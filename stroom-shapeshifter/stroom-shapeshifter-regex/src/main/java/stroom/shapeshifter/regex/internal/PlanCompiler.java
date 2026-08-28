/*
 * Copyright 2016-2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.shapeshifter.regex.internal;

import stroom.shapeshifter.regex.Encoding;
import stroom.shapeshifter.regex.PatternCompileException;
import stroom.shapeshifter.regex.PatternCompileException.Reason;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Lowers a one-pass {@link Hir} into a {@link Plan}.
 * <p>
 * Because the pattern is known to be one-pass before this runs, every decision point can be
 * settled by looking at a single upcoming byte: alternation becomes a dispatch table, and a
 * repetition becomes a scan loop that stops exactly where the following construct begins. No
 * position ever needs revisiting, which is what removes the automaton.
 * <p>
 * Class tests are specialised by cardinality, which measurement showed matters more than
 * dispatch overhead: a single byte becomes an equality test, the complement of a single byte
 * becomes a scan-until, and only the general case uses a lookup table.
 */
public final class PlanCompiler {

    /** Bound on unrolling a bounded repetition of a compound body, to cap plan size. */
    private static final int MAX_UNROLL = 64;

    private final String pattern;
    private final boolean multiline;

    private final List<int[]> instructions = new ArrayList<>();
    private final List<byte[]> literals = new ArrayList<>();
    private final List<byte[]> classes = new ArrayList<>();
    private final List<CharClass> charClasses = new ArrayList<>();
    private final List<int[]> branchTables = new ArrayList<>();

    private final ByteForm form;

    private PlanCompiler(final String pattern, final boolean multiline, final ByteForm form) {
        this.form = form;
        this.pattern = pattern;
        this.multiline = multiline;
    }

    /**
     * <p>The {@code encoding} parameter, placed as phase 1's seam, is read since phase 3: it
     * selects the {@link ByteForm} the lowering compiles against (design/19).
     */
    public static Plan compile(final Hir root,
                               final int groupCount,
                               final boolean multiline,
                               final String pattern,
                               final Encoding encoding) {
        final PlanCompiler compiler = new PlanCompiler(pattern, multiline,
                ByteForm.of(encoding));
        compiler.emitNode(root);
        compiler.emit(Plan.ACCEPT, 0, 0, 0);

        final int count = compiler.instructions.size();
        final int[] op = new int[count];
        final int[] a = new int[count];
        final int[] b = new int[count];
        final int[] c = new int[count];
        for (int i = 0; i < count; i++) {
            final int[] instruction = compiler.instructions.get(i);
            op[i] = instruction[0];
            a[i] = instruction[1];
            b[i] = instruction[2];
            c[i] = instruction[3];
        }

        return new Plan(op, a, b, c,
                compiler.literals.toArray(new byte[0][]),
                compiler.classes.toArray(new byte[0][]),
                compiler.charClasses.toArray(new CharClass[0]),
                compiler.branchTables.toArray(new int[0][]),
                2 * (groupCount + 1),
                groupCount,
                multiline,
                Analysis.nullable(root)
                        ? null
                        : toTable(Analysis.first(root)),
                Analysis.byteLength(root)[0],
                compiler.form);
    }

    // -----------------------------------------------------------------------------------
    // Emission
    // -----------------------------------------------------------------------------------

    private void emitNode(final Hir node) {
        switch (node) {
            case Hir.Empty ignored -> {
            }

            case Hir.Bytes bytes -> {
                if (bytes.value().length == 1) {
                    emit(Plan.MATCH_BYTE, bytes.value()[0] & 0xFF, 0, 0);
                } else if (bytes.value().length > 1) {
                    emit(Plan.MATCH_LITERAL, addLiteral(bytes.value()), 0, 0);
                }
            }

            case Hir.CharClass charClass -> {
                if (charClass.set().isAsciiOnly()) {
                    // Every member is one byte, so the lead-byte table decides on its own.
                    final BitSet leads = charClass.leadBytes();
                    if (leads.cardinality() == 1) {
                        emit(Plan.MATCH_BYTE, leads.nextSetBit(0), 0, 0);
                    } else {
                        emit(Plan.MATCH_CLASS, addClass(leads), 0, 0);
                    }
                } else {
                    // A member may be several bytes, so the match must consume a whole
                    // character or a group span could split one.
                    emit(Plan.MATCH_CHAR, addCharClass(charClass), 0, 0);
                }
            }

            case Hir.Assertion assertion -> emit(Plan.ASSERT, assertion.kind().ordinal(), 0, 0);

            case Hir.Group group -> {
                if (group.capturing()) {
                    emit(Plan.SAVE, 2 * group.index(), 0, 0);
                    emitNode(group.body());
                    emit(Plan.SAVE, 2 * group.index() + 1, 0, 0);
                } else {
                    emitNode(group.body());
                }
            }

            case Hir.Concat concat -> concat.items().forEach(this::emitNode);

            case Hir.Alt alt -> emitAlternation(alt);

            case Hir.Repeat repeat -> emitRepeat(repeat);

            // Fancy constructs are never one-pass — Analysis reports a violation for each — so
            // they cannot reach the plan compiler.
            case Hir.Backref ignored -> throw new IllegalStateException(
                    "a backreference cannot compile to a scan plan");
            case Hir.Look ignored -> throw new IllegalStateException(
                    "lookaround cannot compile to a scan plan");
            case Hir.Atomic ignored -> throw new IllegalStateException(
                    "an atomic group cannot compile to a scan plan");
        }
    }

    /**
     * Alternation compiles to a single dispatch on the next byte. One-pass guarantees the
     * branches' first-sets are disjoint, so the byte identifies at most one branch.
     */
    private void emitAlternation(final Hir.Alt alt) {
        // The table is held by reference, so filling it in as branches are emitted is live.
        final int[] table = new int[257];
        Arrays.fill(table, Plan.NO_TARGET);
        emit(Plan.BRANCH, addBranchTable(table), 0, 0);

        final List<Integer> exitJumps = new ArrayList<>();
        for (final Hir branch : alt.branches()) {
            final int start = nextPc();
            final BitSet first = Analysis.first(branch);
            for (int i = first.nextSetBit(0); i >= 0; i = first.nextSetBit(i + 1)) {
                if (table[i] == Plan.NO_TARGET) {
                    table[i] = start;
                }
            }
            if (Analysis.nullable(branch) && table[Plan.BRANCH_DEFAULT] == Plan.NO_TARGET) {
                table[Plan.BRANCH_DEFAULT] = start;
            }
            emitNode(branch);
            exitJumps.add(emit(Plan.JUMP, 0, 0, 0));
        }

        final int exit = nextPc();
        exitJumps.forEach(pc -> instructions.get(pc)[1] = exit);
    }

    private void emitRepeat(final Hir.Repeat repeat) {
        final Hir body = repeat.body();
        final Hir.CharClass charClass = asCharClass(body);

        if (charClass != null) {
            emitScan(charClass, repeat.min(), repeat.max());
            return;
        }

        // A compound body: unroll the mandatory copies, then loop or unroll the optional ones.
        if (repeat.min() > MAX_UNROLL) {
            throw new PatternCompileException(Reason.UNSUPPORTED, pattern, -1,
                    "repetition minimum above " + MAX_UNROLL + " is not supported for a compound body");
        }
        for (int i = 0; i < repeat.min(); i++) {
            emitNode(body);
        }

        final BitSet bodyFirst = Analysis.first(body);
        if (repeat.isUnbounded()) {
            final int[] table = new int[257];
            Arrays.fill(table, Plan.NO_TARGET);
            final int branchPc = emit(Plan.BRANCH, 0, 0, 0);
            final int bodyStart = nextPc();
            for (int i = bodyFirst.nextSetBit(0); i >= 0; i = bodyFirst.nextSetBit(i + 1)) {
                table[i] = bodyStart;
            }
            emitNode(body);
            emit(Plan.JUMP, branchPc, 0, 0);
            table[Plan.BRANCH_DEFAULT] = nextPc();
            instructions.get(branchPc)[1] = addBranchTable(table);
        } else {
            final int optional = repeat.max() - repeat.min();
            if (optional > MAX_UNROLL) {
                throw new PatternCompileException(Reason.UNSUPPORTED, pattern, -1,
                        "repetition range wider than " + MAX_UNROLL + " is not supported for a compound body");
            }
            final List<Integer> branchPcs = new ArrayList<>();
            final List<int[]> tables = new ArrayList<>();
            for (int i = 0; i < optional; i++) {
                final int[] table = new int[257];
                Arrays.fill(table, Plan.NO_TARGET);
                branchPcs.add(emit(Plan.BRANCH, 0, 0, 0));
                tables.add(table);
                final int bodyStart = nextPc();
                for (int j = bodyFirst.nextSetBit(0); j >= 0; j = bodyFirst.nextSetBit(j + 1)) {
                    table[j] = bodyStart;
                }
                emitNode(body);
            }
            final int exit = nextPc();
            for (int i = 0; i < branchPcs.size(); i++) {
                tables.get(i)[Plan.BRANCH_DEFAULT] = exit;
                instructions.get(branchPcs.get(i))[1] = addBranchTable(tables.get(i));
            }
        }
    }

    /**
     * Emits the scan op for a repeated character class, specialised as far as the class allows.
     * <p>
     * Byte-level scanning is used when it provably covers the same span as character-level
     * scanning: either every member is one byte, or every non-ASCII code point is a member — the
     * case for {@code .} and for negated ASCII classes such as {@code [^,]}, where a continuation
     * byte is never one of the excluded ASCII bytes. Counted repetition is excluded because
     * {@code X{2}} means two characters, which is not two bytes.
     * <p>The equivalence proof assumes validly encoded input, and D38 makes that the caller's
     * contract rather than this compiler's problem: on undecodable bytes these ops are
     * deliberately permissive where the dialect's ruled semantics is strict — the licensed
     * deviation recorded in design/05 §3.2 and pinned by {@code GreedyRunRawBytesTest}.
     * Composition supplies validity where a feed cannot promise it.
     */
    private void emitScan(final Hir.CharClass charClass, final int min, final int max) {
        final boolean unbounded = max == Hir.Repeat.UNBOUNDED;
        // Under a single-byte form every class byte-scans, counted repeats included — one byte
        // is one character, so the count objections below cannot arise (design 19 phase 3).
        final boolean byteScanEquivalent = form.singleByte()
                                           || charClass.set().isAsciiOnly()
                                           || (charClass.set().containsAllNonAscii() && unbounded && min <= 1);
        if (!byteScanEquivalent) {
            emit(Plan.SCAN_WHILE_CHAR, addCharClass(charClass), min, max);
            return;
        }

        // A byte-level scan must accept every byte that can appear *within* an accepted
        // character, not only the bytes that can lead one — otherwise it would stop on the first
        // continuation byte. Where all non-ASCII code points are members, that is every high
        // byte; where the class is ASCII-only, lead bytes are the whole story. A single-byte
        // form has no within: the baked lead bytes are the exact member bytes, and adding the
        // high range would hand unmapped bytes to a scan that strictness says must stop.
        final BitSet set = (BitSet) charClass.leadBytes().clone();
        if (!form.singleByte() && !charClass.set().isAsciiOnly()) {
            set.set(0x80, 0x100);
        }
        final int cardinality = set.cardinality();
        if (cardinality == 1) {
            emit(Plan.SCAN_WHILE_BYTE, set.nextSetBit(0), min, max);
        } else if (cardinality == 255) {
            // The complement of one byte: scanning until that byte is the same thing, and is the
            // shape a memchr-style search accelerates.
            final BitSet complement = new BitSet(256);
            complement.set(0, 256);
            complement.andNot(set);
            emit(Plan.SCAN_UNTIL_BYTE, complement.nextSetBit(0), min, max);
        } else {
            emit(Plan.SCAN_WHILE_CLASS, addClass(set), min, max);
        }
    }

    /** The class for a node that always matches exactly one character, else null. */
    private static Hir.CharClass asCharClass(final Hir node) {
        return switch (node) {
            case Hir.CharClass charClass -> charClass;
            case Hir.Group group -> group.capturing()
                    ? null // the capture must be emitted, so it cannot collapse to a scan
                    : asCharClass(group.body());
            default -> null;
        };
    }

    // -----------------------------------------------------------------------------------
    // Emitter plumbing
    // -----------------------------------------------------------------------------------

    private int emit(final int op, final int a, final int b, final int c) {
        instructions.add(new int[]{op, a, b, c});
        return instructions.size() - 1;
    }

    private int nextPc() {
        return instructions.size();
    }

    private int addLiteral(final byte[] value) {
        literals.add(value);
        return literals.size() - 1;
    }

    private int addClass(final BitSet set) {
        classes.add(toTable(set));
        return classes.size() - 1;
    }

    private int addCharClass(final Hir.CharClass charClass) {
        charClasses.add(new CharClass(charClass.set(), charClass.label(), form));
        return charClasses.size() - 1;
    }

    private int addBranchTable(final int[] table) {
        branchTables.add(table);
        return branchTables.size() - 1;
    }

    private static byte[] toTable(final BitSet set) {
        final byte[] table = new byte[256];
        for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
            table[i] = 1;
        }
        return table;
    }
}
