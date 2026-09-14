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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.Instrument;
import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.config.Declaration;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.graph.CompiledCondition;
import stroom.shapeshifter.engine.graph.CompiledMatch;
import stroom.shapeshifter.engine.graph.CompiledOp;
import stroom.shapeshifter.engine.graph.CompiledProject;
import stroom.shapeshifter.engine.graph.CompiledRef;
import stroom.shapeshifter.engine.graph.CompiledTemplate;
import stroom.shapeshifter.engine.graph.VarName;
import stroom.shapeshifter.engine.match.MatchResult;
import stroom.shapeshifter.engine.output.XmlByteSink;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.value.Comparisons;
import stroom.shapeshifter.engine.value.Dates;
import stroom.shapeshifter.engine.value.Transforms;
import stroom.shapeshifter.engine.value.TypedValue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The body interpreter: what a template's body does with a match — the switch over the
 * compiled instruction vocabulary, and everything an instruction reaches for: the variable
 * registry with its scopes and declared values, the key indexes, the sequences, the
 * transforms and function calls, and {@code apply-templates}, which hands a region to the
 * level. One interpreter over sealed records (design 27 §2.2): the ops are the graph's
 * instruction set, and every state an instruction touches is a field of this class or of the
 * run that owns it.
 */
final class Body {

    private final CompiledProject compiled;
    private final Instrument instrument;
    private final List<Message> messages;
    private final FunctionRuntime functions;
    private final VarRegistry vars;

    /**
     * The arithmetic sites that have already drawn a strict_values warning this run — once
     * per instruction site, because once per record on a million-record input is not a
     * diagnostic, it is a flood (design/17 §10).
     */
    private final Set<CompiledOp.Transform> warnedNumeric =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Whether the root reads the input in pieces whose counters restart (design/16 §10).
     *
     * <p>A {@code classify} or {@code any} root is dispatched chunk-at-a-time by repeated
     * {@code Level.dispatch} calls, so template counters reset and a root template's declared
     * names restore <b>per chunk</b>. An accumulation there would summarise the last chunk while
     * presenting itself as a summary of the input — a wrong answer wearing the shape of a right
     * one, the
     * failure design/16 §10 refuses. A whole-buffer run takes the same code path with exactly
     * one chunk, and is therefore fine.
     */
    private boolean chunkedRoot;

    /** The run's encoding in force, told by the run, which a nested dispatch is handed. */
    private Encoding encoding;

    /** {@code strict_values}, read once: a source flag, not a per-transform question. */
    private final boolean strictValues;

    /** {@code max_sequence_entries}, likewise, read once rather than per append. */
    private final int maxSequenceEntries;

    /**
     * The level a body's {@code apply-templates} hands a region to. The body constructs it,
     * because the level binds captures into this body's registry and hands winning matches
     * back to it: the two are one run's state, and the body owns that state.
     */
    private final Level level;

    Body(final CompiledProject compiled,
         final Instrument instrument,
         final List<Message> messages,
         final FunctionRuntime functions,
         final Encoding encoding) {
        this.compiled = compiled;
        this.vars = new VarRegistry(compiled.names());
        this.instrument = instrument;
        this.messages = messages;
        this.functions = functions;
        this.encoding = encoding;
        this.strictValues = compiled.project().source().strictValues();
        this.maxSequenceEntries = compiled.project().source().maxSequenceEntries();
        // this escapes before the constructor ends; the level's constructor only stores it,
        // and the class is final, so nothing reads through it before the run starts.
        this.level = new Level(instrument, messages, vars, functions, this);
    }

    /** The level this body applies templates through, which the run dispatches the root into. */
    Level level() {
        return level;
    }

    /** Whether the root reads its input in pieces whose counters restart; set by the run before the loop. */
    void chunkedRoot(final boolean chunkedRoot) {
        this.chunkedRoot = chunkedRoot;
    }

    /**
     * Enter the source template's execution, which spans the run (design 35 §4): what it
     * declares is declared once and lasts until {@link #leaveSource}. The template is found
     * once, here, rather than carried by the plan, because the plan is about what the body
     * around the input does and this is about what the body declares.
     */
    void enterSource() {
        final CompiledTemplate source = source();
        if (source != null && source.declared().length > 0) {
            vars.push(source.declared(), source.initial());
        }
    }

    /** Leave it, at the end of the run. */
    void leaveSource() {
        final CompiledTemplate source = source();
        if (source != null && source.declared().length > 0) {
            vars.pop();
        }
    }

    private CompiledTemplate source() {
        for (final CompiledTemplate template : compiled.templates()) {
            if (template.match() instanceof CompiledMatch.Source) {
                return template;
            }
        }
        return null;
    }

    /**
     * Run a body against a match: the interpreter's entry, which the level and the run both
     * call. One arm per instruction, so the method is as long as the instruction set (design 27
     * §2.2).
     */
    void body(final CompiledOp[] ops,
              final MatchResult match,
              final int matchCount,
              final byte[] content,
              final Output out,
              final long inputBase,
              final boolean ignoreErrors,
              final int depth) {
        for (final CompiledOp op : ops) {
            switch (op) {
                case final CompiledOp.Text text -> out.write(text.value());
                case final CompiledOp.ValueOf valueOf ->
                        CompiledRefs.write(valueOf.ref(), match, matchCount, vars, out);
                case final CompiledOp.Apply apply ->
                        apply(apply, match, matchCount, content, out, inputBase, ignoreErrors,
                                depth);
                case final CompiledOp.If value -> {
                    if (test(value.test(), match, matchCount)) {
                        body(value.then(), match, matchCount, content, out,
                                inputBase, ignoreErrors, depth);
                    }
                }
                case final CompiledOp.Choose value -> {
                    boolean taken = false;
                    for (final CompiledOp.When branch : value.when()) {
                        if (test(branch.test(), match, matchCount)) {
                            body(branch.body(), match, matchCount, content, out,
                                    inputBase, ignoreErrors, depth);
                            taken = true;
                            break;
                        }
                    }
                    if (!taken) {
                        body(value.otherwise(), match, matchCount, content, out,
                                inputBase, ignoreErrors, depth);
                    }
                }
                case final CompiledOp.Switch value -> {
                    final String selected = textOf(value.select(), match, matchCount);
                    final CompiledOp[] taken = value.cases().get(selected);
                    body(taken == null ? value.defaultBody() : taken, match, matchCount, content,
                            out, inputBase, ignoreErrors, depth);
                }
                case final CompiledOp.Variable value ->
                        variable(value, match, matchCount, content, inputBase, ignoreErrors, depth);
                case final CompiledOp.Element value -> {
                    structure(() -> out.sink().startElement(value.name(), value.namespace(),
                            value.omitIfEmpty()),
                            "element", value.name());
                    body(value.body(), match, matchCount, content, out,
                            inputBase, ignoreErrors, depth);
                    structure(out.sink()::endElement, "element", value.name());
                }
                case final CompiledOp.Attribute value -> {
                    structure(() -> out.sink().startAttribute(value.name(), value.omitIfEmpty()),
                            "attribute", value.name());
                    body(value.body(), match, matchCount, content, out,
                            inputBase, ignoreErrors, depth);
                    structure(out.sink()::endAttribute, "attribute", value.name());
                }
                case final CompiledOp.Namespace value ->
                        structure(() -> out.sink().namespace(value.prefix(), value.uri()),
                                "namespace", value.prefix());
                case final CompiledOp.CallTemplate value ->
                        callTemplate(value, match, matchCount, content, out, inputBase,
                                ignoreErrors, depth);
                case final CompiledOp.Transform value ->
                        transform(value, match, matchCount, out);
                case final CompiledOp.Replace value -> {
                    final List<TypedValue> inputs = inputs(value.select(), match, matchCount);
                    emit(inputs.isEmpty()
                                    ? null
                                    : TypedValue.of(value.replacer()
                                            .replace(inputs.getFirst().asString())),
                            value.name(), matchCount, out);
                }
                case final CompiledOp.CallFunction value ->
                        callFunction(value, match, matchCount, out, inputBase);
                case final CompiledOp.Append value -> {
                    final TypedValue appended = CompiledRefs.resolveValue(
                            value.select(), match, matchCount, vars);
                    if (appended != null) {
                        final TypedValue.List list = listTarget(value.target(), match, matchCount, "append");
                        if (list != null) {
                            guardAccumulation(value.target());
                            final TypedValue stored = TypedValue.Collection.stored(appended);
                            list.append(stored);
                            vars.grew(1 + TypedValue.Collection.elementsOf(stored));
                            guardLive(value.target());
                        }
                    }
                }
                case final CompiledOp.Insert value -> {
                    final TypedValue inserted = CompiledRefs.resolveValue(
                            value.select(), match, matchCount, vars);
                    final TypedValue.List list = listTarget(value.target(), match, matchCount, "insert");
                    if (list != null && inserted != null) {
                        final int position = position(value.position(), match, matchCount, "insert");
                        if (position < 1 || position > list.size() + 1) {
                            fatal("insert at position " + position + " of a list of " + list.size()
                                  + ": positions run from 1 to the size plus one");
                        }
                        final TypedValue stored = TypedValue.Collection.stored(inserted);
                        list.insert(position - 1, stored);
                        vars.grew(1 + TypedValue.Collection.elementsOf(stored));
                        guardLive(value.target());
                    }
                }
                case final CompiledOp.Put value -> put(value, match, matchCount);
                case final CompiledOp.Remove value -> {
                    final TypedValue target = CompiledRefs.resolveValue(value.target(), match, matchCount, vars);
                    final TypedValue key = CompiledRefs.resolveValue(value.key(), match, matchCount, vars);
                    switch (target) {
                        case final TypedValue.List list -> {
                            final int position = position(value.key(), match, matchCount, "remove");
                            if (position >= 1 && position <= list.size()) {
                                vars.grew(-1 - TypedValue.Collection.elementsOf(list.get(position - 1)));
                                list.remove(position - 1);
                            }
                        }
                        case final TypedValue.Map map -> {
                            if (map.contains(key)) {
                                vars.grew(-1 - TypedValue.Collection.elementsOf(map.get(key)));
                                map.remove(key);
                            }
                        }
                        case final TypedValue.Set set -> {
                            if (set.contains(key)) {
                                set.remove(key);
                                vars.grew(-1);
                            }
                        }
                        case null -> {
                        }
                        default -> fatal("remove from something that is not a collection: " + describe(value.target()));
                    }
                }
                case final CompiledOp.Clear value -> {
                    if (CompiledRefs.resolveValue(value.target(), match, matchCount, vars)
                        instanceof final TypedValue.Collection collection) {
                        vars.grew(-collection.elements());
                        collection.clear();
                    }
                }
                case final CompiledOp.Tokenize value -> {
                    final TypedValue input = CompiledRefs.resolveValue(
                            value.select(), match, matchCount, vars);
                    if (value.name() == null) {
                        if (input != null) {
                            // Written straight out, it keeps the joined rendering it always had.
                            out.write(Transforms.tokenize(List.of(input), value.delimiter()));
                        }
                    } else {
                        // Nothing to split is the empty sequence, which a walk runs over zero
                        // times. Leaving the name alone would walk the last record's pieces.
                        bindDense(value.name(), input == null
                                ? List.of()
                                : Transforms.split(input, value.delimiter()));
                    }
                }
                case final CompiledOp.ForEachGroup value ->
                        forEachGroup(value, match, matchCount, content, out,
                                inputBase, ignoreErrors, depth);
                case final CompiledOp.ForEach value ->
                        forEach(value, match, matchCount, content, out,
                                inputBase, ignoreErrors, depth);
                case final CompiledOp.ParseDate value -> {
                    final TypedValue input = CompiledRefs.resolveValue(
                            value.select(), match, matchCount, vars);
                    TypedValue result = null;
                    if (input != null) {
                        // The reference is a date read like any other (design/17 §9.2): a
                        // captured field today, D10's context seam tomorrow. Absent when the
                        // pattern needs it means an absent result, never a guessed year.
                        final TypedValue reference = value.reference() == null
                                ? null
                                : Comparisons.cast(CompiledRefs.resolveValue(
                                        value.reference(), match, matchCount, vars), Cast.DATE);
                        result = Dates.parse(value.parser(), input.asString(),
                                (TypedValue.Instant) reference);
                    }
                    emit(result, value.name(), matchCount, out);
                }
                case final CompiledOp.EmitError value -> {
                    final String text = CompiledRefs.resolveText(
                            value.message(), match, matchCount, vars);
                    messages.add(new Message(value.severity(), text == null ? "" : text));
                    if (value.severity() == Severity.FATAL) {
                        // The message is recorded; the run ends here (D36).
                        throw new AbortRun();
                    }
                }
            }
        }
    }

    private boolean test(final CompiledCondition condition,
                         final MatchResult match,
                         final int matchCount) {
        return Conditions.evaluate(condition, match, matchCount, vars);
    }

    private String textOf(final CompiledRef ref, final MatchResult match, final int matchCount) {
        final String resolved = CompiledRefs.resolveText(ref, match, matchCount, vars);
        return resolved == null ? "" : resolved;
    }

    /**
     * Call a function (design 26): the arguments resolved in the body's scope, positions kept
     * and absence null, sequences handed over whole; the runtime makes the call and says what
     * a skipped, failed or erroring call means; the result is written or bound like any value.
     */
    private void callFunction(final CompiledOp.CallFunction op,
                              final MatchResult match,
                              final int matchCount,
                              final Output out,
                              final long inputBase) {
        final FunctionDefinition definition = op.definition();
        if (functions.skippedInPreview(definition)) {
            emit(null, op.name(), matchCount, out);
            return;
        }
        final List<Kind> kinds = definition.signature().argKinds();
        final int written = op.select().length;
        final List<TypedValue> values = new ArrayList<>(written);
        final List<TypedValue> raw = new ArrayList<>(written);
        final List<List<TypedValue>> sequences = new ArrayList<>(written);
        for (int i = 0; i < written; i++) {
            final VarName store = op.sequences()[i];
            if (store != null) {
                raw.add(null);
                values.add(null);
                sequences.add(entries(store));
                continue;
            }
            final TypedValue resolved = CompiledRefs.resolveValue(
                    op.select()[i], match, matchCount, vars);
            raw.add(resolved);
            values.add(resolved == null ? null : castTo(resolved, kinds.get(i)));
            sequences.add(null);
        }
        final TypedValue result = functions.invoke(op.slot(), definition.name(),
                new Arguments(values, raw, sequences),
                Level.locate(inputBase, match.matchStart()), match.advance() - match.matchStart());
        emit(result, op.name(), matchCount, out);
    }

    /** A value read as a kind through the casting table (design 17 §3.1); null when it has no such reading. */
    private static TypedValue castTo(final TypedValue value, final Kind kind) {
        return switch (kind) {
            case ANY, SEQUENCE -> value;
            case STRING -> Comparisons.cast(value, Cast.STRING);
            case NUMBER -> Comparisons.cast(value, Cast.NUMBER);
            case INTEGER -> {
                final Long whole = value.asInteger();
                yield whole == null ? null : new TypedValue.Integer(whole);
            }
            case BOOLEAN -> Comparisons.cast(value, Cast.BOOLEAN);
            case DATE -> Comparisons.cast(value, Cast.DATE);
        };
    }

    /**
     * Run a transform function and either write its result or bind it to a variable.
     *
     * <p>An input that resolves to nothing is dropped rather than passed along as an empty
     * string, so a function receiving two references and finding one absent sees one input, not
     * two of which one is blank. And a function returning nothing writes nothing — which is what
     * makes a join of no values disappear instead of leaving a stray separator.
     */
    private void transform(final CompiledOp.Transform op,
                           final MatchResult match,
                           final int matchCount,
                           final Output out) {
        final CompiledRef[] select = op.select();
        final VarName name = op.name();
        final Function<List<TypedValue>, TypedValue> function = op.function();
        final List<TypedValue> inputs = inputs(select, match, matchCount);
        if (op.numericKind() != null && strictValues && !warnedNumeric.contains(op)) {
            // A present value with no numeric reading — a missing field is normal and stays
            // quiet; a value that is there and is not a number is the evidence strict_values
            // exists to surface (design/17 §10).
            for (final TypedValue input : inputs) {
                if (input.asNumber() == null) {
                    warnedNumeric.add(op);
                    messages.add(new Message(Severity.WARNING,
                            "strict_values: a non-numeric value reached " + op.numericKind()
                            + ": [" + preview(input) + "]"));
                    break;
                }
            }
        }
        emit(function.apply(inputs), name, matchCount, out);
    }

    /** The selects an instruction reads, resolved; an absent one contributes nothing. */
    private List<TypedValue> inputs(final CompiledRef[] select,
                                    final MatchResult match,
                                    final int matchCount) {
        final List<TypedValue> inputs = new ArrayList<>(select.length);
        for (final CompiledRef ref : select) {
            final TypedValue resolved = CompiledRefs.resolveValue(ref, match, matchCount, vars);
            if (resolved != null) {
                inputs.add(resolved);
            }
        }
        return inputs;
    }

    /** A short, printable slice of an offending value for the strict_values message. */
    private static String preview(final TypedValue value) {
        final String text = value.asString();
        return text.length() > 40 ? text.substring(0, 40) + "…" : text;
    }

    /**
     * Write a produced value, or bind it to a variable if the instruction named one.
     *
     * <p>Naming a variable binds it, always. An instruction with nothing to say writes nothing
     * — the "empty is absent" rule — but it must still <i>bind</i> absence, because a name left
     * untouched is a name still holding the previous record's answer, and a reference with no
     * index takes the latest there is (E19; design/16's iteration reads values across records).
     * The clear is at the match index, which is how a capture that did not match already says
     * the same thing.
     */
    private void emit(final TypedValue value,
                      final VarName name,
                      final int matchCount,
                      final Output out) {
        if (name == null) {
            if (value != null) {
                out.write(value);
            }
        } else {
            // The typed value binds as itself — no re-encode, and the type survives to any
            // later typed read (design/17 §3.2).
            bind(name, matchCount, value);
        }
    }

    /**
     * Bind a name to one value, absence included: assign a scalar; put at this match's position
     * in a list. What a scalar bind means for a list is design 35 phase 4's question; until then
     * it is the match-indexed write a capture makes.
     */
    private void bind(final VarName name, final int matchCount, final TypedValue value) {
        if (vars.typeOf(name) == Declaration.Type.LIST) {
            // Match numbers are 1-based positions; the list is indexed from 0 (design 35 §5).
            vars.setAt(name, matchCount - 1, value);
        } else {
            vars.set(name, value);
        }
    }

    /**
     * Design/16 §10's chunked-root refusal, on the <b>write</b> rather than on every read.
     *
     * <p>{@code append} is the only instruction whose purpose is to make a value outlive the
     * record that produced it, so it is the accumulation, and refusing it refuses every
     * configuration that deliberately accumulates under a root that reads in pieces. Reads are
     * not guarded: a sequence bound and walked inside one record's body — a {@code tokenize}
     * and a walk over its pieces — crosses no record boundary and cannot be summarised wrongly.
     *
     * <p>What this deliberately does not cover: a list declared on a <b>root</b> template under
     * such a root, which lives for the chunk rather than the run (design 35 §4's sharp edge),
     * so a grouping over it presents a per-chunk answer. The declaration makes it visible to
     * the compiler, which could warn; nothing does yet, and it is named here rather than left
     * for someone to find.
     */
    private void guardAccumulation(final CompiledRef target) {
        if (chunkedRoot) {
            messages.add(new Message(Severity.FATAL, "Appends to '" + describe(target) + "' under "
                    + "a classify or any root: the input is read in pieces whose counters "
                    + "restart, so the accumulation would summarise only the last piece. Use "
                    + "an ordered root dispatch, or read the input whole."));
            throw new AbortRun();
        }
    }

    /**
     * The elements every collection in the run holds may not exceed {@code max_sequence_entries}
     * between them (design 35 §11: one run-wide live-element counter), and the stop when they do.
     * Judged after the write that grew a collection, and named for the collection that grew.
     */
    private void guardLive(final CompiledRef target) {
        final int limit = maxSequenceEntries;
        if (vars.live() > limit) {
            messages.add(new Message(Severity.FATAL, "Collection '" + describe(target) + "' exceeded "
                    + "max_sequence_entries (" + limit + "). A truncated aggregate is a wrong "
                    + "answer rather than a partial one, so the run stops here. Raise the "
                    + "limit if the accumulation is genuinely this large."));
            throw new AbortRun();
        }
    }

    /** The list a name holds, or null when it holds nothing — or a scalar, which no walk reads. */
    private TypedValue.List listOf(final VarName name) {
        return vars.get(name) instanceof final TypedValue.List list ? list : null;
    }

    /** The populated entries of a named list, in ascending position, or empty. */
    private List<TypedValue> entries(final VarName name) {
        final TypedValue.List list = listOf(name);
        if (list == null) {
            return List.of();
        }
        final List<TypedValue> values = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final TypedValue value = list.get(i);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /** Bind values as a dense list — position, with no holes. */
    private void bindDense(final VarName name, final List<TypedValue> values) {
        final TypedValue.List list = new TypedValue.List();
        for (final TypedValue value : values) {
            list.append(TypedValue.Collection.stored(value));
        }
        vars.adopt(name, list);
        guardLive(new CompiledRef.RemoteVar(name, null));
    }

    /** The list a mutation's target reaches, or null with a message when it reaches something else. */
    private TypedValue.List listTarget(final CompiledRef target,
                                       final MatchResult match,
                                       final int matchCount,
                                       final String what) {
        if (target instanceof final CompiledRef.RemoteVar remote && remote.matchIndex() == null
            && vars.typeOf(remote.varId()) == Declaration.Type.LIST) {
            // A declared list: the slot's own, made on first use.
            return vars.list(remote.varId());
        }
        final TypedValue reached = CompiledRefs.resolveValue(target, match, matchCount, vars);
        if (reached instanceof final TypedValue.List list) {
            return list;
        }
        if (reached == null) {
            // A nested get that found nothing: nothing to act on.
            return null;
        }
        fatal(what + " on something that is not a list: " + describe(target) + " is a "
              + (reached instanceof final TypedValue.Collection collection ? collection.kind() : "scalar"));
        return null;
    }

    /**
     * Put: at a position of a list, under a key of a map, into a set, or into a scalar
     * (design 35 §5). Which is the target's declared type when it is a bare name, or what is
     * actually there when it is reached through an accessor.
     */
    private void put(final CompiledOp.Put op, final MatchResult match, final int matchCount) {
        final TypedValue value = CompiledRefs.resolveValue(op.select(), match, matchCount, vars);
        final Declaration.Type declared = op.declared();
        if (declared == Declaration.Type.SCALAR) {
            vars.set(((CompiledRef.RemoteVar) op.target()).varId(), value);
            return;
        }
        if (declared == Declaration.Type.SET) {
            if (value instanceof final TypedValue.Collection member) {
                fatal("put into '" + describe(op.target()) + "' of a " + member.kind()
                      + ": a set member must be a scalar");
            }
            final TypedValue.Set set = vars.setOf(((CompiledRef.RemoteVar) op.target()).varId());
            if (value != null && !set.contains(value)) {
                set.add(value);
                vars.grew(1);
                guardLive(op.target());
            }
            return;
        }
        // A declared collection is the slot's own, made on its first put; anything else is
        // reached through an accessor and is whatever is there.
        final TypedValue target = op.target() instanceof final CompiledRef.RemoteVar remote
                && remote.matchIndex() == null
                ? declaredCollection(remote.varId())
                : CompiledRefs.resolveValue(op.target(), match, matchCount, vars);
        final TypedValue key = op.key() == null ? null : CompiledRefs.resolveValue(op.key(), match, matchCount, vars);
        switch (target) {
            case final TypedValue.List list -> {
                if (op.key() == null) {
                    fatal("put into a list needs a position");
                }
                final int position = position(op.key(), match, matchCount, "put");
                if (position < 1 || position > list.size()) {
                    fatal("put at position " + position + " of a list of " + list.size()
                          + ": positions run from 1 to the size; append adds one");
                }
                final TypedValue stored = TypedValue.Collection.stored(value);
                vars.grew(TypedValue.Collection.elementsOf(stored) -
                        TypedValue.Collection.elementsOf(list.get(position - 1)));
                list.put(position - 1, stored);
                guardLive(op.target());
            }
            case final TypedValue.Map map -> {
                if (op.key() == null) {
                    fatal("put into a map needs a key");
                }
                if (key == null) {
                    // A map's keys are values: an absent one could be stored but never read
                    // back, since get and contains answer absent for it (design 35 §5).
                    fatal("put into '" + describe(op.target()) + "' under an absent key: a map key must be present");
                }
                final TypedValue stored = TypedValue.Collection.stored(value);
                vars.grew((map.contains(key) ? 0 : 1) + TypedValue.Collection.elementsOf(stored)
                          - TypedValue.Collection.elementsOf(map.get(key)));
                if (key instanceof final TypedValue.Collection collectionKey) {
                    fatal("put into '" + describe(op.target()) + "' under a " + collectionKey.kind()
                          + " as the key: a map key must be a scalar");
                }
                map.put(key, stored);
                guardLive(op.target());
            }
            case final TypedValue.Set set -> {
                if (value instanceof final TypedValue.Collection member) {
                    fatal("put into '" + describe(op.target()) + "' of a " + member.kind()
                          + ": a set member must be a scalar");
                }
                if (value != null && !set.contains(value)) {
                    set.add(value);
                    vars.grew(1);
                    guardLive(op.target());
                }
            }
            case null -> {
            }
            default -> fatal("put into something that is not a collection: " + describe(op.target()));
        }
    }

    /** A declared collection, the slot's own, made on first use; null for a scalar. */
    private TypedValue declaredCollection(final VarName name) {
        return switch (vars.typeOf(name)) {
            case LIST -> vars.list(name);
            case MAP -> vars.map(name);
            case SET -> vars.setOf(name);
            case SCALAR -> null;
        };
    }

    /** A 1-based position, from whatever resolved; nothing readable as a number is position 0. */
    private int position(final CompiledRef ref, final MatchResult match, final int matchCount, final String what) {
        final TypedValue value = CompiledRefs.resolveValue(ref, match, matchCount, vars);
        final Long whole = value == null ? null : value.asInteger();
        if (whole == null) {
            fatal(what + ": the position " + (value == null ? "is absent" : "'" + value.asString()
                    + "' is not a whole number"));
        }
        return (int) (long) whole;
    }

    /** The target as an author would recognise it, for a message. */
    private static String describe(final CompiledRef target) {
        return switch (target) {
            case final CompiledRef.RemoteVar remote -> remote.varId().name();
            case final CompiledRef.Accessor accessor -> accessor.kind().spelling() + "(" + describe(accessor.of())
                    + ", …)";
            default -> target.getClass().getSimpleName();
        };
    }

    private void fatal(final String text) {
        messages.add(new Message(Severity.FATAL, text + ". The run stops here."));
        throw new AbortRun();
    }

    /**
     * File a list's entries by key, in order of first appearance — the index a grouping is
     * built on (design/16 §6). Keys resolve with {@code index()} bound, so a key can name a
     * parallel list: "these records, by their category" is said by indexing positions rather
     * than values.
     */
    private Map<String, Filed> file(final TypedValue.List store,
                                    final CompiledRef groupBy,
                                    final MatchResult match,
                                    final int matchCount) {
        final Map<String, Filed> members = new LinkedHashMap<>();
        vars.frames().pushIteration();
        for (int index = 0; index < store.size(); index++) {
            final TypedValue entry = store.get(index);
            if (entry == null) {
                continue;
            }
            vars.frames().index(index + 1);
            final TypedValue key = groupBy == null
                    ? entry
                    : CompiledRefs.resolveValue(groupBy, match, matchCount, vars);
            members.computeIfAbsent(key == null ? null : key.asString(),
                    ignored -> new Filed(key, new ArrayList<>())).members().add(index + 1);
        }
        vars.frames().popIteration();
        return members;
    }

    /**
     * One entry of an index: the key as it was read, and the 1-based positions filed under it.
     * The key is kept as a value rather than as its identity string because a grouping binds
     * it to {@code groupKey()}, where an author expects what they grouped on.
     */
    private record Filed(TypedValue key, List<Integer> members) {

    }

    /**
     * Group a list's entries and run the body once per group (design/16 §6).
     *
     * <p>Groups form in order of first appearance — a {@link java.util.LinkedHashMap} built in
     * one pass, which is the whole implementation. What is grouped is the <b>position set</b>:
     * members are 1-based positions, answered by {@code group()}, so a nested walk over them can
     * read any parallel list at the record each names. Keys are compared by string form, the
     * same total reading an uncast ordering uses.
     *
     * <p>{@code groupSize()} is known before the group's body opens, which is what lets an
     * author write a count into the opening tag — the {@code adjacent_groups} fixture's
     * trailing-empty-group case.
     */
    private void forEachGroup(final CompiledOp.ForEachGroup op,
                              final MatchResult match,
                              final int matchCount,
                              final byte[] content,
                              final Output out,
                              final long inputBase,
                              final boolean ignoreErrors,
                              final int depth) {
        if (!(CompiledRefs.resolveValue(op.select(), match, matchCount, vars) instanceof final TypedValue.List store)) {
            return;
        }
        final Map<String, Filed> members = file(store, op.groupBy(), match, matchCount);
        if (members.isEmpty()) {
            return;
        }

        // The key, the members and the size are the group frame's (design 30 phase 4; the
        // members since design 35 phase 5): group() reads them, and nothing is a name.
        vars.frames().pushGroup();
        for (final Filed group : members.values()) {
            final List<Integer> indices = group.members();
            final TypedValue.List positions = new TypedValue.List();
            for (final Integer index : indices) {
                positions.append(new TypedValue.Integer(index));
            }
            vars.frames().groupMembers(positions);
            vars.frames().groupKey(group.key());
            vars.frames().groupSize(indices.size());
            body(op.body(), match, matchCount, content, out,
                    inputBase, ignoreErrors, depth);
        }
        vars.frames().popGroup();
    }

    /** One entry of a walk: its 1-based index in the collection, its key for a map, its value. */
    private record Item(int index, TypedValue key, TypedValue value) {

    }

    /** What a walk runs over: a list's populated entries, a set's members, a map's entries, in order. */
    private static List<Item> items(final TypedValue collection) {
        final List<Item> items = new ArrayList<>();
        switch (collection) {
            case final TypedValue.List list -> {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) != null) {
                        items.add(new Item(i + 1, null, list.get(i)));
                    }
                }
            }
            case final TypedValue.Set set -> {
                final TypedValue.List members = set.values();
                for (int i = 0; i < members.size(); i++) {
                    items.add(new Item(i + 1, null, members.get(i)));
                }
            }
            case final TypedValue.Map map -> {
                final TypedValue.List keys = map.keys();
                for (int i = 0; i < keys.size(); i++) {
                    items.add(new Item(i + 1, keys.get(i), map.get(keys.get(i))));
                }
            }
            case null, default -> {
            }
        }
        return items;
    }

    /**
     * The entries in sorted order (design/16 §5) — <b>as positions, not as buffered
     * output</b>, which is the whole reason sorting is cheap here: the values are already in
     * memory, so ordering them reorders positions rather than deferring anything that has
     * been written.
     *
     * <p>Keys are evaluated once per entry, up front, with {@code index()} and the item
     * binding in scope so a key can read the item or a parallel list at the same match.
     * Evaluating per comparison instead would re-resolve a reference O(n log n) times.
     *
     * <p>The sort is <b>stable</b>, and the list it sorts is in ascending position, so ties
     * keep data order without an explicit tie-break — that is the same guarantee said once
     * rather than twice.
     */
    private List<Item> sorted(final CompiledOp.ForEach op,
                              final List<Item> items,
                              final MatchResult match,
                              final int matchCount) {
        final int keyCount = op.sort().length;
        final TypedValue[][] keys = new TypedValue[items.size()][keyCount];

        vars.push();
        declareBindings(op);
        // position() and last() are deliberately left alone here: this walk's frame binds only
        // the index, so they inherit an *enclosing* walk's position, which is a real value and
        // legitimately readable, as everywhere else in the scoping model. The compiler still
        // warns, because reading them here is far more likely to mean "this entry's position",
        // which is what does not exist.
        vars.frames().pushIteration();
        for (int i = 0; i < items.size(); i++) {
            final Item item = items.get(i);
            vars.frames().index(item.index());
            bindItem(op, item);
            for (int k = 0; k < keyCount; k++) {
                final CompiledOp.SortKey key = op.sort()[k];
                final TypedValue raw = CompiledRefs.resolveValue(
                        key.by(), match, matchCount, vars);
                // Uncast, an ordering compares string forms — the one total reading.
                keys[i][k] = Comparisons.cast(raw, key.as() == null ? Cast.STRING : key.as());
            }
        }
        vars.frames().popIteration();
        vars.pop();

        final List<Integer> order = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            order.add(i);
        }
        order.sort((left, right) -> {
            for (int k = 0; k < keyCount; k++) {
                final int comparison = compareKeys(keys[left][k], keys[right][k], op.sort()[k].order());
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        });
        return order.stream().map(items::get).toList();
    }

    private void declareBindings(final CompiledOp.ForEach op) {
        if (op.as() != null) {
            vars.declare(op.as());
        }
        if (op.asKey() != null) {
            vars.declare(op.asKey());
        }
    }

    private void bindItem(final CompiledOp.ForEach op, final Item item) {
        if (op.as() != null) {
            vars.set(op.as(), item.value());
        }
        if (op.asKey() != null) {
            vars.set(op.asKey(), item.key());
        }
    }

    /**
     * One key's comparison. <b>Absent sorts last in either direction</b> — the unparseable
     * entries end up together at the bottom rather than migrating to the top when the order
     * flips, which is what a reader would read as data (design/16 §5).
     */
    private static int compareKeys(final TypedValue left,
                                   final TypedValue right,
                                   final OutputNode.Order order) {
        if (left == null || right == null) {
            return left == right ? 0 : (left == null ? 1 : -1);
        }
        final Integer comparison = Comparisons.compare(left, right);
        if (comparison == null) {
            return 0;
        }
        return order == OutputNode.Order.DESCENDING ? -comparison : comparison;
    }

    /**
     * Walk a collection, running the body once per entry (design/16 §4, design 35 §5): a
     * list's populated entries in ascending position, a set's members, a map's entries with
     * the key bound too.
     *
     * <p>Index and position are bound separately because they answer different questions —
     * the index reaches sibling data at the same match, the position is what
     * {@code position()} means and follows the ordering.
     *
     * <p>The iteration runs in its own scope, so the bindings do not outlive it and a nested
     * {@code for-each} shadows rather than overwrites the one around it. The entries are
     * taken before the body runs, so a body that appends to what it walks terminates.
     */
    private void forEach(final CompiledOp.ForEach op,
                         final MatchResult match,
                         final int matchCount,
                         final byte[] content,
                         final Output out,
                         final long inputBase,
                         final boolean ignoreErrors,
                         final int depth) {
        final List<Item> items = items(CompiledRefs.resolveValue(op.select(), match, matchCount, vars));
        if (items.isEmpty()) {
            return;
        }
        final List<Item> order = op.sort().length == 0
                ? items
                : sorted(op, items, match, matchCount);

        vars.push();
        declareBindings(op);
        vars.frames().pushIteration();
        // Known before the first body runs, which is what makes a last-entry test cheap and
        // correct (the adjacent_groups fixture's trailing-empty-group case).
        vars.frames().last(order.size());
        for (int position = 0; position < order.size(); position++) {
            // Position follows the ordering; the index still points at the record, so a key
            // that reordered the walk does not disturb what a body reads (design/16 §5).
            final Item item = order.get(position);
            vars.frames().index(item.index());
            vars.frames().position(position + 1L);
            bindItem(op, item);
            body(op.body(), match, matchCount, content, out,
                    inputBase, ignoreErrors, depth);
        }
        vars.frames().popIteration();
        vars.pop();
    }

    /**
     * A structural call, with the sink's refusal turned into the run's last message. The sink
     * knows the rule (an attribute after content, a close with nothing open); the body knows
     * which instruction broke it, and a fatal is where a misshapen document stops rather than a
     * half-written one continuing.
     */
    // The instruction is named in two pieces so that the message is built only when the
    // structure is actually refused. This runs on every element, attribute and namespace a body
    // writes, and the refusal is the case that does not happen.
    void structure(final Runnable call, final String kind, final String name) {
        try {
            call.run();
        } catch (final OutputSink.StructureException e) {
            messages.add(new Message(Severity.FATAL,
                    "Output structure at " + kind + " '" + name + "': " + e.getMessage()));
            throw new AbortRun();
        }
    }

    /**
     * Bind a variable to what a nested body produces.
     *
     * <p>The body runs in its own scope and into its own buffer, and what comes out depends on
     * what it did. If it captured into the variable's name — which is what happens when the body
     * dispatches to templates that capture — those captures are promoted whole, keeping their
     * per-match structure so that a later reference can still ask for the third one. Otherwise
     * the text it wrote becomes the value. If it did neither, the variable is cleared rather
     * than left holding the previous record's value.
     */
    private void variable(final CompiledOp.Variable value,
                          final MatchResult match,
                          final int matchCount,
                          final byte[] content,
                          final long inputBase,
                          final boolean ignoreErrors,
                          final int depth) {
        // The name is declared over its own body, so the computation cannot read a half-built
        // value — or the outer value it is about to replace (design 35 §4).
        vars.push();
        vars.declare(value.name());

        // A variable is a value, not a document: its text is the bytes its body wrote, with no
        // serialiser's newlines or indent inside it (E41).
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        body(value.body(), match, matchCount, content,
                Output.of(new XmlByteSink(buffer, XmlByteSink.Layout.FAITHFUL)), inputBase,
                ignoreErrors,
                depth);

        TypedValue captured = vars.fromCurrentScope(value.name());
        if (captured instanceof final TypedValue.List list && entriesOf(list) == 0) {
            captured = null;
        }
        vars.pop();

        if (captured != null) {
            // Promoted whole, per-match structure kept, so a later reference can still ask for
            // the third one. The scope that held it has gone, so nothing else refers to it.
            vars.set(value.name(), captured);
        } else if (buffer.size() > 0) {
            bind(value.name(), matchCount, TypedValue.utf8(buffer.toByteArray()));
        } else {
            bind(value.name(), matchCount, null);
        }
    }

    /** How many of a list's positions hold a value. */
    private static int entriesOf(final TypedValue.List list) {
        int populated = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) != null) {
                populated++;
            }
        }
        return populated;
    }


    /**
     * Invoke a template by name, with parameters and without matching anything.
     *
     * <p>Parameters live in their own scope, so a call cannot leave its arguments behind for the
     * next one. Declared parameters the caller did not supply take their defaults.
     */
    private void callTemplate(final CompiledOp.CallTemplate value,
                              final MatchResult match,
                              final int matchCount,
                              final byte[] content,
                              final Output out,
                              final long inputBase,
                              final boolean ignoreErrors,
                              final int depth) {
        final CompiledTemplate target = value.target();
        if (target == null) {
            return;
        }

        // Arguments resolve in the caller's scope — an argument may legitimately read the very
        // variable its parameter will shadow — and only then does the call's own scope open.
        final List<byte[]> resolved = new ArrayList<>(value.args().length);
        for (final CompiledOp.Arg arg : value.args()) {
            resolved.add(CompiledRefs.resolve(arg.value(), match, matchCount, vars));
        }

        // The parameters are declarations scoped to the callee's body (design 35 §4).
        vars.push();
        for (int i = 0; i < value.args().length; i++) {
            final CompiledOp.Arg arg = value.args()[i];
            vars.declare(arg.name());
            if (resolved.get(i) != null) {
                vars.set(arg.name(), TypedValue.utf8(resolved.get(i)));
            }
        }
        // Which parameters this site leaves unsupplied, and their defaults encoded, were
        // settled when the call was linked to its target.
        for (final CompiledOp.Param declared : value.params()) {
            vars.declare(declared.name());
            if (declared.defaultValue() != null) {
                vars.set(declared.name(), declared.defaultValue());
            }
        }
        body(target.body(), match, matchCount, content, out, inputBase, ignoreErrors, depth);
        vars.pop();
    }

    /**
     * Hand some content to another set of templates.
     *
     * <p>Which content is a reference, and it is usually a group of the match just made — that is
     * how a row is broken into fields, and a field into parts. The templates that get it are the
     * level of the named mode, dispatched as ordered choice, with the directive's
     * {@code ignoreErrors} as the level's reporting gate.
     */
    private void apply(final CompiledOp.Apply op,
                       final MatchResult match,
                       final int matchCount,
                       final byte[] parentContent,
                       final Output out,
                       final long parentBase,
                       final boolean inheritedIgnoreErrors,
                       final int depth) {
        final ApplyDirective directive = op.directive();
        if (depth >= directive.maxDepth()) {
            return;
        }

        // "Group 0" means the content this template is working on, which is not always group 0
        // of its match: a delimiter template's content is the field, and its group 0 carries the
        // delimiter too. Resolving group 0 here would hand the trailing separator down to the
        // child templates, which is what turns a CSV header's last column name into "what\n".
        // So the content the parent already selected is passed straight through — and whether
        // that is what the select means was decided at compile time.
        final byte[] content = op.wholeParentContent()
                ? parentContent
                : CompiledRefs.resolve(op.select(), match, matchCount, vars);
        if (content == null || content.length == 0) {
            return;
        }

        // Content taken straight from the parent, or from one of its groups, is still part of
        // the input and can be pointed at. Content built from a variable cannot be.
        final long childBase = op.locatable() ? parentBase : Instrument.UNLOCATABLE;
        if (childBase == Instrument.UNLOCATABLE) {
            instrument.onMatchContent(null, content);
        }

        // A compile-time fact read as a field: the op holds the templates its mode answers to,
        // bound when the project finished compiling (design 29 §3.2).
        final CompiledTemplate[] candidates = op.candidates();

        // A recursive apply needs no scope of its own: a level that declares its names
        // declares them again on re-entry and restores them on exit, which keeps each level's
        // own exactly where the coarse shadow over every candidate's captures used to
        // (design 35 §4).

        // DS3 inherits ignoreErrors down the tree: a level inside an ignoring container is
        // gated even when its own directive says nothing.
        level.dispatch(candidates, content, 0, content.length, out, childBase,
                inheritedIgnoreErrors || directive.ignoreErrors(), depth + 1, op.dispatch(), encoding);
    }
}
