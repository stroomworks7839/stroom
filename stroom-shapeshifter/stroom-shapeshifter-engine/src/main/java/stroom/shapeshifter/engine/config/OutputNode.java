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

package stroom.shapeshifter.engine.config;

import stroom.shapeshifter.engine.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * One instruction in a template's body.
 *
 * <p>A body is a flat sequence of instructions, each writing to the output in turn — not a tree
 * of elements that has to be balanced. That is XSLT's streaming model, and the names follow
 * XSLT's wherever an equivalent exists, because the people who will write these configurations
 * already know what {@code value-of} and {@code apply-templates} do.
 *
 * <p>The transform instructions at the end are the function library. Each takes one or more
 * values, produces one, and either writes it or — when {@code name} is set — binds it to a
 * variable instead. That dual behaviour is why they are instructions rather than expressions.
 */
public sealed interface OutputNode permits OutputNode.Holder, OutputNode.Binding, OutputNode.Leaf {

    /**
     * An instruction that holds bodies, walked in written order (D47). Every walk that needs to
     * look inside an instruction asks here rather than carrying its own list of containers,
     * which twice stopped short of an iteration's body before this was said once (design 27
     * phase 1). A record is a holder or it is not: the {@code permits} list is the statement.
     */
    sealed interface Holder extends OutputNode
            permits If, Choose, Switch, Variable, Element, Attribute, ForEach, ForEachGroup {

        /** The bodies nested directly inside, in written order. */
        List<List<OutputNode>> bodies();
    }

    /**
     * An instruction that may bind a name instead of, or as well as, writing (D47). The
     * transforms bind when named and write when not; a variable, a sequence and a key always
     * bind, their constructors refusing a missing name.
     */
    sealed interface Binding extends OutputNode
            permits Transform, Variable, Sequence, Key, KeyGet, Count, Sum, Avg, Min, Max,
                    DistinctValues, ValueMap {

        String name();

        /** Whether this instruction binds rather than writes. */
        default boolean binds() {
            return name() != null;
        }
    }

    /**
     * A transform: one or more selected values in, one value out, written or bound (D47). The
     * function library's shape, and a function call's.
     */
    sealed interface Transform extends Binding
            permits Translate, StringJoin, Call, Replace, LowerCase, UpperCase, NormalizeSpace,
                    Trim, Substring, Tokenize, Number, Add, Subtract, Multiply, Divide, Mod, Round,
                    Floor, Ceiling, Abs, StringLength, SubstringBefore, SubstringAfter, StartsWith,
                    EndsWith, Contains, FormatNumber, ParseDate, FormatDate {

        List<RefExpression> select();
    }

    /** Everything else: writes, reads or declares, and holds nothing (D47). */
    sealed interface Leaf extends OutputNode
            permits Text, ValueOf, EmitError, ApplyTemplates, CallTemplate, Namespace, Append {

    }

    /**
     * An instruction whose text may be a regular expression the compiler interns (D47). A
     * marker, not a classification: it is outside the {@code permits} lists, and an instruction
     * with a pattern of its own declares it here, in the same clause that says everything else
     * about it, so the pattern-collecting walk needs no list of its own.
     */
    interface Regexed {

        String pattern();

        boolean isRegex();
    }

    /**
     * Emit a message into the run's message stream, as {@code value-of} emits into output —
     * authored diagnostics for paths the author can name, like the cautionary eater (D36).
     * A {@link Severity#FATAL} emission aborts the run.
     */
    record EmitError(Severity severity, RefExpression message) implements Leaf {

    }

    /** Write literal text. XSLT: {@code xsl:text}. */
    record Text(String value) implements Leaf {

    }

    /** Write the value of an expression. XSLT: {@code xsl:value-of}. */
    record ValueOf(RefExpression select) implements Leaf {

    }

    /** Run a body if a condition holds. XSLT: {@code xsl:if}. */
    record If(Condition test, List<OutputNode> then) implements Holder {

        public If {
            then = then == null ? List.of() : List.copyOf(then);
        }

        @Override
        public List<List<OutputNode>> bodies() {
            return List.of(then);
        }
    }

    /** Run the first branch whose condition holds. XSLT: {@code xsl:choose}. */
    record Choose(List<WhenBranch> when, List<OutputNode> otherwise) implements Holder {

        public Choose {
            when = when == null ? List.of() : List.copyOf(when);
            otherwise = otherwise == null ? List.of() : List.copyOf(otherwise);
        }

        /** The branches in written order, then the otherwise. */
        @Override
        public List<List<OutputNode>> bodies() {
            final List<List<OutputNode>> bodies = new ArrayList<>(when.size() + 1);
            for (final WhenBranch branch : when) {
                bodies.add(branch.body());
            }
            bodies.add(otherwise);
            return bodies;
        }
    }

    /** Run the branch whose value matches, comparing a single expression against literals. */
    record Switch(RefExpression select,
                  List<SwitchCase> cases,
                  List<OutputNode> defaultBody) implements Holder {

        public Switch {
            cases = cases == null ? List.of() : List.copyOf(cases);
            defaultBody = defaultBody == null ? List.of() : List.copyOf(defaultBody);
        }

        /** The cases in written order, then the default. */
        @Override
        public List<List<OutputNode>> bodies() {
            final List<List<OutputNode>> bodies = new ArrayList<>(cases.size() + 1);
            for (final SwitchCase switchCase : cases) {
                bodies.add(switchCase.body());
            }
            bodies.add(defaultBody);
            return bodies;
        }
    }

    /** Match templates against some content. XSLT: {@code xsl:apply-templates}. */
    record ApplyTemplates(ApplyDirective directive) implements Leaf {

    }

    /**
     * Invoke a template by name, with no content matching. XSLT: {@code xsl:call-template}.
     *
     * @param name      the template to invoke
     * @param withParam parameters to pass, in order
     */
    record CallTemplate(String name, List<Param> withParam) implements Leaf {

        public CallTemplate {
            withParam = withParam == null ? List.of() : List.copyOf(withParam);
        }
    }

    /** Bind a variable to what a nested body writes. XSLT: {@code xsl:variable}. */
    record Variable(String name, List<OutputNode> body) implements Holder, Binding {

        public Variable {
            if (name == null || name.isEmpty()) {
                throw new ConfigException("A variable needs a name");
            }
            body = body == null ? List.of() : List.copyOf(body);
        }

        @Override
        public List<List<OutputNode>> bodies() {
            return List.of(body);
        }
    }

    /**
     * Open an element around a body; it closes when the body finishes. XSLT: {@code xsl:element}.
     *
     * <p>The three structural instructions (design 20, D40). Inside an element's body, what
     * {@code text} and {@code value-of} write is content; the sink escapes it. Attributes and
     * namespaces must come before any content — the compiler checks the body as written, the
     * sink checks what arrives.
     *
     * @param name        the qualified name, prefix included if it has one
     * @param namespace   the namespace URI, or null to use whatever the prefix is bound to in scope;
     *                    given, and not already bound to the prefix, the element declares it
     * @param omitIfEmpty leave no trace if nothing arrived — no declaration, attribute or content
     *                    (DS3's lazy {@code <record>}, P1 in design 21)
     */
    record Element(String name, String namespace, boolean omitIfEmpty, List<OutputNode> body)
            implements Holder {

        public Element {
            body = body == null ? List.of() : List.copyOf(body);
        }

        @Override
        public List<List<OutputNode>> bodies() {
            return List.of(body);
        }
    }

    /**
     * An attribute of the enclosing element whose value is what the body writes.
     * XSLT: {@code xsl:attribute}. The body may write text and values, not structure.
     *
     * @param omitIfEmpty drop the attribute if its value came out empty (DS3's normalised
     *                    {@code <data>} attributes, P2 in design 21)
     */
    record Attribute(String name, boolean omitIfEmpty, List<OutputNode> body) implements Holder {

        public Attribute {
            body = body == null ? List.of() : List.copyOf(body);
        }

        @Override
        public List<List<OutputNode>> bodies() {
            return List.of(body);
        }
    }

    /** Declare a prefix on the enclosing element; the empty prefix is the default namespace. */
    record Namespace(String prefix, String uri) implements Leaf {

        public Namespace {
            prefix = prefix == null ? "" : prefix;
        }
    }

    /**
     * Map a value through a lookup table.
     *
     * @param select       the value to look up
     * @param entries      the table
     * @param defaultValue what to produce when nothing matches, or null to produce nothing
     * @param name         bind the result to this variable instead of writing it, or null
     */
    record ValueMap(RefExpression select,
                    List<Entry> entries,
                    String defaultValue,
                    String name) implements Binding {

        public ValueMap {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    // -----------------------------------------------------------------------------------
    // Transform functions
    // -----------------------------------------------------------------------------------

    /**
     * A call to a registered function (design 26 §3): the function by name, its arguments as a
     * select list — positional, absent where a reference resolves to nothing — and, with a
     * name, a binding rather than a write.
     */
    record Call(String function, List<RefExpression> select, String name) implements Transform {

    }

    /**
     * Substitute substrings, one search string at a time, in order: each {@code from} entry is
     * replaced throughout by the {@code to} entry at the same index, or deleted when {@code to}
     * has no entry there. Whole substrings, not characters — this is <i>not</i> XSLT's
     * {@code translate()}, whose arguments are parallel character sets.
     */
    record Translate(List<RefExpression> select,
                     List<String> from,
                     List<String> to,
                     String name) implements Transform {

        public Translate {
            select = select == null ? List.of() : List.copyOf(select);
            from = from == null ? List.of() : List.copyOf(from);
            to = to == null ? List.of() : List.copyOf(to);
        }
    }

    /** Join values with a separator. XSLT: {@code string-join()}. */
    record StringJoin(List<RefExpression> select, String separator, String name)
            implements Transform {

        public StringJoin {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /**
     * Replace occurrences of a pattern. XSLT: {@code replace()}.
     *
     * @param isRegex whether {@code pattern} is a regex or a literal. It defaults to literal,
     *                which is worth knowing: most patterns in the corpus are literals
     */
    record Replace(List<RefExpression> select,
                   String pattern,
                   String replacement,
                   boolean isRegex,
                   String name) implements Transform, Regexed {

        public Replace {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Lower-case. XSLT: {@code lower-case()}. */
    record LowerCase(List<RefExpression> select, String name) implements Transform {

        public LowerCase {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Upper-case. XSLT: {@code upper-case()}. */
    record UpperCase(List<RefExpression> select, String name) implements Transform {

        public UpperCase {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Collapse runs of whitespace. XSLT: {@code normalize-space()}. */
    record NormalizeSpace(List<RefExpression> select, String name) implements Transform {

        public NormalizeSpace {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Strip leading and trailing whitespace. */
    record Trim(List<RefExpression> select, String name) implements Transform {

        public Trim {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /**
     * Take part of a value. XSLT: {@code substring()} — and the base is <b>version-gated</b>
     * (design/17 §7, superseding E21's documented-not-aligned ruling): below version 5
     * {@code start} is 0-based, faithful to the ported library and existing configurations;
     * from version 5 it is 1-based, XSLT's own reading, with a start below 1 shrinking the
     * window per XPath's rule. An <b>omitted</b> start means "from the beginning" under
     * either base — which is why it is nullable rather than defaulting to a number that
     * would change meaning at the gate. The compiler warns, once per configuration, on any
     * pre-version-5 configuration a bump would change.
     */
    record Substring(List<RefExpression> select,
                     Integer start,
                     Integer length,
                     String name) implements Transform {

        public Substring {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Split on a delimiter. XSLT: {@code tokenize()}. */
    record Tokenize(List<RefExpression> select, String delimiter, String name)
            implements Transform {

        public Tokenize {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Read as a number. XSLT: {@code number()}. */
    record Number(List<RefExpression> select, String name) implements Transform {

        public Number {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    // -----------------------------------------------------------------------------------
    // Arithmetic (design/17 §5). Inputs cast per the table; any absent or non-numeric
    // input makes the whole result absent — not zero, and not a partial fold.
    // -----------------------------------------------------------------------------------

    /** Fold {@code +} over the inputs. Whole numbers stay exact; overflow promotes (§11). */
    record Add(List<RefExpression> select, String name) implements Transform {

        public Add {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code a - b}, exactly two inputs. */
    record Subtract(List<RefExpression> select, String name) implements Transform {

        public Subtract {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Fold {@code *} over the inputs. */
    record Multiply(List<RefExpression> select, String name) implements Transform {

        public Multiply {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code a / b}. Whole when exact, fractional otherwise; division by zero is absent. */
    record Divide(List<RefExpression> select, String name) implements Transform {

        public Divide {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code a mod b}, the sign following the dividend — XPath's {@code mod}, Java's {@code %}. */
    record Mod(List<RefExpression> select, String name) implements Transform {

        public Mod {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Round half-up on ties — XPath's {@code round()}: {@code round(-2.5)} is {@code -2}. */
    record Round(List<RefExpression> select, String name) implements Transform {

        public Round {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** XSLT: {@code floor()}. */
    record Floor(List<RefExpression> select, String name) implements Transform {

        public Floor {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** XSLT: {@code ceiling()}. */
    record Ceiling(List<RefExpression> select, String name) implements Transform {

        public Ceiling {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** XPath: {@code abs()}. */
    record Abs(List<RefExpression> select, String name) implements Transform {

        public Abs {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    // -----------------------------------------------------------------------------------
    // The string additions (design/17 §6)
    // -----------------------------------------------------------------------------------

    /** Length in code points — what a person would count. XSLT: {@code string-length()}. */
    record StringLength(List<RefExpression> select, String name) implements Transform {

        public StringLength {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /**
     * The part before the first occurrence of a marker, <b>absent when the marker is not
     * found</b> — not the empty string, so a condition can tell the two apart (§6). XSLT
     * returns {@code ""} for both; the written output is identical, the testable value is not.
     */
    record SubstringBefore(List<RefExpression> select,
                           String marker,
                           String name) implements Transform {

        public SubstringBefore {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** The part after the first occurrence of a marker; absent when not found, as above. */
    record SubstringAfter(List<RefExpression> select,
                          String marker,
                          String name) implements Transform {

        public SubstringAfter {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code starts-with()} as a value — for binding and for choosing on a computed flag. */
    record StartsWith(List<RefExpression> select, String prefix, String name) implements Transform {

        public StartsWith {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code ends-with()} as a value. */
    record EndsWith(List<RefExpression> select, String suffix, String name) implements Transform {

        public EndsWith {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code contains()} as a value. The condition of the same name stays; this one binds. */
    record Contains(List<RefExpression> select, String substring, String name)
            implements Transform {

        public Contains {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /**
     * Format a number through a picture string — {@code java.text.DecimalFormat} under
     * {@code Locale.ROOT}, which matches XSLT's default decimal format for the ordinary
     * pictures and diverges on the edges (per-mille, explicit {@code +}, infinity); the
     * boundary is recorded by the proving case. XSLT: {@code format-number()}.
     */
    record FormatNumber(List<RefExpression> select,
                        String picture,
                        String name) implements Transform {

        public FormatNumber {
            select = select == null ? List.of() : List.copyOf(select);
            if (picture == null || picture.isEmpty()) {
                throw new ConfigException("A format-number needs a picture");
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // Sequences (design/16). A store is the sequence type — there is no new value kind —
    // and these are the instructions that declare one, add to one, and walk one.
    // -----------------------------------------------------------------------------------

    /**
     * Declare a sequence, and empty it. XSLT has no equivalent: it is what makes a value
     * captured in a nested level outlive the level, which nesting alone cannot do because a
     * child level's stores are cleared on its first match of each new parent match (E19).
     *
     * <p>Declaring is required rather than implied. It is what puts the accumulation's
     * lifetime where an author can see it, and it gives the compiler somewhere to stand:
     * an {@code append} to a name no {@code sequence} declares is refused, and a name that
     * collides with a capture is refused, because a template's first-match clearing would
     * empty the accumulation underneath it mid-run (design/16 §9).
     */
    record Sequence(String name) implements Binding {

        public Sequence {
            if (name == null || name.isEmpty()) {
                throw new ConfigException("A sequence needs a name");
            }
        }
    }

    /**
     * Add a value to a declared sequence, at its next free index.
     *
     * <p>An absent value appends nothing — not a hole. A dense sequence's index is its
     * position, so a hole in one would mean nothing at all; the sparse reading belongs to
     * capture-indexed stores, where an index is a match number and a gap is meaningful.
     */
    record Append(String name, RefExpression select) implements Leaf {

        public Append {
            if (name == null || name.isEmpty()) {
                throw new ConfigException("An append needs the name of a sequence");
            }
        }
    }

    /**
     * Walk a sequence, running a body once per populated entry — XSLT's {@code xsl:for-each},
     * over what has been captured rather than over a tree.
     *
     * @param select the sequence's <b>name</b>, not a reference (design/16 §4.1, ruled): a
     *               {@link RefExpression} resolves to exactly one value by construction, and
     *               making it sometimes mean "all of them" would put a second reading into
     *               the one type every instruction shares
     * @param as     binds the item's value for the body, or null to read it by index alone
     * @param body   what runs per entry, with {@code __index}, {@code __position} and
     *               {@code __last} bound (§4.3)
     */
    record ForEach(String select, String as, List<Sort> sort, List<OutputNode> body)
            implements Holder {

        public ForEach {
            if (select == null || select.isEmpty()) {
                throw new ConfigException("A for-each needs the name of a sequence to walk");
            }
            sort = sort == null ? List.of() : List.copyOf(sort);
            body = body == null ? List.of() : List.copyOf(body);
        }

        @Override
        public List<List<OutputNode>> bodies() {
            return List.of(body);
        }
    }

    /**
     * Group a sequence's entries and run a body once per group — XSLT's
     * {@code xsl:for-each-group} with {@code group-by} (design/16 §6).
     *
     * <p>Groups form in <b>order of first appearance</b>, XSLT's rule and the one a log
     * summary wants. What is grouped is the <b>index set</b>, not the values: two captures of
     * one template at match <i>i</i> belong to the same record, so a group's members are
     * positions that any parallel store can be read at. That is how a byte engine with no
     * tree reaches what {@code current-group()} reaches.
     *
     * @param select  the sequence whose indices are grouped
     * @param groupBy the key, evaluated per entry with {@code __index} bound, or null to
     *                group by the entry's own value
     */
    record ForEachGroup(String select, RefExpression groupBy, List<OutputNode> body)
            implements Holder {

        public ForEachGroup {
            if (select == null || select.isEmpty()) {
                throw new ConfigException("A for-each-group needs the name of a sequence");
            }
            body = body == null ? List.of() : List.copyOf(body);
        }

        @Override
        public List<List<OutputNode>> bodies() {
            return List.of(body);
        }
    }

    /**
     * Build a random-access index over a sequence — XSLT's {@code xsl:key} (design/16 §8).
     *
     * <p>The index is the same {@code Map<String, int[]>} a grouping builds; what a key adds
     * is reaching **one** entry of it by value, without walking the groups. It is an
     * instruction rather than a project-level declaration, so it runs where its inputs are
     * ready — typically the epilogue, once the level that fills the sequence has finished —
     * and its cost is paid somewhere an author can see.
     *
     * <p>Key names are their own namespace: a key and a sequence may share a name without
     * colliding, because nothing can confuse the two at a use site.
     *
     * @param groupBy the key each entry is filed under, evaluated with {@code __index}
     *                bound, or null to file each entry under its own value
     */
    record Key(String name, String select, RefExpression groupBy) implements Binding {

        public Key {
            if (name == null || name.isEmpty()) {
                throw new ConfigException("A key needs a name");
            }
            if (select == null || select.isEmpty()) {
                throw new ConfigException("A key needs the name of a sequence to index");
            }
        }
    }

    /**
     * Look one value up in a key — XSLT's {@code key()} — binding the matching entries as a
     * dense sequence of store indices, the same shape as {@code __group}.
     *
     * <p>A value with no entry binds an <b>empty</b> sequence, which a walk runs over zero
     * times and {@code count} reports as 0: the same non-answer XSLT's {@code key()} gives,
     * and not an error.
     */
    record KeyGet(String key, RefExpression select, String name) implements Binding {

        public KeyGet {
            if (key == null || key.isEmpty()) {
                throw new ConfigException("A key-get needs the name of a key");
            }
            if (select == null) {
                throw new ConfigException("A key-get needs a value to look up");
            }
            if (name == null || name.isEmpty()) {
                throw new ConfigException("A key-get needs a name to bind: it produces a"
                                          + " sequence, which has nothing to write to output");
            }
        }
    }

    /**
     * One key of an iteration's ordering (design/16 §5). {@code by} is evaluated once per
     * entry with {@code __index} bound, so a key can read the item, a parallel store at the
     * same match, or a concatenation.
     *
     * <p>Ordering is by the same {@code as} cast every typed read in the engine uses; uncast
     * it compares string forms, which is the one <b>total</b> reading and therefore the only
     * safe default for something that must order a whole column (design/17 §8).
     */
    record Sort(RefExpression by, Order order, Cast as) {

        public Sort {
            if (by == null) {
                throw new ConfigException("A sort key needs something to sort by");
            }
            order = order == null ? Order.ASCENDING : order;
        }
    }

    /** Which way a sort key runs. */
    enum Order {
        ASCENDING, DESCENDING
    }

    /**
     * The folds (design/16 §8). Each names a <b>sequence</b> rather than taking a reference,
     * for §4.1's reason: a reference resolves to exactly one value by construction. Where a
     * fold over computed values is wanted it is composed — walk the source, {@code append}
     * the computed value, fold that.
     */
    record Count(String select, String name) implements Binding {

        public Count {
            requireSequence(select, "count");
        }
    }

    /**
     * Add up a sequence. Whole while every entry is whole and nothing overflows, promoting to
     * fractional otherwise (design/17 §11); <b>zero</b> over an empty sequence, which is
     * XPath's answer for {@code sum(())}.
     */
    record Sum(String select, String name) implements Binding {

        public Sum {
            requireSequence(select, "sum");
        }
    }

    /**
     * The mean. <b>Absent</b> over an empty sequence rather than zero — XPath's answer for
     * {@code avg(())} too, and the engine's own word for "there was no value".
     */
    record Avg(String select, String name) implements Binding {

        public Avg {
            requireSequence(select, "avg");
        }
    }

    /** The smallest entry, ordered by {@code as} — uncast orders by string form (17 §8). */
    record Min(String select, Cast as, String name) implements Binding {

        public Min {
            requireSequence(select, "min");
        }
    }

    /** The largest entry, under the same ordering. */
    record Max(String select, Cast as, String name) implements Binding {

        public Max {
            requireSequence(select, "max");
        }
    }

    /**
     * The distinct entries of a sequence, bound as a dense one — first appearance order,
     * compared by string form, which is the same total reading an uncast ordering uses.
     */
    record DistinctValues(String select, String name) implements Binding {

        public DistinctValues {
            requireSequence(select, "distinct-values");
            if (name == null || name.isEmpty()) {
                throw new ConfigException("A distinct-values needs a name to bind: it produces"
                                          + " a sequence, which has nothing to write to output");
            }
        }
    }

    private static void requireSequence(final String select, final String what) {
        if (select == null || select.isEmpty()) {
            throw new ConfigException("A " + what + " needs the name of a sequence");
        }
    }

    // -----------------------------------------------------------------------------------
    // Dates (design/17 §9): the composed pair the 2026-08-21 ruling chose over
    // stroom:format-date's conflated signature
    // -----------------------------------------------------------------------------------

    /**
     * Parse text into an instant. The pattern is a {@code DateTimeFormatter} pattern under
     * the root locale, or one of the reserved names {@code iso}, {@code epoch-millis},
     * {@code epoch-seconds}. The timezone supplies the placement only when the pattern
     * parses no offset; a parsed offset always wins and is carried. A pattern with no year
     * needs a {@code reference} date — the nearest-year rule, Stroom's own with the input
     * made explicit — and is refused at compile time without one.
     */
    record ParseDate(List<RefExpression> select,
                     String pattern,
                     String timezone,
                     RefExpression reference,
                     String name) implements Transform {

        public ParseDate {
            select = select == null ? List.of() : List.copyOf(select);
            if (pattern == null || pattern.isEmpty()) {
                throw new ConfigException("A parse-date needs a pattern");
            }
        }
    }

    /**
     * Render an instant. Zone precedence: the instruction's {@code timezone} if given, else
     * the instant's own carried offset, else UTC — a value round-trips through its original
     * offset unless the author says otherwise.
     */
    record FormatDate(List<RefExpression> select,
                      String pattern,
                      String timezone,
                      String name) implements Transform {

        public FormatDate {
            select = select == null ? List.of() : List.copyOf(select);
            if (pattern == null || pattern.isEmpty()) {
                throw new ConfigException("A format-date needs a pattern");
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // Supporting shapes
    // -----------------------------------------------------------------------------------

    /** One branch of a {@link Choose}. XSLT: {@code xsl:when}. */
    record WhenBranch(Condition test, List<OutputNode> body) {

        public WhenBranch {
            body = body == null ? List.of() : List.copyOf(body);
        }
    }

    /** One case of a {@link Switch}. */
    record SwitchCase(String value, List<OutputNode> body) {

        public SwitchCase {
            body = body == null ? List.of() : List.copyOf(body);
        }
    }

    /** One entry of a {@link ValueMap} table. */
    record Entry(String from, String to) {

    }

    /** One parameter passed to a template. */
    record Param(String name, RefExpression value) {

    }

    /**
     * What {@code apply-templates} should do.
     *
     * @param select      the content to match templates against
     * @param mode        restrict candidates to templates in this mode, or null for those with
     *                    no mode
     * @param withParam   parameters to pass to whichever template matches
     * @param maxDepth    how deep recursion may go before it is an error
     * @param ignoreErrors suppress the dispatched level's skip and unmatched-content reports.
     *                     This is DS3's {@code ignoreErrors} on the group whose content is being
     *                     dispatched: the container owns the gate, not the templates inside it
     * @param dispatch     how the dispatched level runs (D36), or null to inherit the source
     *                     default. The container owns this too, exactly as DS3's group owned
     *                     {@code matchOrder}
     */
    record ApplyDirective(RefExpression select,
                          String mode,
                          List<Param> withParam,
                          int maxDepth,
                          boolean ignoreErrors,
                          Dispatch dispatch) {

        /** The prefix that marks a mode as the recursive form, minted once here. */
        private static final String RECURSIVE_PREFIX = "__rec_";

        /**
         * Whether this is the recursive form, which runs in its own scope: a directive whose
         * mode is spelt with the recursive prefix (design/16 §1).
         */
        public boolean recursive() {
            return mode != null && mode.startsWith(RECURSIVE_PREFIX);
        }

        /** How deep recursion goes before the engine calls it a runaway. */
        public static final int DEFAULT_MAX_DEPTH = 64;

        public ApplyDirective {
            withParam = withParam == null ? List.of() : List.copyOf(withParam);
            if (maxDepth <= 0) {
                throw new ConfigException("A max depth must be positive: " + maxDepth);
            }
        }
    }
}
