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

import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.config.CaptureBinding;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Declaration;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.Template;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every instruction that binds a name is collected by {@code ReferenceCheck.visit}, the
 * compiler's one reference walk (E27).
 *
 * <p>The walk's switch is exhaustive, so an instruction the vocabulary gains cannot be
 * ignored outright — but exhaustiveness cannot tell whether an arm does the <i>right</i>
 * thing, and an arm that read its selects while forgetting to bind its name would make
 * perfectly good configurations fail to compile with "reads a name nothing writes". That is
 * what this pins: one configuration per binding instruction, each writing a name and then
 * reading it straight back, each of which must compile.
 *
 * <p>The same configurations are run a second time with an <b>engine</b> name bound, and each
 * must be refused. Design 30 phase 4 put the engine's variables in execution frames, so a
 * binding named {@code __index} would write the registry while every reference to that name
 * reads the frame — written, and unreadable. One walk collects the bindings, so one refusal
 * covers them, and running it over the whole vocabulary is what says the refusal did not get
 * attached to a single arm.
 */
class ReferenceCheckBindingsTest {

    private static RefExpression ref(final String var) {
        return new RefExpression(List.of(new RefExpression.RefPart.Capture(var, 0, null)));
    }

    /** Design 35: every name the helpers bind or read is declared, once, on the line template. */
    private static List<Declaration> declared(final String... names) {
        return declared(Declaration.Type.SCALAR, names);
    }

    private static List<Declaration> declared(final Declaration.Type boundType, final String... names) {
        return java.util.Arrays.stream(names).distinct()
                .map(name -> new Declaration(name, name.equals("seed") ? Declaration.Type.SCALAR : boundType)).toList();
    }

    private static final List<RefExpression> SELECT = List.of(ref("seed"));
    private static final List<RefExpression> PAIR = List.of(ref("seed"), ref("seed"));

    private static Map<String, OutputNode> binders(final String bound) {
        final Map<String, OutputNode> out = new LinkedHashMap<>();
        out.put("variable", new OutputNode.Variable(bound, List.of(new OutputNode.Text("x"))));
        out.put("put", new OutputNode.Put(ref(bound), null, ref("seed")));
        out.put("translate", new OutputNode.Translate(SELECT, List.of("a"), List.of("b"), bound));
        out.put("string-join", new OutputNode.StringJoin(SELECT, ",", bound));
        out.put("replace", new OutputNode.Replace(SELECT, "a", "b", false, bound));
        out.put("lower-case", new OutputNode.LowerCase(SELECT, bound));
        out.put("upper-case", new OutputNode.UpperCase(SELECT, bound));
        out.put("normalize-space", new OutputNode.NormalizeSpace(SELECT, bound));
        out.put("trim", new OutputNode.Trim(SELECT, bound));
        out.put("substring", new OutputNode.Substring(SELECT, 1, 2, bound));
        out.put("tokenize", new OutputNode.Tokenize(SELECT, ",", bound));
        out.put("number", new OutputNode.Number(SELECT, bound));
        out.put("add", new OutputNode.Add(PAIR, bound));
        out.put("subtract", new OutputNode.Subtract(PAIR, bound));
        out.put("multiply", new OutputNode.Multiply(PAIR, bound));
        out.put("divide", new OutputNode.Divide(PAIR, bound));
        out.put("mod", new OutputNode.Mod(PAIR, bound));
        out.put("round", new OutputNode.Round(SELECT, bound));
        out.put("floor", new OutputNode.Floor(SELECT, bound));
        out.put("ceiling", new OutputNode.Ceiling(SELECT, bound));
        out.put("abs", new OutputNode.Abs(SELECT, bound));
        out.put("string-length", new OutputNode.StringLength(SELECT, bound));
        out.put("substring-before", new OutputNode.SubstringBefore(SELECT, "|", bound));
        out.put("substring-after", new OutputNode.SubstringAfter(SELECT, "|", bound));
        out.put("starts-with", new OutputNode.StartsWith(SELECT, "a", bound));
        out.put("ends-with", new OutputNode.EndsWith(SELECT, "a", bound));
        out.put("contains", new OutputNode.Contains(SELECT, "a", bound));
        out.put("format-number", new OutputNode.FormatNumber(SELECT, "#0.00", bound));
        out.put("parse-date", new OutputNode.ParseDate(SELECT, "iso", null, null, bound));
        out.put("format-date", new OutputNode.FormatDate(SELECT, "iso", null, bound));
        return out;
    }

    /**
     * A reference naming a group of a variable is refused, because a variable holds one value.
     *
     * <p>The group a reference names is spent when the configuration is compiled — the DS3
     * migration binds a capture per referenced group (E48) — so by the time the graph exists
     * there is one store per name and nothing to select within it. Before this was refused, a
     * native configuration asking for group 2 read as absent for ever, with no message.
     */
    @Test
    void varReferenceMayNotNameAGroup() {
        assertThatThrownBy(() -> Shapeshifter.compile(readsGroupOf("seed", 2)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("asks for group 2")
                .hasMessageContaining("seed");
    }

    /** Group 0 is the only group a variable has, and it is the spelling everything uses. */
    @Test
    void varReferenceAtGroupZeroIsFine() {
        assertThatCode(() -> Shapeshifter.compile(readsGroupOf("seed", 0)))
                .doesNotThrowAnyException();
    }

    private static Project readsGroupOf(final String name, final int group) {
        final OutputNode read = new OutputNode.ValueOf(new RefExpression(
                List.of(new RefExpression.RefPart.Capture(name, group, null))));
        final Template line = new Template(
                UUID.randomUUID(), "line", "doc", false, null, List.of(), declared("seed", name),
                new MatchExpression.Regex("([^\n]*)\n", null, 0),
                new Template.MatchLimits(0, -1, null),
                List.of(new CaptureBinding("seed", new CaptureBinding.CaptureSource.Group(1), null)),
                List.of(read),
                null, false);
        final Template source = new Template(
                UUID.randomUUID(), "source", null, false, null, List.of(), List.of(),
                new MatchExpression.Source(),
                new Template.MatchLimits(0, -1, null), List.of(),
                List.of(new OutputNode.ApplyTemplates(new OutputNode.ApplyDirective(
                        new RefExpression(List.of(new RefExpression.RefPart.Capture(null, 0, null))),
                        "doc", List.of(), OutputNode.ApplyDirective.DEFAULT_MAX_DEPTH, false,
                        null))),
                null, false);
        return new Project("t", 5, Project.SourceConfig.defaults(), List.of(source, line), List.of());
    }

    /** The binder writes the name; the instruction after it reads the same name back. */
    private static Project project(final OutputNode binder, final String bound) {
        final Template line = new Template(
                UUID.randomUUID(), "line", "doc", false, null, List.of(),
                // tokenize fills a list with the pieces; every other binder binds one value
                declared(binder instanceof OutputNode.Tokenize ? Declaration.Type.LIST : Declaration.Type.SCALAR,
                        "seed", bound),
                new MatchExpression.Regex("([^\n]*)\n", null, 0),
                new Template.MatchLimits(0, -1, null),
                List.of(new CaptureBinding("seed", new CaptureBinding.CaptureSource.Group(1), null)),
                List.of(binder, new OutputNode.ValueOf(binder instanceof OutputNode.Tokenize
                        ? new RefExpression(List.of(new RefExpression.RefPart.Accessor(
                                RefExpression.RefPart.Accessor.Kind.LAST, ref(bound), null, null, null)))
                        : ref(bound))),
                null, false);
        final Template source = new Template(
                UUID.randomUUID(), "source", null, false, null, List.of(), List.of(),
                new MatchExpression.Source(),
                new Template.MatchLimits(0, -1, null), List.of(),
                List.of(new OutputNode.ApplyTemplates(new OutputNode.ApplyDirective(
                        new RefExpression(List.of(new RefExpression.RefPart.Capture(null, 0, null))),
                        "doc", List.of(), OutputNode.ApplyDirective.DEFAULT_MAX_DEPTH, false,
                        null))),
                null, false);
        return new Project("t", 5, Project.SourceConfig.defaults(), List.of(source, line), List.of());
    }

    @TestFactory
    List<DynamicTest> everyBinderIsCollectedByTheSingleWalk() {
        return binders("bound").entrySet().stream()
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () ->
                        assertThatCode(() -> Shapeshifter.compile(project(entry.getValue(), "bound")))
                                .as("%s binds a name the body walk must collect", entry.getKey())
                                .doesNotThrowAnyException()))
                .toList();
    }

}
