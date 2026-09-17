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
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.OutputNode.Param;
import stroom.shapeshifter.engine.config.OutputNode.SwitchCase;
import stroom.shapeshifter.engine.config.OutputNode.WhenBranch;
import stroom.shapeshifter.engine.config.Project.SourceConfig;
import stroom.shapeshifter.engine.config.RefExpression.MatchIndex;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;
import stroom.shapeshifter.engine.config.RefExpression.RefPart.Accessor;
import stroom.shapeshifter.engine.config.Template.MatchLimits;
import stroom.shapeshifter.engine.config.Template.ParamDecl;
import stroom.shapeshifter.engine.config.Template.RegexFlags;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
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
 * <p>The corpus exercises a fraction of {@link PatternNode} and few of the casts, so "all the
 * configurations round-trip" says much less than it appears to. This test builds one of
 * everything instead, and then <b>checks that it did</b>: it walks the constructed tree, collects
 * the classes it found, and compares them against each sealed interface's permitted subclasses.
 *
 * <p>That second half is the part that keeps working. A variant added later and forgotten here
 * fails with its own name, rather than waiting for the fixture that happens to use it.
 */
class EveryVariantTest {

    /** A string equality: {@code eq} with both operands cast to string (design 35 §5 retired the alias). */
    private static Condition eq(final RefExpression select, final String value) {
        return new Condition.Compare(Condition.Compare.Op.EQ,
                new Condition.Operand(select, null, Cast.STRING),
                new Condition.Operand(null, new Condition.Literal.Text(value), Cast.STRING));
    }

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    /**
     * A payload-less condition is written as the bare string, like every other payload-less
     * variant; the round trip alone would not notice a regression to the empty object, which
     * the reader also accepts (design 27 phase 7).
     */
    @Test
    void payloadLessConditionsAreWrittenBare() {
        final String json = """
                {"name": "bare", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "root", "match": "source",
                   "body": [{"if": {"test": {"is-first": {}}, "then": [{"text": "!"}]}},
                            {"if": {"test": "is-last", "then": [{"text": "?"}]}}]}
                 ]}
                """;
        final String written = ProjectReader.write(ProjectReader.read(json));
        assertThat(written).contains("\"is-first\"").contains("\"is-last\"")
                .doesNotContainPattern("\"is-first\"\\s*:\\s*\\{").doesNotContainPattern("\"is-last\"\\s*:\\s*\\{");
    }

    @Test
    void everyVariantSurvivesARoundTrip() {
        final Project project = oneOfEverything();
        assertThat(ProjectReader.read(ProjectReader.write(project))).isEqualTo(project);
    }

    @Test
    void oneOfEverythingIsActuallyOneOfEverything() {
        final Set<Class<?>> found = new HashSet<>();
        collect(oneOfEverything(), found);

        for (final Class<?> sum : List.of(MatchExpression.class, CaptureSource.class, Condition.class,
                OutputNode.class, RefPart.class, PatternNode.class, MatchExpression.MatchPart.class,
                MatchExpression.Length.class)) {
            final List<Class<?>> missing = variants(sum).stream()
                    .filter(variant -> !found.contains(variant))
                    .toList();
            assertThat(missing)
                    .as("%s variants not covered by oneOfEverything()", sum.getSimpleName())
                    .isEmpty();
        }
    }

    /**
     * The records a sealed type permits, through any sealed interfaces between: an
     * {@code OutputNode} is a holder, a binding or a leaf before it is an instruction (D47).
     */
    private static List<Class<?>> variants(final Class<?> sum) {
        final List<Class<?>> records = new ArrayList<>();
        for (final Class<?> permitted : sum.getPermittedSubclasses()) {
            if (permitted.isInterface()) {
                records.addAll(variants(permitted));
            } else {
                records.add(permitted);
            }
        }
        return records;
    }

    // -----------------------------------------------------------------------------------
    // One of everything
    // -----------------------------------------------------------------------------------

    private static RefExpression name(final String name) {
        return new RefExpression(List.of(new RefPart.Capture(name, 0, null)));
    }

    private static RefExpression accessorRef(final Accessor.Kind kind, final RefExpression of,
            final RefExpression key) {
        return new RefExpression(List.of(new Accessor(kind, of, key, null, null)));
    }

    private static Project oneOfEverything() {
        final List<Template> templates = new ArrayList<>(List.of(everyMatch(), everyOutput()));
        // One template per remaining match expression. A match expression only exists as a
        // template's match, so this is the only place they can be put.
        for (final MatchExpression match : List.of(
                new MatchExpression.Regex("^(\\w+)$", new RegexFlags(true, true), 1),
                new MatchExpression.Delimiter(",", "\\", "\"", "\""),
                new MatchExpression.Source(),
                new MatchExpression.Named(),
                // The pattern tree, every node once (design 38)
                new MatchExpression.Pattern(new PatternNode.Sequence(List.of(
                        new PatternNode.Labelled(new PatternNode.Tag("x"), "t", null),
                        new PatternNode.TakeWhile("[a-z]", 1, PatternNode.Repeat.UNBOUNDED),
                        new PatternNode.TakeUntil(",", false),
                        new PatternNode.TakeUntil(" end", true),
                        new PatternNode.Labelled(new PatternNode.Take(4), "n", BinaryCast.UINT32BE),
                        new PatternNode.Take(1),
                        new PatternNode.Regex("(\\d+)", new RegexFlags(true, false)),
                        new PatternNode.Ref("IP_ADDRESS"),
                        new PatternNode.Choice(List.of(new PatternNode.Tag("a"), new PatternNode.Tag("b"))),
                        new PatternNode.Optional(new PatternNode.Tag("?")),
                        new PatternNode.Repeat(new PatternNode.Tag("r"), 1, 3, false),
                        new PatternNode.Peek(new PatternNode.Tag("p")),
                        new PatternNode.Not(new PatternNode.Tag("q")),
                        new PatternNode.Labelled(new PatternNode.Sequence(List.of()), "here", BinaryCast.POSITION)))),
                // The match sequence, every part and every length once
                new MatchExpression.Parts(List.of(
                        new MatchExpression.MatchPart.Pattern(
                                new PatternNode.Labelled(new PatternNode.Take(2), "len", BinaryCast.UINT16LE)),
                        new MatchExpression.MatchPart.Take(new MatchExpression.Length.Label("len"), "body"),
                        new MatchExpression.MatchPart.Take(new MatchExpression.Length.Literal(4), null),
                        new MatchExpression.MatchPart.Seek(new MatchExpression.Length.Var("skip"), false),
                        new MatchExpression.MatchPart.Seek(new MatchExpression.Length.Literal(8), true),
                        new MatchExpression.MatchPart.Read(BinaryCast.ZIGZAG, "count"),
                        new MatchExpression.MatchPart.Read(BinaryCast.UINT8, null))))) {
            templates.add(new Template(ID, "carrier", null, false, null, List.of(), List.of(), match,
                    MatchLimits.unlimited(), List.of(), List.of(), null, false));
        }
        return new Project(
                "every variant",
                3,
                new SourceConfig(1024, true, "windows-1252", Dispatch.LEXER, true, 4096),
                templates);
    }

    /** A template carrying every part of a template, and every capture source. */
    private static Template everyMatch() {
        final PatternNode.Sequence nodes = new PatternNode.Sequence(List.of(
                new PatternNode.Tag("literal"),
                new PatternNode.Labelled(new PatternNode.TakeWhile("[a-z]", 0, PatternNode.Repeat.UNBOUNDED), "t",
                        null)));

        return new Template(
                ID,
                "matching",
                "mode",
                false,
                new Condition.Exists(ref()),
                List.of(new ParamDecl("depth", "0"), new ParamDecl("required", null)),
                List.of(new Declaration("declared", Declaration.Type.LIST),
                        new Declaration("total", Declaration.Type.SCALAR),
                        new Declaration("seq", Declaration.Type.LIST),
                        new Declaration("lookup", Declaration.Type.MAP,
                                List.of(new Declaration.Entry("1", "one"), new Declaration.Entry("2", "two")))),
                new MatchExpression.Pattern(nodes),
                new MatchLimits(1, 9, Set.of(1, 2, 5)),
                List.of(
                        new CaptureBinding("byGroup", new CaptureSource.Group(2), Cast.INTEGER),
                        new CaptureBinding("byLabel", new CaptureSource.Label("t"), null),
                        new CaptureBinding("bySelect", new CaptureSource.Select(ref()), null),
                        new CaptureBinding("ignored", new CaptureSource.KeyValue(ref(), ref()), null)),
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
                new OutputNode.Call("hex-to-dec", List.of(ref()), "called"),
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
                        ref(), "row", List.of(new Param("depth", ref())), 32, true,
                        Dispatch.CLASSIFY)),
                new OutputNode.EmitError(Severity.WARNING, ref()),
                new OutputNode.CallTemplate("named", List.of(new Param("depth", ref()))),
                new OutputNode.Variable("bound", List.of(new OutputNode.Text("value"))),
                new OutputNode.Element("el", "urn:e", true, List.of(
                        new OutputNode.Namespace("p", "urn:p"),
                        new OutputNode.Attribute("p:a", true, List.of(new OutputNode.Text("v"))),
                        new OutputNode.Text("content"))),
                new OutputNode.Translate(select, List.of("ab"), List.of("AB"), "translated"),
                new OutputNode.StringJoin(select, ", ", null),
                new OutputNode.Replace(select, "\\s+", " ", true, null),
                new OutputNode.Replace(select, "+", " ", false, "literalReplace"),
                new OutputNode.LowerCase(select, null),
                new OutputNode.Decode(select, Codec.BASE64, "decoded"),
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
                new OutputNode.FormatDate(select, "epoch-millis", null, "ms"),
                new OutputNode.Append(name("seq"), ref()),
                new OutputNode.Append(accessorRef(Accessor.Kind.GET, name("lookup"), RefExpression.text("k")), ref()),
                new OutputNode.Insert(name("seq"), RefExpression.text("1"), ref()),
                new OutputNode.Put(name("seq"), RefExpression.text("1"), ref()),
                new OutputNode.Put(name("lookup"), ref(), ref()),
                new OutputNode.Put(name("total"), null, ref()),
                new OutputNode.Remove(name("seq"), RefExpression.text("1")),
                new OutputNode.Remove(name("lookup"), ref()),
                new OutputNode.Clear(name("seq")),
                new OutputNode.ForEach(name("seq"), "item", null, List.of(),
                        List.of(new OutputNode.Text("each"))),
                new OutputNode.ForEach(name("lookup"), "v", "k", List.of(), List.of()),
                new OutputNode.ForEach(name("seq"), null, null,
                        List.of(new OutputNode.Sort(ref(), OutputNode.Order.DESCENDING, Cast.NUMBER),
                                new OutputNode.Sort(ref(), null, null)),
                        List.of()),
                new OutputNode.ForEachGroup(name("seq"), ref(), List.of(new OutputNode.Text("g"))),
                new OutputNode.ForEachGroup(name("seq"), null, List.of()),
                new OutputNode.ValueOf(new RefExpression(List.of(
                        new Accessor(Accessor.Kind.GET, name("lookup"), RefExpression.text("k"),
                                RefExpression.text("none"), null),
                        new Accessor(Accessor.Kind.SIZE, name("seq"), null, null, null),
                        new Accessor(Accessor.Kind.CONTAINS, name("seq"), ref(), null, null),
                        new Accessor(Accessor.Kind.LAST, name("seq"), null, null, null),
                        new Accessor(Accessor.Kind.HEAD, name("seq"), null, null, null),
                        new Accessor(Accessor.Kind.KEYS, name("lookup"), null, null, null),
                        new Accessor(Accessor.Kind.VALUES, name("lookup"), null, null, null),
                        new Accessor(Accessor.Kind.SUM, name("seq"), null, null, null),
                        new Accessor(Accessor.Kind.AVG, name("seq"), null, null, null),
                        new Accessor(Accessor.Kind.MIN, name("seq"), null, null, Cast.NUMBER),
                        new Accessor(Accessor.Kind.MAX, name("seq"), null, null, null))))));

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
                new Condition.IsFirst(),
                new Condition.IsLast(),
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
                RefPart.Capture.label("t"),
                new RefPart.Capture("var", 1, new MatchIndex(1, true, false, null, null)),
                new RefPart.Capture("var", 2, new MatchIndex(0, false, true, null, EngineVars.MATCH_COUNT)),
                new RefPart.Counter(EngineVars.INDEX, new MatchIndex(0, false, true, null, null)),
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
