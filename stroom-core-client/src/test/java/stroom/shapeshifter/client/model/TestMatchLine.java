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
import stroom.shapeshifter.config.Template.RegexFlags;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// The Match line's reading of a match and its writing of one (design 47 G)
class TestMatchLine {

    private static final MatchExpression.Delimiter NEWLINE = new MatchExpression.Delimiter("\n", null, null, null);

    @Test
    void aRegexOpensAsItsPatternWithADelimiterReadyBehindIt() {
        final MatchLine line = MatchLine.of(new MatchExpression.Regex("(a)=(b)", null, 0));

        assertThat(line.kind()).isEqualTo(MatchKind.REGEX);
        assertThat(line.pattern()).isEqualTo("(a)=(b)");
        // A delimiter chosen on the line starts as one does in the workbench: on a newline
        assertThat(line.delimiter()).isEqualTo("\\n");
        assertThat(line.containerStart()).isEmpty();
    }

    @Test
    void aDelimiterOpensAsItsFieldsSpeltAsTheyAreTyped() {
        final MatchLine line = MatchLine.of(new MatchExpression.Delimiter("\t", "\\", "\"", "\""));

        assertThat(line.kind()).isEqualTo(MatchKind.DELIMITER);
        assertThat(line.delimiter()).isEqualTo("\\t");
        assertThat(line.containerStart()).isEqualTo("\"");
        assertThat(line.containerEnd()).isEqualTo("\"");
        assertThat(line.pattern()).isEmpty();
    }

    @Test
    void aMatchWithNoOneLineSpellingIsTheWorkbenchs() {
        assertThat(MatchLine.of(new MatchExpression.Source())).isNull();
        assertThat(MatchLine.of(new MatchExpression.All())).isNull();
        assertThat(MatchLine.of(Templates.blank(MatchKind.TREE))).isNull();
    }

    @Test
    void aRegexKeepsTheFlagsAndAdvanceTheWorkbenchGaveIt() {
        final MatchExpression.Regex was = new MatchExpression.Regex("a", new RegexFlags(true, false), 2);
        final MatchLine line = MatchLine.of(was).withPattern("b");

        assertThat(line.toMatch(was)).isEqualTo(new MatchExpression.Regex("b", new RegexFlags(true, false), 2));
        // Made from a delimiter, it has none
        assertThat(line.toMatch(NEWLINE)).isEqualTo(new MatchExpression.Regex("b", null, 0));
    }

    @Test
    void aDelimiterIsWrittenFromItsSpellingAndKeepsItsEscape() {
        final MatchExpression.Delimiter was = new MatchExpression.Delimiter("|", "\\", null, null);
        final MatchLine line = new MatchLine(MatchKind.DELIMITER, "", "\\t", "\"", "\"");

        assertThat(line.toMatch(was)).isEqualTo(new MatchExpression.Delimiter("\t", "\\", "\"", "\""));
        // A container left empty is none
        assertThat(new MatchLine(MatchKind.DELIMITER, "", ",", "", "").toMatch(was))
                .isEqualTo(new MatchExpression.Delimiter(",", "\\", null, null));
        // Made from a regex, there is no escape to keep
        assertThat(line.toMatch(new MatchExpression.Regex("a", null, 0)))
                .isEqualTo(new MatchExpression.Delimiter("\t", null, "\"", "\""));
    }

    @Test
    void aDelimiterWithNothingToSplitOnIsNotAMatch() {
        assertThat(new MatchLine(MatchKind.DELIMITER, "", "", "", "").toMatch(NEWLINE)).isNull();
    }

    @Test
    void onlyAKindChangeThatThrowsAwayWorkIsAskedAbout() {
        final MatchExpression.Regex written = new MatchExpression.Regex("(a)", null, 0);
        final MatchExpression.Regex empty = new MatchExpression.Regex("", null, 0);
        final MatchLine toDelimiter = new MatchLine(MatchKind.DELIMITER, "", "|", "", "");
        final MatchLine toRegex = new MatchLine(MatchKind.REGEX, "x", "\\n", "", "");

        assertThat(toDelimiter.replacesWork(written)).isTrue();
        // A template just made has an empty regex: nothing is lost
        assertThat(toDelimiter.replacesWork(empty)).isFalse();
        // Nor is anything lost by staying the same kind, whatever changes
        assertThat(toRegex.replacesWork(written)).isFalse();
        assertThat(toRegex.replacesWork(NEWLINE)).isFalse();
        assertThat(toRegex.replacesWork(new MatchExpression.Delimiter("|", null, null, null))).isTrue();
    }

    @Test
    void theLineSpellsARegexAndADelimiterOnly() {
        assertThat(MatchLine.spells(NEWLINE)).isTrue();
        assertThat(MatchLine.spells(new MatchExpression.Regex("a", null, 0))).isTrue();
        assertThat(MatchLine.spells(new MatchExpression.Source())).isFalse();
        assertThat(MatchLine.spells(Templates.blank(MatchKind.PARTS))).isFalse();
    }

    @Test
    void theWorkbenchAndTheLineBuildADelimiterAlike() {
        // One builder for both, so the two cannot come to disagree
        assertThat(MatchLine.delimiter("\\t", "\\\\", "", ""))
                .isEqualTo(new MatchExpression.Delimiter("\t", "\\", null, null));
        assertThat(MatchLine.delimiter("", "", "\"", "\"")).isNull();
    }

    @Test
    void aLineWithNoKindChosenIsTheKindItWas() {
        final MatchLine line = MatchLine.of(NEWLINE);

        assertThat(line.withKind(null)).isEqualTo(line);
        assertThat(line.withKind(MatchKind.REGEX).kind()).isEqualTo(MatchKind.REGEX);
    }
}
