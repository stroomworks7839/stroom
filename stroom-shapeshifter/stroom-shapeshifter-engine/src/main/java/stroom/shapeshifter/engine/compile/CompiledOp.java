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
import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;
import stroom.shapeshifter.engine.exec.Dates;
import stroom.shapeshifter.engine.exec.Transforms;
import stroom.shapeshifter.engine.exec.TypedValue;
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
 * <p>Conditions stay authored and are evaluated by {@code Conditions} as before — their
 * patterns are already interned, and compiling them further is a later change if the numbers
 * ask for it.
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
    record Call(String name, List<Arg> args) implements CompiledOp {

    }

    /** One argument of a {@link Call}. */
    record Arg(String name, CompiledRef value) {

    }

    /** Bind a variable to what a nested body writes. */
    record Variable(String name, List<CompiledOp> body) implements CompiledOp {

    }

    /** Map a value through a lookup table. */
    record ValueMap(CompiledRef select,
                    List<OutputNode.Entry> entries,
                    String defaultValue,
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

    /**
     * Compile a body.
     *
     * @param patterns the project's interned patterns, already collected — a regex replace
     *                 resolves its {@link BytePattern} here, once
     */
    static List<CompiledOp> compile(final List<OutputNode> body,
                                    final Map<String, BytePattern> patterns,
                                    final Project project) {
        final List<CompiledOp> ops = new ArrayList<>(body.size());
        for (final OutputNode node : body) {
            final CompiledOp op = switch (node) {
                case OutputNode.Text text ->
                        new Text(text.value().getBytes(StandardCharsets.UTF_8));
                case OutputNode.ValueOf valueOf -> new ValueOf(CompiledRef.of(valueOf.select()));
                case OutputNode.If value ->
                        new If(value.test(), compile(value.then(), patterns, project));
                case OutputNode.Choose value -> new Choose(
                        value.when().stream()
                                .map(branch -> new When(branch.test(), compile(branch.body(), patterns, project)))
                                .toList(),
                        compile(value.otherwise(), patterns, project));
                case OutputNode.Switch value -> new Switch(
                        CompiledRef.of(value.select()),
                        value.cases().stream()
                                .map(c -> new Case(c.value(), compile(c.body(), patterns, project)))
                                .toList(),
                        compile(value.defaultBody(), patterns, project));
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
                case OutputNode.CallTemplate value -> new Call(
                        value.name(),
                        value.withParam().stream()
                                .map(param -> new Arg(param.name(), CompiledRef.of(param.value())))
                                .toList());
                case OutputNode.Variable value ->
                        new Variable(value.name(), compile(value.body(), patterns, project));
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
                    // A version-5 start below 1 clamps to the first position.
                    final int start = project.version() >= 5 ? value.start() - 1 : value.start();
                    yield transform(single("substring", value.select()),
                            value.name(), inputs -> Transforms.substring(inputs, start, value.length()));
                }
                case OutputNode.Tokenize value -> transform(single("tokenize", value.select()),
                        value.name(), inputs -> Transforms.tokenize(inputs, value.delimiter()));
                case OutputNode.Number value ->
                        transform(single("number", value.select()), value.name(), Transforms::number);
                case OutputNode.Add value ->
                        arithmetic("add", value.select(), value.name(), 1, Transforms::add);
                case OutputNode.Subtract value ->
                        arithmetic("subtract", value.select(), value.name(), 2, Transforms::subtract);
                case OutputNode.Multiply value ->
                        arithmetic("multiply", value.select(), value.name(), 1, Transforms::multiply);
                case OutputNode.Divide value ->
                        arithmetic("divide", value.select(), value.name(), 2, Transforms::divide);
                case OutputNode.Mod value ->
                        arithmetic("mod", value.select(), value.name(), 2, Transforms::mod);
                case OutputNode.Round value ->
                        arithmetic("round", value.select(), value.name(), 1, Transforms::round);
                case OutputNode.Floor value ->
                        arithmetic("floor", value.select(), value.name(), 1, Transforms::floor);
                case OutputNode.Ceiling value ->
                        arithmetic("ceiling", value.select(), value.name(), 1, Transforms::ceiling);
                case OutputNode.Abs value ->
                        arithmetic("abs", value.select(), value.name(), 1, Transforms::abs);
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
                                final TypedValue instant = stroom.shapeshifter.engine.exec.Comparisons
                                        .cast(inputs.getFirst(), stroom.shapeshifter.engine.config.Cast.DATE);
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
     * @param arity the required select count; {@code add}/{@code multiply} fold and take
     *              {@code arity} as a minimum instead
     */
    private static Transform arithmetic(final String what,
                                        final List<RefExpression> select,
                                        final String name,
                                        final int arity,
                                        final Function<List<TypedValue>, TypedValue> function) {
        final boolean fold = what.equals("add") || what.equals("multiply");
        if (fold ? select.size() < arity : select.size() != arity) {
            throw new ConfigException("A " + what + " takes "
                                      + (fold ? "at least " : "exactly ") + arity
                                      + (arity == 1 ? " select" : " selects")
                                      + ", but has " + select.size());
        }
        final int expected = select.size();
        return new Transform(select.stream().map(CompiledRef::of).toList(), name,
                inputs -> inputs.size() == expected ? function.apply(inputs) : null, what);
    }

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
     * Refuse extra selects on a one-input transform. Only {@code string-join} folds a list;
     * every other transform reads its first input, so a second one is an authoring mistake
     * that would otherwise run and silently drop data.
     */
    private static List<RefExpression> single(final String what, final List<RefExpression> select) {
        if (select.size() > 1) {
            throw new ConfigException("A " + what + " takes one select, but has " + select.size()
                                      + ": only the first select would be read");
        }
        return select;
    }

    /** A regex replace closes over its compiled pattern; a literal one over its text. */
    private static Transform replace(final OutputNode.Replace value,
                                     final Map<String, BytePattern> patterns) {
        single("replace", value.select());
        if (!value.isRegex()) {
            return transform(value.select(), value.name(),
                    inputs -> Transforms.replaceLiteral(inputs, value.pattern(), value.replacement()));
        }
        final BytePattern pattern = patterns.get(value.pattern());
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
