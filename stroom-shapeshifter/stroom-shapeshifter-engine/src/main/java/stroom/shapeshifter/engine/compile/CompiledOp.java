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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;
import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Signature;
import stroom.shapeshifter.engine.match.PatternKey;
import stroom.shapeshifter.engine.value.Comparisons;
import stroom.shapeshifter.engine.value.Dates;
import stroom.shapeshifter.engine.value.Transforms;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.regex.BytePattern;

import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * One instruction of a compiled body.
 *
 * <p>The authored {@link OutputNode} says what to do; this is the same instruction with every
 * decision that does not depend on a match already taken (D35). Literal text is bytes. A
 * reference is a {@link CompiledRef} that knows its strategy. A regex replace holds its
 * {@link BytePattern} instead of the text to look one up by. An apply knows whether its select
 * is the parent's whole content. The transform instructions collapse to one instruction
 * holding its function, parameters already bound.
 *
 * <p>Conditions stay authored and are evaluated by {@code Conditions}: their patterns are
 * interned here, and compiling them further is design 10 §2's open row (design 27 §2.7).
 */
public sealed interface CompiledOp {

    /** Write literal bytes, encoded once. */
    record Text(byte[] bytes) implements CompiledOp {

    }

    /** Write the value of a reference. */
    record ValueOf(CompiledRef ref) implements CompiledOp {

    }

    /** Run a body if a condition holds. */
    record If(Condition test, List<CompiledOp> then) implements CompiledOp {

    }

    /** Run the first branch whose condition holds. */
    record Choose(List<When> when, List<CompiledOp> otherwise) implements CompiledOp {

    }

    /** One branch of a {@link Choose}. */
    record When(Condition test, List<CompiledOp> body) {

    }

    /** Run the branch whose value matches. */
    record Switch(CompiledRef select, List<Case> cases, List<CompiledOp> defaultBody) implements CompiledOp {

    }

    /** One case of a {@link Switch}. */
    record Case(String value, List<CompiledOp> body) {

    }

    /**
     * Match templates against some content.
     *
     * @param directive          the authored directive — mode, limits, gates
     * @param select             the content reference, compiled
     * @param wholeParentContent whether the select means "the content this template is working
     *                           on", which is passed straight through rather than re-resolved
     * @param locatable          whether the dispatched content is still part of the input, and
     *                           can therefore be pointed at
     * @param dispatch           how the dispatched level runs — the directive's word, the
     *                           source default, or the version default, resolved once (D36)
     */
    record Apply(ApplyDirective directive,
                 CompiledRef select,
                 boolean wholeParentContent,
                 boolean locatable,
                 Dispatch dispatch) implements CompiledOp {

    }

    /** Emit a message into the run's stream; {@code FATAL} aborts the run (D36). */
    record EmitError(Severity severity, CompiledRef message) implements CompiledOp {

    }

    /** Invoke a template by name. The target is a field read at run time, not a search. */
    record CallTemplate(String name, List<Arg> args) implements CompiledOp {

    }

    /** One argument of a {@link Call}. */
    record Arg(String name, CompiledRef value) {

    }

    /** Bind a variable to what a nested body writes. */
    record Variable(String name, List<CompiledOp> body) implements CompiledOp {

    }

    /** Design 20's structural instructions, bracketing their bodies with the sink's calls. */
    record Element(String name, String namespace, boolean omitIfEmpty, List<CompiledOp> body)
            implements CompiledOp {

    }

    /** An attribute on the enclosing element, its value the body's text; omitted if empty when asked. */
    record Attribute(String name, boolean omitIfEmpty, List<CompiledOp> body) implements CompiledOp {

    }

    /** A namespace declaration on the enclosing element; a null prefix declares the default. */
    record Namespace(String prefix, String uri) implements CompiledOp {

    }

    /** Map a value through a lookup table. */
    record ValueMap(CompiledRef select,
                    List<OutputNode.Entry> entries,
                    String defaultValue,
                    String name) implements CompiledOp {

    }

    /**
     * A call to a registered function (design 26): {@code select.get(i)} is the reference at
     * position {@code i}, or null where {@code sequences.get(i)} names the store whose entries
     * that position receives.
     */
    record CallFunction(FunctionDefinition definition,
                        List<CompiledRef> select,
                        List<String> sequences,
                        String name) implements CompiledOp {

    }

    /**
     * Run a transform function over resolved inputs — every {@code translate}, {@code replace},
     * {@code substring} and the rest, as one instruction with its parameters already closed
     * over. What kind it was matters at authoring time; at run time there is only "resolve the
     * selects, apply the function, write or bind the result".
     *
     * @param numericKind the instruction's name when it is arithmetic — the hook for the
     *                    {@code strict_values} diagnostic (design/17 §10) — or null
     */
    record Transform(List<CompiledRef> select,
                     String name,
                     Function<List<TypedValue>, TypedValue> function,
                     String numericKind) implements CompiledOp {

    }

    /**
     * Parse text into an instant (design/17 §9). Its own instruction rather than a
     * {@link Transform}, because the reference date resolves against the match at run time —
     * threading it through the select list would let an absent input shift positions, and a
     * reference read as an input is a wrong date that looks right.
     *
     * @param reference the reference date's ref, or null; required at compile time when the
     *                  pattern has no year
     */
    record ParseDate(CompiledRef select,
                     CompiledRef reference,
                     Dates.Parser parser,
                     String name) implements CompiledOp {

    }

    /** Declare a sequence and empty it (design/16 §9). */
    record Sequence(String name) implements CompiledOp {

    }

    /** Add a value to a declared sequence, at its next free index. */
    record Append(String name, CompiledRef select) implements CompiledOp {

    }

    /** Walk a sequence, running a body per populated entry (design/16 §4). */
    record ForEach(String select,
                   String as,
                   List<SortKey> sort,
                   List<CompiledOp> body) implements CompiledOp {

    }

    /** Group a sequence's entries, running a body per group (design/16 §6). */
    record ForEachGroup(String select,
                        CompiledRef groupBy,
                        List<CompiledOp> body) implements CompiledOp {

    }

    /** Build a random-access index over a sequence (design/16 §8). */
    record Key(String name, String select, CompiledRef groupBy) implements CompiledOp {

    }

    /** Look one value up in a key, binding the entries it names. */
    record KeyGet(String key, CompiledRef select, String name) implements CompiledOp {

    }

    /** One compiled ordering key: the reference resolved once, the cast decided once. */
    record SortKey(CompiledRef by, OutputNode.Order order, Cast as) {

    }

    /** What a {@link Fold} does. The authored vocabulary is five instructions; this is one. */
    enum FoldKind {
        COUNT, SUM, AVG, MIN, MAX
    }

    /**
     * Fold a sequence to one value (design/16 §8). Five authored instructions collapse here
     * the way the transforms collapse to {@link Transform}: what kind it was matters at
     * authoring time, and at run time there is only "read the sequence, fold it, write or
     * bind the result".
     */
    record Fold(String select, FoldKind kind, Cast as, String name) implements CompiledOp {

    }

    /** The distinct entries of a sequence, bound as a dense one. */
    record DistinctValues(String select, String name) implements CompiledOp {

    }

    /**
     * Split a value. Its own instruction rather than a {@link Transform} because binding a
     * name now means binding <b>N</b> values, which a transform's single result cannot do —
     * design/17 §16.4's ruling.
     */
    record Tokenize(CompiledRef select, String delimiter, String name) implements CompiledOp {

    }

    /**
     * Compile a body.
     *
     * @param patterns the project's interned patterns, already collected — a regex replace
     *                 resolves its {@link BytePattern} here, once
     */
    static List<CompiledOp> compile(final List<OutputNode> body,
                                    final Map<PatternKey, BytePattern> patterns,
                                    final Project project,
                                    final Functions functions) {
        final List<CompiledOp> ops = new ArrayList<>(body.size());
        for (final OutputNode node : body) {
            final CompiledOp op = switch (node) {
                case OutputNode.Text text ->
                        new Text(text.value().getBytes(StandardCharsets.UTF_8));
                case OutputNode.ValueOf valueOf -> new ValueOf(CompiledRef.of(valueOf.select()));
                case OutputNode.Call value -> call(value, functions);
                case OutputNode.If value ->
                        new If(value.test(), compile(value.then(), patterns, project, functions));
                case OutputNode.Choose value -> new Choose(
                        value.when().stream()
                                .map(branch -> new When(branch.test(),
                                        compile(branch.body(), patterns, project, functions)))
                                .toList(),
                        compile(value.otherwise(), patterns, project, functions));
                case OutputNode.Switch value -> new Switch(
                        CompiledRef.of(value.select()),
                        value.cases().stream()
                                .map(c -> new Case(c.value(),
                                        compile(c.body(), patterns, project, functions)))
                                .toList(),
                        compile(value.defaultBody(), patterns, project, functions));
                case OutputNode.ApplyTemplates apply -> {
                    // Whole-parent-content is the group-0 special case of a local group, so
                    // being a local group is the whole of being locatable.
                    final RefExpression select = apply.directive().select();
                    yield new Apply(
                            apply.directive(),
                            CompiledRef.of(select),
                            isWholeParentContent(select),
                            isLocalGroup(select),
                            Dispatch.effective(apply.directive().dispatch(), project));
                }
                case OutputNode.EmitError value ->
                        new EmitError(value.severity(), CompiledRef.of(value.message()));
                case OutputNode.CallTemplate value -> new CallTemplate(
                        value.name(),
                        value.withParam().stream()
                                .map(param -> new Arg(param.name(), CompiledRef.of(param.value())))
                                .toList());
                case OutputNode.Variable value ->
                        new Variable(value.name(), compile(value.body(), patterns, project, functions));
                case OutputNode.Element value -> new Element(
                        value.name(), value.namespace(), value.omitIfEmpty(),
                        compile(value.body(), patterns, project, functions));
                case OutputNode.Attribute value -> new Attribute(
                        value.name(), value.omitIfEmpty(),
                                compile(value.body(), patterns, project, functions));
                case OutputNode.Namespace value -> new Namespace(value.prefix(), value.uri());
                case OutputNode.ValueMap value -> new ValueMap(
                        CompiledRef.of(value.select()), value.entries(), value.defaultValue(), value.name());
                case OutputNode.Translate value -> transform(single("translate", value.select()),
                        value.name(), inputs -> Transforms.translate(inputs, value.from(), value.to()));
                case OutputNode.StringJoin value -> transform(value.select(), value.name(),
                        inputs -> Transforms.stringJoin(inputs, value.separator()));
                case OutputNode.Replace value -> replace(value, patterns);
                case OutputNode.LowerCase value ->
                        transform(single("lower-case", value.select()), value.name(), Transforms::lowerCase);
                case OutputNode.UpperCase value ->
                        transform(single("upper-case", value.select()), value.name(), Transforms::upperCase);
                case OutputNode.NormalizeSpace value -> transform(
                        single("normalize-space", value.select()), value.name(), Transforms::normalizeSpace);
                case OutputNode.Trim value ->
                        transform(single("trim", value.select()), value.name(), Transforms::trim);
                case OutputNode.Substring value -> {
                    // The version gate (design/17 §7, ruled): 1-based from version 5,
                    // 0-based before — Dispatch.effective's precedent, applied to the base.
                    // A version-5 start below 1 follows XPath's rule: the window is
                    // [start, start + length) intersected with the string, so the length
                    // shrinks by the part that fell before position 1 —
                    // substring(x, 0, 3) is the first two characters, not three. An
                    // omitted start is "from the beginning" under either base.
                    int start = value.start() == null ? 0 : value.start();
                    Integer length = value.length();
                    if (project.version() >= 5 && value.start() != null) {
                        start = start - 1;
                        if (start < 0 && length != null) {
                            length = Math.max(0, length + start);
                        }
                        start = Math.max(0, start);
                    }
                    final int effectiveStart = start;
                    final Integer effectiveLength = length;
                    yield transform(single("substring", value.select()), value.name(),
                            inputs -> Transforms.substring(inputs, effectiveStart, effectiveLength));
                }
                case OutputNode.Tokenize value -> new Tokenize(
                        CompiledRef.of(single("tokenize", value.select()).getFirst()),
                        value.delimiter(), value.name());
                case OutputNode.Number value ->
                        transform(single("number", value.select()), value.name(), Transforms::number);
                case OutputNode.Add value ->
                        arithmetic("add", value.select(), value.name(), Arity.AT_LEAST, 1, Transforms::add);
                case OutputNode.Subtract value ->
                        arithmetic("subtract", value.select(), value.name(), Arity.EXACTLY, 2, Transforms::subtract);
                case OutputNode.Multiply value ->
                        arithmetic("multiply", value.select(), value.name(), Arity.AT_LEAST, 1, Transforms::multiply);
                case OutputNode.Divide value ->
                        arithmetic("divide", value.select(), value.name(), Arity.EXACTLY, 2, Transforms::divide);
                case OutputNode.Mod value ->
                        arithmetic("mod", value.select(), value.name(), Arity.EXACTLY, 2, Transforms::mod);
                case OutputNode.Round value ->
                        arithmetic("round", value.select(), value.name(), Arity.EXACTLY, 1, Transforms::round);
                case OutputNode.Floor value ->
                        arithmetic("floor", value.select(), value.name(), Arity.EXACTLY, 1, Transforms::floor);
                case OutputNode.Ceiling value ->
                        arithmetic("ceiling", value.select(), value.name(), Arity.EXACTLY, 1, Transforms::ceiling);
                case OutputNode.Abs value ->
                        arithmetic("abs", value.select(), value.name(), Arity.EXACTLY, 1, Transforms::abs);
                case OutputNode.StringLength value ->
                        transform(single("string-length", value.select()), value.name(),
                                Transforms::stringLength);
                case OutputNode.SubstringBefore value ->
                        transform(single("substring-before", value.select()), value.name(),
                                inputs -> Transforms.substringBefore(inputs, value.marker()));
                case OutputNode.SubstringAfter value ->
                        transform(single("substring-after", value.select()), value.name(),
                                inputs -> Transforms.substringAfter(inputs, value.marker()));
                case OutputNode.StartsWith value ->
                        transform(single("starts-with", value.select()), value.name(),
                                inputs -> Transforms.startsWith(inputs, value.prefix()));
                case OutputNode.EndsWith value ->
                        transform(single("ends-with", value.select()), value.name(),
                                inputs -> Transforms.endsWith(inputs, value.suffix()));
                case OutputNode.Contains value ->
                        transform(single("contains", value.select()), value.name(),
                                inputs -> Transforms.contains(inputs, value.substring()));
                case OutputNode.FormatNumber value -> formatNumber(value);
                case OutputNode.ParseDate value -> {
                    final Dates.Parser parser = Dates.compileParser(
                            value.pattern(), value.timezone(), value.reference() != null, "parse-date");
                    yield new ParseDate(
                            CompiledRef.of(single("parse-date", value.select()).getFirst()),
                            value.reference() == null ? null : CompiledRef.of(value.reference()),
                            parser,
                            value.name());
                }
                case OutputNode.Count value -> new Fold(value.select(), FoldKind.COUNT, null, value.name());
                case OutputNode.Sum value -> new Fold(value.select(), FoldKind.SUM, null, value.name());
                case OutputNode.Avg value -> new Fold(value.select(), FoldKind.AVG, null, value.name());
                case OutputNode.Min value -> new Fold(value.select(), FoldKind.MIN, value.as(), value.name());
                case OutputNode.Max value -> new Fold(value.select(), FoldKind.MAX, value.as(), value.name());
                case OutputNode.DistinctValues value ->
                        new DistinctValues(value.select(), value.name());
                case OutputNode.Sequence value -> new Sequence(value.name());
                case OutputNode.Append value -> new Append(value.name(), CompiledRef.of(value.select()));
                case OutputNode.Key value -> new Key(value.name(), value.select(),
                        value.groupBy() == null ? null : CompiledRef.of(value.groupBy()));
                case OutputNode.KeyGet value -> new KeyGet(value.key(),
                        CompiledRef.of(value.select()), value.name());
                case OutputNode.ForEachGroup value -> new ForEachGroup(value.select(),
                        value.groupBy() == null ? null : CompiledRef.of(value.groupBy()),
                        compile(value.body(), patterns, project, functions));
                case OutputNode.ForEach value -> new ForEach(value.select(), value.as(),
                        value.sort().stream()
                                .map(key -> new SortKey(CompiledRef.of(key.by()), key.order(), key.as()))
                                .toList(),
                        compile(value.body(), patterns, project, functions));
                case OutputNode.FormatDate value -> {
                    final Dates.Formatter formatter = Dates.compileFormatter(
                            value.pattern(), value.timezone(), "format-date");
                    yield transform(single("format-date", value.select()), value.name(),
                            inputs -> {
                                if (inputs.isEmpty()) {
                                    return null;
                                }
                                // The input is an Instant or anything the date cast reads —
                                // ISO bytes pass straight through (design/17 §9.1).
                                final TypedValue instant = Comparisons.cast(inputs.getFirst(), Cast.DATE);
                                return instant == null
                                        ? null
                                        : Dates.format(formatter, (TypedValue.Instant) instant);
                            });
                }
            };
            ops.add(op);
        }
        return List.copyOf(ops);
    }

    /**
     * A call to a registered function (design 26 §3): the name resolved now, by name; the
     * arity checked now against the signature; a {@code SEQUENCE} position required to name a
     * store, since that is what it receives. The definition is remembered so the run can bind it.
     */
    private static CallFunction call(final OutputNode.Call value, final Functions functions) {
        final FunctionDefinition definition = functions.resolve(value.function());
        final Signature signature = definition.signature();
        final int written = value.select().size();
        if (written < signature.minArgs() || written > signature.maxArgs()) {
            throw new ConfigException("Function '" + value.function() + "' takes "
                                      + (signature.minArgs() == signature.maxArgs()
                    ? "exactly " + signature.maxArgs()
                    : signature.minArgs() + " to " + signature.maxArgs())
                                      + (signature.maxArgs() == 1 ? " argument" : " arguments")
                                      + ", but the call has " + written);
        }
        final List<CompiledRef> select = new ArrayList<>(written);
        final List<String> sequences = new ArrayList<>(written);
        for (int i = 0; i < written; i++) {
            final RefExpression ref = value.select().get(i);
            if (signature.argKinds().get(i) == Kind.SEQUENCE) {
                final String store = ref.parts().size() == 1
                                     && ref.parts().getFirst() instanceof RefPart.Capture capture
                                     && capture.varId() != null
                        ? capture.varId()
                        : null;
                if (store == null) {
                    throw new ConfigException("Function '" + value.function() + "': argument " + (i + 1)
                                              + " is a sequence and must name a variable,"
                                              + " whose every entry it receives");
                }
                sequences.add(store);
                select.add(null);
            } else {
                sequences.add(null);
                select.add(CompiledRef.of(ref));
            }
        }
        return new CallFunction(definition, select, sequences, value.name());
    }

    private static Transform transform(final List<RefExpression> select,
                                       final String name,
                                       final Function<List<TypedValue>, TypedValue> function) {
        return new Transform(select.stream().map(CompiledRef::of).toList(), name, function, null);
    }

    /**
     * An arithmetic instruction: arity checked at compile time against the shape the
     * operation has, and again at run time against what actually resolved — an absent input
     * shrinks the resolved list, and §5's rule is that any absent input makes the whole
     * result absent, so a short list is an answer, not an error.
     *
     * @param arity the required select count, exactly or at least: {@code add} and
     *              {@code multiply} fold, and take {@code count} as a minimum
     */
    private static Transform arithmetic(final String what,
                                        final List<RefExpression> select,
                                        final String name,
                                        final Arity arity,
                                        final int count,
                                        final Function<List<TypedValue>, TypedValue> function) {
        if (arity == Arity.AT_LEAST ? select.size() < count : select.size() != count) {
            throw new ConfigException("A " + what + " takes "
                                      + (arity == Arity.AT_LEAST ? "at least " : "exactly ") + count
                                      + (count == 1 ? " select" : " selects")
                                      + ", but has " + select.size());
        }
        final int expected = select.size();
        return new Transform(select.stream().map(CompiledRef::of).toList(), name,
                inputs -> inputs.size() == expected ? function.apply(inputs) : null, what);
    }

    /**
     * How an arithmetic instruction's select count is checked. Public only because an interface
     * has no other visibility; nothing outside the compile reads it.
     */
    enum Arity { EXACTLY, AT_LEAST }

    /** A format-number closes over its picture, compiled once and refused at compile time. */
    private static Transform formatNumber(final OutputNode.FormatNumber value) {
        final DecimalFormat format;
        try {
            format = new DecimalFormat(value.picture(), DecimalFormatSymbols.getInstance(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new ConfigException("A format-number picture will not compile: "
                                      + value.picture() + " (" + e.getMessage() + ")");
        }
        return transform(single("format-number", value.select()), value.name(),
                inputs -> Transforms.formatNumber(inputs, format));
    }

    /**
     * Require exactly one select on a one-input transform. Only {@code string-join} folds a
     * list; every other transform reads its first input, so a second one is an authoring
     * mistake that would otherwise run and silently drop data.
     *
     * <p>And so is <b>none</b>. An instruction with nothing to read produces nothing for ever —
     * the same "reads absent for ever" hazard the unknown-reference refusal exists to catch —
     * and refusing arity here says which instruction and what is wrong, for all of them at once,
     * where an instruction taking its one select by {@code getFirst()} would fail without a name.
     */
    private static List<RefExpression> single(final String what, final List<RefExpression> select) {
        if (select.size() != 1) {
            throw new ConfigException("A " + what + " takes exactly one select, but has "
                                      + select.size()
                                      + (select.isEmpty()
                                              ? ": with nothing to read it would produce nothing"
                                              : ": only the first select would be read"));
        }
        return select;
    }

    /** A regex replace closes over its compiled pattern; a literal one over its text. */
    private static Transform replace(final OutputNode.Replace value,
                                     final Map<PatternKey, BytePattern> patterns) {
        single("replace", value.select());
        if (!value.isRegex()) {
            return transform(value.select(), value.name(),
                    inputs -> Transforms.replaceLiteral(inputs, value.pattern(), value.replacement()));
        }
        final BytePattern pattern = patterns.get(PatternKey.ofValue(value.pattern()));
        if (pattern == null) {
            throw new IllegalStateException("Pattern was not compiled: " + value.pattern());
        }
        return transform(value.select(), value.name(),
                inputs -> inputs.isEmpty()
                        ? null
                        : TypedValue.of(Transforms.replaceRegex(
                                pattern, inputs.getFirst().asString(), value.replacement())));
    }

    /** True if an expression is exactly "group 0 of this match, whichever one that is". */
    private static boolean isWholeParentContent(final RefExpression expression) {
        return expression.parts().size() == 1
               && expression.parts().getFirst() instanceof RefPart.Capture capture
               && capture.varId() == null
               && capture.group() == 0
               && capture.matchIndex() == null;
    }

    /** True if an expression is one group of this match, wherever that group came from. */
    private static boolean isLocalGroup(final RefExpression expression) {
        return expression.parts().size() == 1
               && expression.parts().getFirst() instanceof RefPart.Capture capture
               && capture.varId() == null
               && capture.matchIndex() == null;
    }
}
