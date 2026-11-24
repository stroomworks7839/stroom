/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.client.model;

import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.RegexFlags;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 49: what an undo shows, and what typing is joined by.
class TestProjectChange {

    private static final String SETTINGS = "settings:";
    private static final Template ONE = template("00000000-0000-0000-0000-000000000001", "a");
    private static final Template TWO = template("00000000-0000-0000-0000-000000000002", "b");
    private static final Project BOTH = project("p", List.of(ONE, TWO), Map.of());

    @Test
    void nothingDifferentIsNothingToShow() {
        assertThat(row(BOTH, BOTH)).isNull();
    }

    @Test
    void oneTemplateNewOrDifferentIsShown() {
        assertThat(row(project("p", List.of(ONE), Map.of()), BOTH)).isEqualTo(TWO.id());
        assertThat(row(BOTH, project("p", List.of(ONE, body(TWO)), Map.of()))).isEqualTo(TWO.id());
    }

    @Test
    void whatIsGoneOrOnlyMovedIsNot() {
        assertThat(row(BOTH, project("p", List.of(ONE), Map.of()))).isNull();
        assertThat(row(BOTH, project("p", List.of(TWO, ONE), Map.of()))).isNull();
    }

    @Test
    void moreThanOneIsNotOneThing() {
        assertThat(row(BOTH, project("p", List.of(body(ONE), body(TWO)), Map.of()))).isNull();
    }

    @Test
    void aPartIsShownByItsRow() {
        assertThat(row(BOTH, project("p", List.of(ONE, TWO), Map.of("ip", new PatternNode.Tag("x")))))
                .isEqualTo(Patterns.rowId("ip"));
    }

    @Test
    void theSettingsAreShownByTheirs() {
        assertThat(row(BOTH, project("q", List.of(ONE, TWO), Map.of()))).isEqualTo(SETTINGS);
    }

    @Test
    void aPatternTypedIsItsFields() {
        final Project typed = project("p", List.of(ONE, matching(TWO, regex("bc"))), Map.of());
        assertThat(ProjectChange.typedInto(BOTH, typed)).isEqualTo(ProjectChange.typedInto(typed, BOTH)).isNotNull();
        assertThat(ProjectChange.typedInto(BOTH, project("p", List.of(matching(ONE, regex("ab")), TWO), Map.of())))
                .isNotEqualTo(ProjectChange.typedInto(BOTH, typed));
    }

    @Test
    void anyOtherEditIsAStepOfItsOwn() {
        assertThat(ProjectChange.typedInto(BOTH, BOTH)).isNull();
        assertThat(ProjectChange.typedInto(BOTH, project("p", List.of(ONE, body(TWO)), Map.of()))).isNull();
        // Its flags changed with it, another kind of match, and the body changed as well
        assertThat(ProjectChange.typedInto(BOTH, project("p", List.of(ONE, matching(TWO,
                new MatchExpression.Regex("bc", new RegexFlags(true, false), 0))), Map.of()))).isNull();
        assertThat(ProjectChange.typedInto(BOTH, project("p", List.of(ONE, matching(TWO,
                new MatchExpression.All())), Map.of()))).isNull();
        assertThat(ProjectChange.typedInto(BOTH, project("p", List.of(ONE, body(matching(TWO, regex("bc")))),
                Map.of()))).isNull();
        // A template that was not there
        assertThat(ProjectChange.typedInto(project("p", List.of(ONE), Map.of()), BOTH)).isNull();
    }

    private static String row(final Project before, final Project after) {
        return ProjectChange.row(before, after, SETTINGS);
    }

    private static Project project(final String name, final List<Template> templates,
                                   final Map<String, PatternNode> patterns) {
        return new Project(name, 5, null, templates, patterns);
    }

    private static MatchExpression.Regex regex(final String pattern) {
        return new MatchExpression.Regex(pattern, null, 0);
    }

    private static Template template(final String id, final String pattern) {
        return new Template(id, "t", null, false, null, null, null, regex(pattern), null, null, List.of(), null, false);
    }

    private static Template matching(final Template template, final MatchExpression match) {
        return Templates.withMatch(template, match);
    }

    private static Template body(final Template template) {
        return Templates.withBody(template, List.of(new OutputNode.Text("x")));
    }
}
