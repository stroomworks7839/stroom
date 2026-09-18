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

import stroom.shapeshifter.config.BinaryCast;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Template.RegexFlags;
import stroom.shapeshifter.engine.PatternExplode;
import stroom.shapeshifter.engine.PatternPrint;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The forms a tree prints as, for the nodes no explode makes — {@code PatternExplodeTest}
 * covers the exploded ones over the whole corpus — and the grouping rules that keep a printed
 * tree meaning what the tree meant.
 */
class PatternPrintTest {

    private static final RegexFlags NONE = RegexFlags.none();

    private static String print(final PatternNode node) {
        return PatternPrint.print(node, NONE);
    }

    @Test
    void theTreeOnlyNodesPrintAsTheCompilerLowersThem() {
        assertThat(print(new PatternNode.Take(4))).isEqualTo("[\\s\\S]{4}");
        assertThat(print(new PatternNode.TakeUntil(",", false))).isEqualTo("[^,]*");
        assertThat(print(new PatternNode.TakeUntil(",", true))).isEqualTo("[^,]*,");
        assertThat(print(new PatternNode.TakeUntil("]", true))).isEqualTo("[^\\x{5D}]*\\]");
        assertThat(print(new PatternNode.TakeUntil(" pid=", false))).isEqualTo("(?s:.*?)(?= pid=)");
        assertThat(print(new PatternNode.TakeUntil("\n\n", true))).isEqualTo("(?s:.*?)\\n\\n");
        assertThat(print(new PatternNode.Ref("digits"))).isEqualTo("[0-9]+");
        assertThat(print(new PatternNode.Ref("ipv4"))).isEqualTo("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");
        assertThatThrownBy(() -> print(new PatternNode.Ref("nope")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
    }

    @Test
    void tagsAreQuotedAndFlagsAreScopedToTheLeaf() {
        assertThat(print(new PatternNode.Tag("a.b(c)\t"))).isEqualTo("a\\.b\\(c\\)\\t");
        assertThat(print(new PatternNode.Regex("ab", new RegexFlags(true, false)))).isEqualTo("(?i:ab)");
        assertThat(PatternPrint.print(new PatternNode.Regex("ab", NONE), new RegexFlags(true, true)))
                .isEqualTo("(?-is:ab)");
        assertThat(PatternPrint.print(new PatternNode.Regex("ab", new RegexFlags(true, true)),
                new RegexFlags(true, true))).isEqualTo("ab");
    }

    @Test
    void groupsAppearOnlyWhereTheMeaningNeedsThem() {
        final PatternNode ab = new PatternNode.Choice(List.of(new PatternNode.Tag("a"), new PatternNode.Tag("b")));
        assertThat(print(ab)).isEqualTo("a|b");
        assertThat(print(new PatternNode.Sequence(List.of(ab, new PatternNode.Tag("c"))))).isEqualTo("(?:a|b)c");
        assertThat(print(new PatternNode.Repeat(ab, 2, PatternNode.Repeat.UNBOUNDED, false))).isEqualTo("(?:a|b){2,}?");
        assertThat(print(new PatternNode.Optional(new PatternNode.Tag("ab")))).isEqualTo("(?:ab)?");
        assertThat(print(new PatternNode.Optional(new PatternNode.Tag("a")))).isEqualTo("a?");
        assertThat(print(new PatternNode.Repeat(new PatternNode.TakeWhile("[a-z]", 1, 1), 0, 3, true)))
                .isEqualTo("[a-z]{0,3}");
        assertThat(print(new PatternNode.Peek(ab))).isEqualTo("(?=a|b)");
        assertThat(print(new PatternNode.Not(new PatternNode.Tag("x")))).isEqualTo("(?!x)");
    }

    @Test
    void labelsAreNamedGroupsAndNumberedOnesUnnamedAndACastIsDropped() {
        final PatternNode labelled = new PatternNode.Sequence(List.of(
                new PatternNode.Labelled(
                        new PatternNode.TakeWhile("[0-9]", 1, PatternNode.Repeat.UNBOUNDED), "_1", null),
                new PatternNode.Tag(":"),
                new PatternNode.Labelled(new PatternNode.Take(2), "port", BinaryCast.UINT16LE)));
        assertThat(print(labelled)).isEqualTo("([0-9]+):(?<port>[\\s\\S]{2})");
    }

    /** What the editor does on every keystroke: the exploded tree of a regex, printed, is a regex. */
    @Test
    void anExplodedTreePrintsAsARegexAgain() {
        final String regex = "^(?<level>ERROR|WARN|INFO) +(?<msg>.*?)(?=\\n|$)";
        final PatternNode tree = PatternExplode.explode(regex, new RegexFlags(false, true));
        assertThat(print(tree)).isEqualTo("^(?<level>ERROR|WARN|INFO) +(?<msg>[\\s\\S]*?)(?=\\n|$)");
    }
}
