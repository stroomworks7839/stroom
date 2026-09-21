/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The library's edits are rewrites of the project: a rename follows every ref, extract and inline are inverses. */
class PatternsTest {

    private static final PatternNode KEY = new PatternNode.TakeWhile("[a-z]", 1, PatternNode.Repeat.UNBOUNDED);
    private static final PatternNode PAIR = new PatternNode.Sequence(List.of(
            new PatternNode.Labelled(new PatternNode.Ref("KEY"), "k", null), new PatternNode.Tag("=")));

    private static Template template(final String name, final MatchExpression match) {
        return Templates.withMatch(Templates.create(name, null, false), match);
    }

    private static final Project PROJECT = new Project("p", 5, null, List.of(
            template("tree", new MatchExpression.Pattern(new PatternNode.Sequence(List.of(
                    new PatternNode.Ref("PAIR"), new PatternNode.Tag(";"))))),
            template("parts", new MatchExpression.Parts(List.of(
                    new MatchPart.Pattern(new PatternNode.Labelled(new PatternNode.Ref("KEY"), "only", null))))),
            template("regex", new MatchExpression.Regex("x", null, 0))),
            library());

    private static Map<String, PatternNode> library() {
        final Map<String, PatternNode> patterns = new LinkedHashMap<>();
        patterns.put("KEY", KEY);
        patterns.put("PAIR", PAIR);
        return patterns;
    }

    @Test
    void usesCountTemplatesAndOtherPartsNamingAPart() {
        assertThat(Patterns.uses(PROJECT, "KEY")).as("the parts template, and PAIR").isEqualTo(2);
        assertThat(Patterns.uses(PROJECT, "PAIR")).as("the tree template").isEqualTo(1);
        assertThat(Patterns.uses(PROJECT, "NOWHERE")).isZero();
    }

    @Test
    void renameFollowsEveryRefInTemplatesAndParts() {
        final Project renamed = Patterns.rename(PROJECT, "KEY", "NAME");
        assertThat(renamed.patterns().keySet()).containsExactly("NAME", "PAIR");
        assertThat(Patterns.refers(renamed.patterns().get("PAIR"), "NAME")).isTrue();
        assertThat(Patterns.refers(renamed.patterns().get("PAIR"), "KEY")).isFalse();
        assertThat(Patterns.uses(renamed, "NAME")).isEqualTo(2);
        assertThat(Patterns.uses(renamed, "KEY")).isZero();
        assertThat(((MatchExpression.Parts) renamed.templates().get(1).match()).parts().get(0))
                .isEqualTo(new MatchPart.Pattern(new PatternNode.Labelled(new PatternNode.Ref("NAME"), "only", null)));
        assertThat(renamed.templates().get(2).match()).as("a regex has no refs")
                .isEqualTo(new MatchExpression.Regex("x", null, 0));
    }

    @Test
    void defineAndRemoveKeepTheOrder() {
        final Project more = Patterns.define(PROJECT, "NUM", new PatternNode.Ref("digits"));
        assertThat(more.patterns().keySet()).containsExactly("KEY", "PAIR", "NUM");
        assertThat(Patterns.define(more, "KEY", new PatternNode.Tag("k")).patterns().keySet())
                .as("redefining keeps the place").containsExactly("KEY", "PAIR", "NUM");
        assertThat(Patterns.remove(more, "PAIR").patterns().keySet()).containsExactly("KEY", "NUM");
    }

    @Test
    void extractLeavesARefAndKeepsTheTreesLabelAndInlineUndoesIt() {
        final PatternNode tree = new PatternNode.Sequence(List.of(
                new PatternNode.Labelled(KEY, "k", null), new PatternNode.Tag("=")));
        final PatternNode extracted = Patterns.extract(tree, new int[]{0}, "KEY");
        assertThat(extracted).isEqualTo(new PatternNode.Sequence(List.of(
                new PatternNode.Labelled(new PatternNode.Ref("KEY"), "k", null), new PatternNode.Tag("="))));
        assertThat(Patterns.part(PatternNodes.get(tree, new int[]{0}))).as("the label stays with the tree")
                .isEqualTo(KEY);
        assertThat(Patterns.inline(extracted, new int[]{0}, Map.of("KEY", KEY))).isEqualTo(tree);
        assertThat(Patterns.inline(extracted, new int[]{1}, Map.of("KEY", KEY))).as("not a ref: unchanged")
                .isSameAs(extracted);
        assertThat(Patterns.inline(extracted, new int[]{0}, Map.of())).as("a standard-library ref stays")
                .isSameAs(extracted);
    }

    @Test
    void rowIdsAreDistinctFromTemplateIds() {
        assertThat(Patterns.nameOf(Patterns.rowId("KEY"))).isEqualTo("KEY");
        assertThat(Patterns.nameOf(Templates.create("t", null, false).id())).isNull();
        assertThat(Patterns.nameOf(null)).isNull();
    }
}
