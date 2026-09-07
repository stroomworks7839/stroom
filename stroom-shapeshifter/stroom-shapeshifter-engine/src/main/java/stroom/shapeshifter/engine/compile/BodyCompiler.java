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

import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.OutputNode;
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

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The compiler of bodies: every authored instruction to its compiled form, against the compile
 * context — the interned patterns a regex replace resolves against, the project whose version
 * gates decide, and the functions a call resolves in. One instance per compile; the vocabulary
 * it produces is {@link CompiledOp}'s, kept there beside the run that reads it (design 27 §2.3).
 */
final class BodyCompiler {

    private final Map<PatternKey, BytePattern> patterns;
    private final Project project;
    private final Functions functions;

    /**
     * @param patterns the project's interned patterns, already collected — a regex replace
     *                 resolves its {@link BytePattern} here, once
     */
    BodyCompiler(final Map<PatternKey, BytePattern> patterns,
                 final Project project,
                 final Functions functions) {
        this.patterns = patterns;
        this.project = project;
        this.functions = functions;
    }

    /** How an arithmetic instruction's select count is checked. */
    private enum Arity { EXACTLY, AT_LEAST }

    /** Compile a body: one arm per instruction, so the method is as long as the vocabulary. */
    List<CompiledOp> compile(final List<OutputNode> body) {
        final List<CompiledOp> ops = new ArrayList<>(body.size());
        for (final OutputNode node : body) {
            final CompiledOp op = switch (node) {
                case OutputNode.Text text ->
                        new CompiledOp.Text(TypedValue.of(text.value()));
                case OutputNode.ValueOf valueOf ->
                        new CompiledOp.ValueOf(CompiledRef.of(valueOf.select()));
                case OutputNode.Call value -> call(value);
                case OutputNode.If value ->
                        new CompiledOp.If(value.test(), compile(value.then()));
                case OutputNode.Choose value -> new CompiledOp.Choose(
                        value.when().stream()
                                .map(branch -> new CompiledOp.When(branch.test(),
                                        compile(branch.body())))
                                .toList(),
                        compile(value.otherwise()));
                case OutputNode.Switch value -> new CompiledOp.Switch(
                        CompiledRef.of(value.select()),
                        value.cases().stream()
                                .map(c -> new CompiledOp.Case(c.value(),
                                        compile(c.body())))
                                .toList(),
                        compile(value.defaultBody()));
                case OutputNode.ApplyTemplates apply -> {
                    // Whole-parent-content is the group-0 special case of a local group, so
                    // being a local group is the whole of being locatable.
                    final RefExpression select = apply.directive().select();
                    yield new CompiledOp.Apply(
                            apply.directive(),
                            CompiledRef.of(select),
                            isWholeParentContent(select),
                            isLocalGroup(select),
                            Dispatch.effective(apply.directive().dispatch(), project));
                }
                case OutputNode.EmitError value ->
                        new CompiledOp.EmitError(value.severity(), CompiledRef.of(value.message()));
                case OutputNode.CallTemplate value -> new CompiledOp.CallTemplate(
                        value.name(),
                        value.withParam().stream()
                                .map(param -> new CompiledOp.Arg(param.name(),
                                        CompiledRef.of(param.value())))
                                .toList());
                case OutputNode.Variable value ->
                        new CompiledOp.Variable(value.name(), compile(value.body()));
                case OutputNode.Element value -> new CompiledOp.Element(
                        value.name(), value.namespace(), value.omitIfEmpty(),
                        compile(value.body()));
                case OutputNode.Attribute value -> new CompiledOp.Attribute(
                        value.name(), value.omitIfEmpty(),
                                compile(value.body()));
                case OutputNode.Namespace value ->
                        new CompiledOp.Namespace(value.prefix(), value.uri());
                case OutputNode.ValueMap value -> new CompiledOp.ValueMap(
                        CompiledRef.of(value.select()), value.entries(), value.defaultValue(),
                                value.name());
                case OutputNode.Translate value -> transform(single("translate", value.select()),
                        value.name(), inputs ->
                                Transforms.translate(inputs, value.from(), value.to()));
                case OutputNode.StringJoin value -> transform(value.select(), value.name(),
                        inputs -> Transforms.stringJoin(inputs, value.separator()));
                case OutputNode.Replace value -> replace(value);
                case OutputNode.LowerCase value ->
                        transform(single("lower-case", value.select()), value.name(),
                                Transforms::lowerCase);
                case OutputNode.UpperCase value ->
                        transform(single("upper-case", value.select()), value.name(),
                                Transforms::upperCase);
                case OutputNode.NormalizeSpace value -> transform(
                        single("normalize-space", value.select()), value.name(),
                                Transforms::normalizeSpace);
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
                            inputs ->
                                    Transforms.substring(inputs, effectiveStart, effectiveLength));
                }
                case OutputNode.Tokenize value -> new CompiledOp.Tokenize(
                        CompiledRef.of(single("tokenize", value.select()).getFirst()),
                        value.delimiter(), value.name());
                case OutputNode.Number value ->
                        transform(single("number", value.select()), value.name(),
                                Transforms::number);
                case OutputNode.Add value ->
                        arithmetic("add", value.select(), value.name(), Arity.AT_LEAST, 1,
                                Transforms::add);
                case OutputNode.Subtract value ->
                        arithmetic("subtract", value.select(), value.name(), Arity.EXACTLY, 2,
                                Transforms::subtract);
                case OutputNode.Multiply value ->
                        arithmetic("multiply", value.select(), value.name(), Arity.AT_LEAST, 1,
                                Transforms::multiply);
                case OutputNode.Divide value ->
                        arithmetic("divide", value.select(), value.name(), Arity.EXACTLY, 2,
                                Transforms::divide);
                case OutputNode.Mod value ->
                        arithmetic("mod", value.select(), value.name(), Arity.EXACTLY, 2,
                                Transforms::mod);
                case OutputNode.Round value ->
                        arithmetic("round", value.select(), value.name(), Arity.EXACTLY, 1,
                                Transforms::round);
                case OutputNode.Floor value ->
                        arithmetic("floor", value.select(), value.name(), Arity.EXACTLY, 1,
                                Transforms::floor);
                case OutputNode.Ceiling value ->
                        arithmetic("ceiling", value.select(), value.name(), Arity.EXACTLY, 1,
                                Transforms::ceiling);
                case OutputNode.Abs value ->
                        arithmetic("abs", value.select(), value.name(), Arity.EXACTLY, 1,
                                Transforms::abs);
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
                            value.pattern(), value.timezone(), value.reference() != null,
                                    "parse-date");
                    yield new CompiledOp.ParseDate(
                            CompiledRef.of(single("parse-date", value.select()).getFirst()),
                            value.reference() == null ? null : CompiledRef.of(value.reference()),
                            parser,
                            value.name());
                }
                case OutputNode.Count value ->
                        new CompiledOp.Fold(value.select(), CompiledOp.FoldKind.COUNT, null,
                                value.name());
                case OutputNode.Sum value ->
                        new CompiledOp.Fold(value.select(), CompiledOp.FoldKind.SUM, null,
                                value.name());
                case OutputNode.Avg value ->
                        new CompiledOp.Fold(value.select(), CompiledOp.FoldKind.AVG, null,
                                value.name());
                case OutputNode.Min value ->
                        new CompiledOp.Fold(value.select(), CompiledOp.FoldKind.MIN,
                                value.as(), value.name());
                case OutputNode.Max value ->
                        new CompiledOp.Fold(value.select(), CompiledOp.FoldKind.MAX,
                                value.as(), value.name());
                case OutputNode.DistinctValues value ->
                        new CompiledOp.DistinctValues(value.select(), value.name());
                case OutputNode.Sequence value -> new CompiledOp.Sequence(value.name());
                case OutputNode.Append value ->
                        new CompiledOp.Append(value.name(), CompiledRef.of(value.select()));
                case OutputNode.Key value -> new CompiledOp.Key(value.name(), value.select(),
                        value.groupBy() == null ? null : CompiledRef.of(value.groupBy()));
                case OutputNode.KeyGet value -> new CompiledOp.KeyGet(value.key(),
                        CompiledRef.of(value.select()), value.name());
                case OutputNode.ForEachGroup value -> new CompiledOp.ForEachGroup(value.select(),
                        value.groupBy() == null ? null : CompiledRef.of(value.groupBy()),
                        compile(value.body()));
                case OutputNode.ForEach value -> new CompiledOp.ForEach(value.select(), value.as(),
                        value.sort().stream()
                                .map(key ->
                                        new CompiledOp.SortKey(CompiledRef.of(key.by()),
                                                key.order(), key.as()))
                                .toList(),
                        compile(value.body()));
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
                                final TypedValue instant = Comparisons.cast(inputs.getFirst(),
                                        Cast.DATE);
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
    private CompiledOp.CallFunction call(final OutputNode.Call value) {
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
                    throw new ConfigException("Function '" + value.function()
                            + "': argument " + (i + 1)
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
        return new CompiledOp.CallFunction(definition, select, sequences, value.name());
    }

    private static CompiledOp.Transform transform(final List<RefExpression> select,
                                                  final String name,
                                                  final Function<List<TypedValue>, TypedValue> function) {
        return new CompiledOp.Transform(select.stream().map(CompiledRef::of).toList(), name,
                function, null);
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
    private static CompiledOp.Transform arithmetic(final String what,
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
        return new CompiledOp.Transform(select.stream().map(CompiledRef::of).toList(), name,
                inputs -> inputs.size() == expected ? function.apply(inputs) : null, what);
    }

    /** A format-number closes over its picture, compiled once and refused at compile time. */
    private static CompiledOp.Transform formatNumber(final OutputNode.FormatNumber value) {
        final DecimalFormat format;
        try {
            format = new DecimalFormat(value.picture(),
                    DecimalFormatSymbols.getInstance(Locale.ROOT));
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
    private CompiledOp.Transform replace(final OutputNode.Replace value) {
        single("replace", value.select());
        if (!value.isRegex()) {
            return transform(value.select(), value.name(),
                    inputs ->
                            Transforms.replaceLiteral(inputs, value.pattern(),
                                    value.replacement()));
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
