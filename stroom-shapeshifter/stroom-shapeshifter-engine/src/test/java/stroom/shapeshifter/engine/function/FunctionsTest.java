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

package stroom.shapeshifter.engine.function;

import stroom.shapeshifter.engine.Instrument;
import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.output.XmlByteSink;
import stroom.shapeshifter.engine.value.TypedValue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design 26 phase 1: the registry and the contract, exercised through a {@code call} instruction
 * with a registry of test functions — nothing here is a function anyone would ship.
 */
class FunctionsTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final FunctionRegistry REGISTRY = FunctionRegistry.of(
            FunctionDefinition.of("upper", Signature.of(Kind.STRING, Kind.STRING), Purity.PURE,
                    context -> args -> args.string(0) == null ? null : TypedValue.of(args.string(0).toUpperCase())),
            FunctionDefinition.of("twice", Signature.of(Kind.NUMBER, Kind.NUMBER), Purity.PURE,
                    context -> args -> args.number(0) == null ? null : new TypedValue.Double(args.number(0) * 2)),
            FunctionDefinition.of("whole", Signature.of(Kind.INTEGER, Kind.INTEGER), Purity.PURE,
                    context -> args -> args.integer(0) == null ? null : new TypedValue.Integer(args.integer(0) + 1)),
            FunctionDefinition.of("flag", Signature.of(Kind.STRING, Kind.BOOLEAN), Purity.PURE,
                    context -> args -> TypedValue.of(args.bool(0) == null ? "absent" : args.bool(0) ? "yes" : "no")),
            FunctionDefinition.of("year", Signature.of(Kind.STRING, Kind.DATE), Purity.PURE,
                    context -> args -> args.date(0) == null
                            ? null
                            : TypedValue.of(args.date(0).toJavaInstant().toString().substring(0, 4))),
            FunctionDefinition.of("nth", Signature.of(1, Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING),
                    Purity.PURE,
                    context -> args -> TypedValue.of(args.size() + ":" + args.string(0) + "/" + args.string(1)
                                                     + "/" + args.string(2))),
            FunctionDefinition.of("strict", Signature.of(Kind.STRING, Kind.NUMBER), Purity.PURE,
                    context -> args -> {
                        if (args.miscast(0)) {
                            context.warn("argument 1 is not a number");
                        }
                        return TypedValue.of(args.number(0) == null ? "-" : "n");
                    }),
            FunctionDefinition.of("total", Signature.of(Kind.NUMBER, Kind.SEQUENCE), Purity.PURE,
                    context -> args -> new TypedValue.Double(args.sequence(0).stream()
                            .mapToDouble(v -> v.asNumber() == null ? 0 : v.asNumber()).sum())),
            FunctionDefinition.of("boom", Signature.of(Kind.STRING, Kind.STRING), Purity.PURE,
                    context -> args -> {
                        throw new IllegalStateException("kaboom");
                    }),
            FunctionDefinition.of("die", Signature.of(Kind.STRING, Kind.STRING), Purity.PURE,
                    context -> args -> {
                        throw new FunctionFailure("the end");
                    }),
            FunctionDefinition.of("fetch", Signature.of(Kind.STRING, Kind.STRING), Purity.IMPURE,
                    context -> args -> TypedValue.of("fetched " + args.string(0))),
            FunctionDefinition.of("clock", Signature.of(Kind.STRING), Purity.CONTEXT,
                    context -> args -> TypedValue.of("tick")),
            FunctionDefinition.of("count", Signature.of(Kind.INTEGER), Purity.CONTEXT,
                    context -> args -> {
                        final int n = (int) context.state().merge("count", 1, (a, b) -> (Integer) a + (Integer) b);
                        return new TypedValue.Integer(n);
                    }),
            FunctionDefinition.of("where", Signature.of(Kind.INTEGER), Purity.CONTEXT,
                    context -> args -> new TypedValue.Integer(context.inputOffset())),
            FunctionDefinition.of("extent", Signature.of(Kind.STRING), Purity.CONTEXT,
                    context -> args -> TypedValue.of(context.recordNumber() + "@" + context.inputOffset()
                                                     + "+" + context.inputLength())),
            FunctionDefinition.of("say", Signature.of(Kind.STRING, Kind.STRING), Purity.IMPURE,
                    context -> args -> {
                        context.message(Severity.INFO, "noted " + args.string(0));
                        return null;
                    }),
            FunctionDefinition.of("greeting", Signature.of(Kind.STRING), Purity.CONTEXT,
                    context -> args -> TypedValue.of(String.valueOf(context.service(String.class)))),
            FunctionDefinition.of("unbindable", Signature.of(Kind.STRING), Purity.PURE,
                    context -> {
                        throw new IllegalStateException("no such service");
                    }),
            FunctionDefinition.of("bound", Signature.of(Kind.INTEGER), Purity.CONTEXT,
                    context -> {
                        final int binding = COUNTER.incrementAndGet();
                        return args -> new TypedValue.Integer(binding);
                    }));

    private record Run(String output, List<Message> messages) {

    }

    /** One template per line; the body given runs for each line with group 1 as the line. */
    private static String lines(final String body) {
        return """
                {"name": "functions", "version": 5,
                 "source": {"buffer_size": 4096, "ignore_errors": false, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                   "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "l"}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "l",
                   "match": {"regex": {"pattern": "([^\\n]*)\\n"}},
                   "body": [BODY, {"text": "|"}]}
                 ]}
                """.replace("BODY", body);
    }

    private static String call(final String function, final String select, final String name) {
        return "{\"call\": {\"function\": \"" + function + "\", \"select\": [" + select + "]"
               + (name == null ? "" : ", \"name\": \"" + name + "\"") + "}}";
    }

    private static final String GROUP1 = "{\"parts\": [{\"capture\": {\"group\": 1}}]}";
    private static final String LITERAL_X = "{\"parts\": [{\"text\": \"x\"}]}";

    private static Run run(final String json, final String input, final RunMode mode, final Services services) {
        final CompiledProject compiled = Shapeshifter.compile(ProjectReader.read(json), REGISTRY);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.run(compiled,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new XmlByteSink(output),
                Instrument.NONE, mode, services);
        return new Run(output.toString(StandardCharsets.UTF_8), messages);
    }

    private static Run run(final String json, final String input) {
        return run(json, input, RunMode.NORMAL, Services.NONE);
    }

    private static Project project(final String json) {
        return ProjectReader.read(json);
    }

    @Test
    void unknownFunctionIsRefusedByName() {
        assertThatThrownBy(() -> Shapeshifter.compile(project(lines(call("nope", GROUP1, null))), REGISTRY))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Unknown function: 'nope'");
        assertThatThrownBy(() -> Shapeshifter.compile(project(lines(call("upper", GROUP1, null)))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("no functions are registered");
    }

    @Test
    void arityIsCheckedAtCompileTimeAgainstTheSignature() {
        assertThatThrownBy(() -> Shapeshifter.compile(project(lines(call("upper", "", null))), REGISTRY))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("'upper' takes exactly 1 argument, but the call has 0");
        assertThatThrownBy(() -> Shapeshifter.compile(
                project(lines(call("nth", GROUP1 + "," + GROUP1 + "," + GROUP1 + "," + GROUP1, null))), REGISTRY))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("'nth' takes 1 to 3 arguments, but the call has 4");
    }

    @Test
    void callWritesItsResultOrBindsItByName() {
        assertThat(run(lines(call("upper", GROUP1, null)), "ab\ncd\n").output()).isEqualTo("AB|CD|");
        final String bound = "[" + call("upper", GROUP1, "u") + ", {\"value-of\": {\"parts\": [{\"text\": \"<\"},"
                             + " {\"capture\": {\"var_id\": \"u\", \"group\": 0}}, {\"text\": \">\"}]}}]";
        assertThat(run(lines(bound.substring(1, bound.length() - 1)), "ab\n").output()).isEqualTo("<AB>|");
    }

    @Test
    void argumentsAreCastToTheirKinds() {
        assertThat(run(lines(call("twice", GROUP1, null)), "21\n1.5\n").output()).isEqualTo("42|3|");
        assertThat(run(lines(call("whole", GROUP1, null)), "41\n").output()).isEqualTo("42|");
        assertThat(run(lines(call("flag", GROUP1, null)), "true\n0\nmaybe\n").output()).isEqualTo("yes|no|absent|");
        assertThat(run(lines(call("year", GROUP1, null)), "2026-09-04T10:00:00Z\n").output()).isEqualTo("2026|");
    }

    @Test
    void positionsAreKeptAndAbsentIsNullNotDropped() {
        // Group 1 of a blank line resolves to nothing: position 1 is null, position 2 is still x.
        assertThat(run(lines(call("nth", GROUP1 + "," + LITERAL_X, null)), "a\n\n").output())
                .isEqualTo("2:a/x/null|2:null/x/null|");
        assertThat(run(lines(call("nth", GROUP1, null)), "a\n").output()).isEqualTo("1:a/null/null|");
    }

    @Test
    void miscastIsTheFunctionsToWarnAbout() {
        final Run run = run(lines(call("strict", GROUP1, null)), "12\nabc\n\n");
        assertThat(run.output()).isEqualTo("n|-|-|");
        // Present but unreadable warns; absent does not.
        assertThat(run.messages()).singleElement().satisfies(m -> {
            assertThat(m.severity()).isEqualTo(Severity.WARNING);
            assertThat(m.text()).isEqualTo("strict: argument 1 is not a number");
        });
    }

    @Test
    void sequenceArgumentReceivesEveryEntryOfTheNamedStore() {
        final String body = "{\"sequence\": {\"name\": \"nums\"}},"
                            + " {\"append\": {\"select\": {\"parts\": [{\"text\": \"1\"}]}, \"name\": \"nums\"}},"
                            + " {\"append\": {\"select\": {\"parts\": [{\"text\": \"2\"}]}, \"name\": \"nums\"}},"
                            + " {\"append\": {\"select\": " + GROUP1 + ", \"name\": \"nums\"}}, "
                            + call("total", "{\"parts\": [{\"capture\": {\"var_id\": \"nums\", \"group\": 0}}]}", null);
        assertThat(run(lines(body), "39\n").output()).isEqualTo("42|");
        assertThatThrownBy(() -> Shapeshifter.compile(project(lines(call("total", GROUP1, null))), REGISTRY))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("argument 1 is a sequence and must name a variable");
    }

    @Test
    void throwingFunctionIsAnErrorAndAnAbsentResult() {
        final Run run = run(lines(call("boom", GROUP1, null)), "a\nb\n");
        assertThat(run.output()).isEqualTo("||");
        assertThat(run.messages()).hasSize(2).allSatisfy(m -> {
            assertThat(m.severity()).isEqualTo(Severity.ERROR);
            assertThat(m.text()).isEqualTo("boom: kaboom");
        });
    }

    @Test
    void functionFailureEndsTheRunAsFatal() {
        final Run run = run(lines(call("die", GROUP1, null)), "a\nb\n");
        assertThat(run.output()).isEqualTo("");
        assertThat(run.messages()).singleElement().satisfies(m -> {
            assertThat(m.severity()).isEqualTo(Severity.FATAL);
            assertThat(m.text()).isEqualTo("die: the end");
        });
    }

    @Test
    void previewSkipsImpureFunctionsOnceWarnedAndRunsContextOnes() {
        final String body = call("fetch", GROUP1, null) + ", " + call("clock", "", null);
        final Run preview = run(lines(body), "a\nb\n", RunMode.PREVIEW, Services.NONE);
        assertThat(preview.output()).isEqualTo("tick|tick|");
        assertThat(preview.messages()).singleElement().satisfies(m -> {
            assertThat(m.severity()).isEqualTo(Severity.WARNING);
            assertThat(m.text()).isEqualTo("fetch: not run in preview");
        });
        // The whole-buffer form takes the mode too (design 27 phase 5): the same skip, the same word.
        final ByteArrayOutputStream wholeOutput = new ByteArrayOutputStream();
        final List<Message> wholeMessages = Shapeshifter.runWhole(
                Shapeshifter.compile(ProjectReader.read(lines(body)), REGISTRY),
                "a\nb\n".getBytes(StandardCharsets.UTF_8), new XmlByteSink(wholeOutput),
                Instrument.NONE, RunMode.PREVIEW, Services.NONE);
        assertThat(wholeOutput.toString(StandardCharsets.UTF_8)).isEqualTo("tick|tick|");
        assertThat(wholeMessages).singleElement().satisfies(m ->
                assertThat(m.text()).isEqualTo("fetch: not run in preview"));
        final Run normal = run(lines(body), "a\nb\n");
        assertThat(normal.output()).isEqualTo("fetched atick|fetched btick|");
        assertThat(normal.messages()).isEmpty();
    }

    @Test
    void stateIsSharedAcrossCallsWithinARunAndNotBeyondIt() {
        assertThat(run(lines(call("count", "", null)), "a\nb\nc\n").output()).isEqualTo("1|2|3|");
        assertThat(run(lines(call("count", "", null)), "a\n").output()).isEqualTo("1|");
    }

    @Test
    void inputOffsetIsTheRunningMatchsAndServicesAreTheRunsAndBindingIsOncePerRun() {
        assertThat(run(lines(call("where", "", null)), "ab\ncd\n").output()).isEqualTo("0|3|");
        assertThat(run(lines(call("greeting", "", null)), "a\n", RunMode.NORMAL,
                type -> type == String.class ? "hello" : null).output()).isEqualTo("hello|");
        assertThat(run(lines(call("greeting", "", null)), "a\n").output()).isEqualTo("null|");
        final int before = COUNTER.get();
        assertThat(run(lines(call("bound", "", null) + ", " + call("bound", "", null)), "a\nb\n").output())
                .isEqualTo((before + 1) + "" + (before + 1) + "|" + (before + 1) + "" + (before + 1) + "|");
        assertThat(COUNTER.get()).isEqualTo(before + 1);
    }

    /** Phase 1 audit: a definition that cannot be bound is the run's one FATAL, not an exception. */
    @Test
    void definitionThatCannotBeBoundIsAFatalMessageNotAnException() {
        final Run run = run(lines(call("unbindable", "", null)), "a\n");
        assertThat(run.output()).isEqualTo("");
        assertThat(run.messages()).singleElement().satisfies(m -> {
            assertThat(m.severity()).isEqualTo(Severity.FATAL);
            assertThat(m.text()).isEqualTo("unbindable: could not be bound to this run: no such service");
        });
    }

    /** A call to a function of no arguments may leave select out altogether. */
    @Test
    void selectMayBeOmittedForAFunctionOfNoArguments() {
        assertThat(run(lines("{\"call\": {\"function\": \"clock\"}}"), "a\n").output()).isEqualTo("tick|");
    }

    /** Phase 3 needs: the running match's extent and the record number, and a message of any severity. */
    @Test
    void contextReportsTheMatchsExtentTheRecordNumberAndMessagesOfAnySeverity() {
        assertThat(run(lines(call("extent", "", null)), "ab\ncd\n").output()).isEqualTo("1@0+3|2@3+3|");
        // The record number counts under a whole-buffer run too (design 27 phase 2: the count
        // moved to where a top-level record begins, whichever root loop runs it).
        final ByteArrayOutputStream whole = new ByteArrayOutputStream();
        Shapeshifter.runWhole(Shapeshifter.compile(ProjectReader.read(lines(call("extent", "", null))), REGISTRY),
                "ab\ncd\n".getBytes(StandardCharsets.UTF_8), new XmlByteSink(whole));
        assertThat(whole.toString(StandardCharsets.UTF_8)).isEqualTo("1@0+3|2@3+3|");
        final Run said = run(lines(call("say", GROUP1, null)), "a\n");
        assertThat(said.output()).isEqualTo("|");
        assertThat(said.messages()).singleElement().satisfies(m -> {
            assertThat(m.severity()).isEqualTo(Severity.INFO);
            assertThat(m.text()).isEqualTo("say: noted a");
        });
    }

    @Test
    void registryRefusesADuplicateNameAndTheCodecRoundTripsACall() {
        assertThatThrownBy(() -> FunctionRegistry.of(
                FunctionDefinition.of("x", Signature.of(Kind.STRING), Purity.PURE, c -> a -> null),
                FunctionDefinition.of("x", Signature.of(Kind.STRING), Purity.PURE, c -> a -> null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'x' is registered twice");
        final String json = lines(call("nth", GROUP1 + "," + LITERAL_X, "n"));
        final Project project = ProjectReader.read(json);
        assertThat(ProjectReader.read(ProjectReader.writePretty(project))).isEqualTo(project);
        assertThat(ProjectReader.writePretty(project)).contains("\"call\"").contains("\"function\" : \"nth\"");
    }
}
