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
import stroom.shapeshifter.engine.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.OutputNode.Entry;
import stroom.shapeshifter.engine.config.OutputNode.Param;
import stroom.shapeshifter.engine.config.OutputNode.SwitchCase;
import stroom.shapeshifter.engine.config.OutputNode.WhenBranch;
import stroom.shapeshifter.engine.config.Predicate.CharSet;
import stroom.shapeshifter.engine.config.Project.SourceConfig;
import stroom.shapeshifter.engine.config.RefExpression.MatchIndex;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;
import stroom.shapeshifter.engine.config.Template.MatchLimits;
import stroom.shapeshifter.engine.config.Template.ParamDecl;
import stroom.shapeshifter.engine.config.Template.RegexFlags;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every variant of every sum type survives a round trip — including the ones the corpus has
 * never used.
 *
 * <p>The corpus exercises about a third of {@link MatchStep} and none of {@link Predicate}, so
 * "all 36 configurations round-trip" says much less than it appears to. This test builds one of
 * everything instead, and then <b>checks that it did</b>: it walks the constructed tree, collects
 * the classes it found, and compares them against each sealed interface's permitted subclasses.
 *
 * <p>That second half is the part that keeps working. A variant added later and forgotten here
 * fails with its own name, rather than waiting for the fixture that happens to use it.
 */
class EveryVariantTest {

    /** A string equality in the new spelling — the shape the legacy "equals" alias maps to. */
    private static Condition eq(final RefExpression select, final String value) {
        return new Condition.Compare(Condition.Compare.Op.EQ,
                new Condition.Operand(select, null, Cast.STRING),
                new Condition.Operand(null, new Condition.Literal.Text(value), Cast.STRING));
    }

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @Test
    void everyVariantSurvivesARoundTrip() {
        final Project project = oneOfEverything();
        assertThat(ProjectReader.read(ProjectReader.write(project))).isEqualTo(project);
    }

    @Test
    void oneOfEverythingIsActuallyOneOfEverything() {
        final Set<Class<?>> found = new HashSet<>();
        collect(oneOfEverything(), found);

        for (final Class<?> sum : List.of(MatchExpression.class, MatchStep.class, StepRef.class,
                Predicate.class, CaptureSource.class, Condition.class, OutputNode.class,
                RefPart.class)) {
            final List<Class<?>> missing = Arrays.stream(sum.getPermittedSubclasses())
                    .filter(variant -> !found.contains(variant))
                    .toList();
            assertThat(missing)
                    .as("%s variants not covered by oneOfEverything()", sum.getSimpleName())
                    .isEmpty();
        }
    }

    // -----------------------------------------------------------------------------------
    // One of everything
    // -----------------------------------------------------------------------------------

    private static Project oneOfEverything() {
        final List<Template> templates = new ArrayList<>(List.of(everyMatch(), everyOutput()));
        // One template per remaining match expression. A match expression only exists as a
        // template's match, so this is the only place they can be put.
        for (final MatchExpression match : List.of(
                new MatchExpression.Regex("^(\\w+)$", new RegexFlags(true, true), 1),
                new MatchExpression.Delimiter(",", "\\", "\"", "\""),
                new MatchExpression.Source(),
                new MatchExpression.Named(),
                new MatchExpression.Avro("{\"type\":\"record\"}"),
                new MatchExpression.Parquet(List.of("city", "population")),
                new MatchExpression.Protobuf("/tmp/schema.desc", "example.Event"))) {
            templates.add(new Template(ID, "carrier", null, false, null, List.of(), match,
                    MatchLimits.unlimited(), List.of(), List.of(), null, false));
        }
        return new Project(
                "every variant",
                3,
                new SourceConfig(1024, true, "windows-1252", Dispatch.LEXER, true),
                templates,
                List.of(new CombinatorPattern(ID, "reusable", List.of(new MatchStep.Tag("x")))));
    }

    /** A template carrying every match expression, every step and every capture source. */
    private static Template everyMatch() {
        final List<MatchStep> steps = List.of(
                new MatchStep.Tag("literal"),
                new MatchStep.MatchByte(new byte[]{0x00, (byte) 0xFF, 0x7F}),
                new MatchStep.TakeWhile(new Predicate.Alphabetic()),
                new MatchStep.TakeWhile(new Predicate.Alphanumeric()),
                new MatchStep.TakeWhile(new Predicate.Numeric()),
                new MatchStep.TakeWhile(new Predicate.Whitespace()),
                new MatchStep.TakeWhile(new Predicate.NonWhitespace()),
                new MatchStep.TakeWhile(new Predicate.Any()),
                new MatchStep.TakeWhile(new Predicate.Custom(new CharSet(
                        "[^a-z_]",
                        List.of('_'),
                        List.of(new CharSet.Range('a', 'z')),
                        true))),
                new MatchStep.TakeUntil("::", true),
                new MatchStep.TakeBytes(new StepRef.StepOutput(1)),
                new MatchStep.TakeN(4),
                new MatchStep.AnyChar(),
                new MatchStep.ReadNumeric(NumericType.SHORT, false, Endianness.BIG),
                new MatchStep.ReadNumeric(NumericType.INT, true, Endianness.LITTLE),
                new MatchStep.ReadNumeric(NumericType.LONG, true, Endianness.BIG),
                new MatchStep.ReadNumeric(NumericType.FLOAT, true, Endianness.BIG),
                new MatchStep.ReadNumeric(NumericType.DOUBLE, true, Endianness.BIG),
                new MatchStep.ReadVarint(),
                new MatchStep.ReadVarintZigZag(),
                new MatchStep.Seek(new StepRef.Literal(2)),
                new MatchStep.SeekAbs(new StepRef.Literal(0)),
                new MatchStep.SeekBack(new StepRef.Literal(1)),
                new MatchStep.Tell(),
                new MatchStep.Decode(new StepRef.StepOutput(0), Codec.BASE64),
                new MatchStep.Decode(new StepRef.StepOutput(0), Codec.BASE64_URL),
                new MatchStep.Decode(new StepRef.StepOutput(0), Codec.URL_ENCODING),
                new MatchStep.Encode(new StepRef.StepOutput(0), Codec.HEX),
                new MatchStep.Regex("\\d+", new RegexFlags(false, true)),
                new MatchStep.Choice(List.of(
                        List.of(new MatchStep.Tag("a")),
                        List.of(new MatchStep.Tag("b")))),
                new MatchStep.Optional(List.of(new MatchStep.Tag("maybe"))),
                new MatchStep.Repeat(List.of(new MatchStep.AnyChar()), 1, 8),
                new MatchStep.Repeat(List.of(new MatchStep.AnyChar()), 0, null),
                new MatchStep.Sequence(List.of(new MatchStep.Tag("grouped"))),
                new MatchStep.PatternRef(ID),
                new MatchStep.Peek(List.of(new MatchStep.Tag("ahead"))),
                new MatchStep.Not(List.of(new MatchStep.Tag("absent"))));

        return new Template(
                ID,
                "matching",
                "mode",
                false,
                new Condition.Exists(ref()),
                List.of(new ParamDecl("depth", "0"), new ParamDecl("required", null)),
                new MatchExpression.Progressive(steps),
                new MatchLimits(1, 9, Set.of(1, 2, 5)),
                List.of(
                        new CaptureBinding("byGroup", new CaptureSource.Group(2)),
                        new CaptureBinding("byStep", new CaptureSource.Step(3)),
                        new CaptureBinding("byField", new CaptureSource.Field("name")),
                        new CaptureBinding("bySelect", new CaptureSource.Select(ref())),
                        new CaptureBinding("ignored", new CaptureSource.KeyValue(ref(), ref()))),
                List.of(new OutputNode.Text("matched")),
                "utf-8",
                true);
    }

    /** A template carrying every output node, every condition and the other match expressions. */
    private static Template everyOutput() {
        final List<RefExpression> select = List.of(ref());
        final List<RefExpression> twoSelects = List.of(ref(), ref());
        final List<OutputNode> body = new ArrayList<>(List.of(
                new OutputNode.Text("literal"),
                new OutputNode.ValueOf(ref()),
                new OutputNode.If(eq(ref(), "x"), List.of(new OutputNode.Text("then"))),
                new OutputNode.Choose(
                        List.of(new WhenBranch(new Condition.Compare(Condition.Compare.Op.NE,
                                new Condition.Operand(ref(), null, Cast.STRING),
                                new Condition.Operand(null, new Condition.Literal.Text("y"), Cast.STRING)),
                                List.of(new OutputNode.Text("when")))),
                        List.of(new OutputNode.Text("otherwise"))),
                new OutputNode.Switch(ref(),
                        List.of(new SwitchCase("a", List.of(new OutputNode.Text("case")))),
                        List.of(new OutputNode.Text("default"))),
                new OutputNode.ApplyTemplates(new ApplyDirective(
                        ref(), "row", List.of(new Param("depth", ref())), 32, "named", true,
                        Dispatch.CLASSIFY)),
                new OutputNode.EmitError(Severity.WARNING, ref()),
                new OutputNode.CallTemplate("named", List.of(new Param("depth", ref()))),
                new OutputNode.Variable("bound", List.of(new OutputNode.Text("value"))),
                new OutputNode.ValueMap(ref(), List.of(new Entry("1", "one")), "unknown", "mapped"),
                new OutputNode.Translate(select, List.of("ab"), List.of("AB"), "translated"),
                new OutputNode.StringJoin(select, ", ", null),
                new OutputNode.Replace(select, "\\s+", " ", true, null),
                new OutputNode.Replace(select, "+", " ", false, "literalReplace"),
                new OutputNode.LowerCase(select, null),
                new OutputNode.UpperCase(select, null),
                new OutputNode.NormalizeSpace(select, null),
                new OutputNode.Trim(select, null),
                new OutputNode.Substring(select, 2, 5, null),
                new OutputNode.Substring(select, 2, null, "toEnd"),
                new OutputNode.Tokenize(select, ",", null),
                new OutputNode.Number(select, null),
                new OutputNode.Add(twoSelects, "sum"),
                new OutputNode.Subtract(twoSelects, null),
                new OutputNode.Multiply(twoSelects, "product"),
                new OutputNode.Divide(twoSelects, null),
                new OutputNode.Mod(twoSelects, null),
                new OutputNode.Round(select, null),
                new OutputNode.Floor(select, null),
                new OutputNode.Ceiling(select, null),
                new OutputNode.Abs(select, "magnitude"),
                new OutputNode.StringLength(select, "length"),
                new OutputNode.SubstringBefore(select, "|", null),
                new OutputNode.SubstringAfter(select, "|", "tail"),
                new OutputNode.StartsWith(select, "pre", null),
                new OutputNode.EndsWith(select, "post", null),
                new OutputNode.Contains(select, "mid", "flag"),
                new OutputNode.FormatNumber(select, "#,##0.00", null),
                new OutputNode.ParseDate(select, "MMM d HH:mm:ss", "Europe/London", ref(), "when"),
                new OutputNode.ParseDate(select, "iso", null, null, null),
                new OutputNode.FormatDate(select, "uuuu-MM-dd", "UTC", null),
                new OutputNode.FormatDate(select, "epoch-millis", null, "ms")));

        // The remaining conditions, each inside its own guard-shaped instruction so that the
        // walker sees them all.
        for (final Condition condition : List.of(
                new Condition.Compare(Condition.Compare.Op.EQ,
                        new Condition.Operand(ref(), null, Cast.STRING),
                        new Condition.Operand(ref(), null, Cast.STRING)),
                new Condition.Compare(Condition.Compare.Op.NE,
                        new Condition.Operand(ref(), null, null),
                        new Condition.Operand(null, new Condition.Literal.Text("x"), null)),
                new Condition.Compare(Condition.Compare.Op.LE,
                        new Condition.Operand(ref(), null, Cast.NUMBER),
                        new Condition.Operand(null, new Condition.Literal.Whole(5), null)),
                new Condition.Compare(Condition.Compare.Op.GE,
                        new Condition.Operand(ref(), null, Cast.BOOLEAN),
                        new Condition.Operand(null, new Condition.Literal.Truth(true), null)),
                new Condition.Matches(ref(), "^\\w+$"),
                new Condition.Contains(ref(), "sub"),
                new Condition.StartsWith(ref(), "pre"),
                new Condition.Compare(Condition.Compare.Op.GT,
                        new Condition.Operand(ref(), null, Cast.NUMBER),
                        new Condition.Operand(null, new Condition.Literal.Fractional(1.5), null)),
                new Condition.Compare(Condition.Compare.Op.LT,
                        new Condition.Operand(ref(), null, Cast.NUMBER),
                        new Condition.Operand(null, new Condition.Literal.Fractional(-2.25), null)),
                new Condition.And(List.of(new Condition.Exists(ref()))),
                new Condition.Or(List.of(new Condition.StartsWith(ref(), "or-pre"))),
                new Condition.Not(new Condition.Exists(ref())))) {
            body.add(new OutputNode.If(condition, List.of(new OutputNode.Text("."))));
        }

        return new Template(
                ID,
                "output",
                null,
                true,
                null,
                List.of(),
                new MatchExpression.All(),
                MatchLimits.unlimited(),
                List.of(),
                body,
                null,
                false);
    }

    private static RefExpression ref() {
        return new RefExpression(List.of(
                new RefPart.Text("["),
                new RefPart.Capture(null, 0, null),
                new RefPart.Capture("var", 1, new MatchIndex(1, true, false, null)),
                new RefPart.Capture("var", 2, new MatchIndex(0, false, true, "__match_count")),
                new RefPart.Text("]")));
    }

    // -----------------------------------------------------------------------------------
    // Walking the tree
    // -----------------------------------------------------------------------------------

    /**
     * Collect the class of every record reachable from a value.
     *
     * <p>Records make this possible without any registry to keep in step: the components are the
     * structure, so a variant added tomorrow is walked into without this code changing.
     */
    private static void collect(final Object value, final Set<Class<?>> found) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(element -> collect(element, found));
            return;
        }
        if (!value.getClass().isRecord()) {
            return;
        }
        found.add(value.getClass());
        for (final RecordComponent component : value.getClass().getRecordComponents()) {
            try {
                collect(component.getAccessor().invoke(value), found);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("Could not read " + component, e);
            }
        }
    }
}
