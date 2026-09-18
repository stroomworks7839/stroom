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

import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.RegexFlags;
import stroom.shapeshifter.engine.PatternExplode;
import stroom.shapeshifter.engine.PatternPrint;
import stroom.shapeshifter.engine.ProjectReader;
import stroom.shapeshifter.engine.fixture.FixtureLedger;
import stroom.shapeshifter.engine.fixture.FixtureLedger.Fixture;
import stroom.shapeshifter.engine.match.PatternKey;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.text.RegexEncodings;
import stroom.shapeshifter.regex.BytePattern;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 38 §3a's gate: every regex in the fixture corpus, exploded into the tree and compiled
 * back, has the plan of the regex itself — so a configuration brought into the UI as a tree
 * runs exactly as it ran as a regex. The tree also survives the wire format, since it is what
 * the configuration stores.
 */
class PatternExplodeTest {

    /** A regex the corpus holds, with the flags and the encoding it compiles under. */
    private record Found(String where, String pattern, RegexFlags flags, Encoding encoding) {

    }

    @TestFactory
    List<DynamicTest> everyCorpusRegexExplodesToItsOwnPlan() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Fixture fixture : FixtureLedger.all()) {
            if (fixture.status() == FixtureLedger.Status.SKIPPED) {
                continue;
            }
            final String path = switch (fixture.family()) {
                case NATIVE -> "native/" + fixture.name() + "/project.json";
                case PROJECTS -> "projects/" + fixture.name() + "/project.json";
                default -> null;
            };
            if (path == null) {
                continue;
            }
            final Project project = ProjectReader.read(FixtureLedger.bytes(path));
            final Map<String, Found> found = new LinkedHashMap<>();
            // The reading a template's match compiles under, as the compiler settles it: the
            // source's declaration, auto read as UTF-8, a transcoded family as UTF-8, and a
            // template's own declaration over the source's.
            final Encoding declared = Encoding.fromLabel(project.source().encoding());
            final Encoding source = declared == null || declared == Encoding.AUTO ? Encoding.UTF_8 : declared;
            for (final Template template : project.templates()) {
                final Encoding own = Encoding.fromLabel(template.encoding());
                final Encoding match = RegexEncodings.needsTranscode(source) || own == null ? source : own;
                collect(template, fixture.name() + "/" + template.name(), match, found);
            }
            for (final Found regex : found.values()) {
                tests.add(DynamicTest.dynamicTest(regex.where() + ": " + regex.pattern(), () -> check(regex)));
            }
        }
        assertThat(tests).as("the corpus must hold regexes to check").hasSizeGreaterThan(100);
        return tests;
    }

    /**
     * Walks a template's records. A template's own match regex compiles under the template's
     * encoding; a body's replace and a condition's matches run over resolved values, UTF-8.
     */
    private static void collect(final Object value, final String where, final Encoding encoding,
                                final Map<String, Found> found) {
        if (value == null) {
            return;
        }
        switch (value) {
            case final MatchExpression.Regex regex ->
                    found.put(where + " match " + regex.pattern(),
                            new Found(where, regex.pattern(), regex.flags(), encoding));
            case final PatternNode.Regex regex ->
                    found.put(where + " leaf " + regex.pattern(), new Found(where, regex.pattern(),
                            regex.flags() == null ? RegexFlags.none() : regex.flags(), encoding));
            case final Condition.Matches matches ->
                    found.put(where + " matches " + matches.pattern(),
                            new Found(where, matches.pattern(), RegexFlags.none(), Encoding.UTF_8));
            case final OutputNode.Replace replace -> {
                if (replace.isRegex()) {
                    found.put(where + " replace " + replace.pattern(),
                            new Found(where, replace.pattern(), RegexFlags.none(), Encoding.UTF_8));
                }
            }
            default -> {
            }
        }
        if (value instanceof final Collection<?> items) {
            items.forEach(item -> collect(item, where, encoding, found));
        } else if (value.getClass().isRecord()
                   && value.getClass().getPackageName().startsWith("stroom.shapeshifter.config")) {
            for (final RecordComponent component : value.getClass().getRecordComponents()) {
                try {
                    collect(component.getAccessor().invoke(value), where, encoding, found);
                } catch (final ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static void check(final Found regex) {
        final BytePattern written = BytePattern.compile(regex.pattern(), PatternKey.flags(regex.flags()),
                RegexEncodings.forMatch(regex.encoding()));
        final PatternNode tree = PatternExplode.explode(regex.pattern(), regex.flags());
        final BytePattern exploded = PatternCompiler.compile(tree, regex.encoding(), regex.where()).pattern();
        assertThat(planOf(exploded)).as("%s explodes as %s", regex.pattern(), tree).isEqualTo(planOf(written));
        assertThat(exploded.groupCount()).isEqualTo(written.groupCount());
        // And back: the tree printed as a regex (design 43 §5) is the same plan again, with the
        // same groups under the same numbers and names.
        final String printed = PatternPrint.print(tree, regex.flags());
        final BytePattern reprinted = BytePattern.compile(printed, PatternKey.flags(regex.flags()),
                RegexEncodings.forMatch(regex.encoding()));
        assertThat(planOf(reprinted)).as("%s prints as %s", regex.pattern(), printed).isEqualTo(planOf(written));
        assertThat(reprinted.groupCount()).isEqualTo(written.groupCount());
        assertThat(reprinted.groupNames()).isEqualTo(written.groupNames());
        for (int group = 1; group <= written.groupCount(); group++) {
            final String name = written.groupNames().get(group);
            if (name != null) {
                assertThat(exploded.groupIndex(name)).as("group '%s' keeps its number", name).isEqualTo(group);
            }
        }
    }

    /**
     * The plan from the tier line down, with a character class's label — the text it was
     * written as, which the explode rewrites to the set it means — taken out, so the comparison
     * is of instructions and sets.
     */
    private static String planOf(final BytePattern pattern) {
        final String explained = pattern.explain();
        return explained.substring(explained.indexOf("tier:"))
                .replaceAll("(MATCH_CHAR|SCAN_WHILE_CHAR) +\\S+ \\(", "$1 (");
    }

    /**
     * An exclusive take-until of a multi-character terminator followed by its tag lowers as a
     * lazy run and the literal, with no lookahead — the lookahead is what sends a pattern to
     * the backtracking tier (design 38 §8). The meaning is the same either way; the tier is not.
     */
    private static final PatternNode PID = new PatternNode.Labelled(
            new PatternNode.TakeWhile("[0-9]", 1, PatternNode.Repeat.UNBOUNDED), "pid", null);

    @Test
    void takeUntilFollowedByItsTagLowersWithoutTheLookahead() {
        final PatternNode fused = new PatternNode.Sequence(List.of(
                new PatternNode.Labelled(new PatternNode.TakeUntil(" pid=", false), "msg", null),
                new PatternNode.Tag(" pid="),
                PID));
        final PatternNode unfused = new PatternNode.Sequence(List.of(
                new PatternNode.Labelled(new PatternNode.TakeUntil(" pid=", false), "msg", null),
                new PatternNode.Tag(" pid"),
                new PatternNode.Tag("="),
                PID));
        final BytePattern fast = PatternCompiler.compile(fused, Encoding.UTF_8, "t").pattern();
        final BytePattern slow = PatternCompiler.compile(unfused, Encoding.UTF_8, "t").pattern();
        assertThat(fast.tier()).as("no lookahead, no backtracker").isLessThan(slow.tier());
        assertThat(fast.pattern()).doesNotContain("(?=");
        final byte[] input = "hello world pid=42".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (final BytePattern pattern : List.of(fast, slow)) {
            final var matcher = pattern.matcher();
            assertThat(matcher.match(input, 0, input.length, stroom.shapeshifter.regex.Anchoring.ANCHORED)).isTrue();
            assertThat(matcher.group("msg").toString()).isEqualTo("hello world");
            assertThat(matcher.group("pid").toString()).isEqualTo("42");
        }
    }

    /** The tree an explode makes is the tree the configuration stores: it survives the wire format. */
    @Test
    void anExplodedTreeSurvivesTheWireFormat() {
        final PatternNode tree = PatternExplode.explode(
                "^(?<level>ERROR|WARN|INFO) +(?<msg>.*?)(?=\\n|$)", new RegexFlags(false, true));
        final String json = ProjectReader.write(new Project("t", 5, Project.SourceConfig.defaults(), List.of(
                new Template(java.util.UUID.randomUUID().toString(), "t", null, false, null, List.of(), List.of(),
                        new MatchExpression.Pattern(tree), Template.MatchLimits.unlimited(), List.of(), List.of(),
                        null, false))));
        assertThat(ProjectReader.read(json).templates().getFirst().match())
                .isEqualTo(new MatchExpression.Pattern(tree));
        assertThat(tree).isInstanceOf(PatternNode.Sequence.class);
        assertThat(((PatternNode.Sequence) tree).items().get(1)).isInstanceOf(PatternNode.Labelled.class);
    }
}
