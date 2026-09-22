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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The match kind picker's arithmetic (design 44 §1): every kind can be arrived at, the three
 * pattern kinds carry their pattern between them, and only a sequence of several parts has
 * something a single pattern cannot hold.
 */
class MatchKindTest {

    @Test
    void everyKindHasABlankToArriveAt() {
        for (final MatchKind kind : MatchKind.values()) {
            assertThat(MatchKind.of(Templates.blank(kind))).as(kind.label()).isEqualTo(kind);
        }
        assertThat(Templates.blank(MatchKind.DELIMITER))
                .as("a delimiter needs one, and a line is the usual")
                .isEqualTo(new MatchExpression.Delimiter("\n", null, null, null));
    }

    @Test
    void theKindsWithoutAPatternAreLeftBehindWhenOneIsChosen() {
        // Source, all and named hold nothing to carry, so switching to a pattern kind starts
        // blank rather than being refused - which is what switching away from one already did.
        for (final MatchKind kind : new MatchKind[]{MatchKind.SOURCE, MatchKind.ALL, MatchKind.NAMED,
                MatchKind.DELIMITER}) {
            final MatchExpression match = Templates.blank(kind);
            assertThat(Templates.singlePattern(match)).as(kind.label()).isNull();
            assertThat(Templates.holdsPattern(match)).as(kind.label()).isFalse();
            assertThat(kind.holdsPattern()).isFalse();
        }
    }

    @Test
    void thePatternKindsCarryTheirPatternBetweenThem() {
        final PatternNode node = new PatternNode.Tag("x");
        assertThat(Templates.singlePattern(new MatchExpression.Pattern(node))).isEqualTo(node);
        assertThat(Templates.singlePattern(new MatchExpression.Parts(List.of(new MatchPart.Pattern(node)))))
                .isEqualTo(node);
        assertThat(Templates.singlePattern(new MatchExpression.Regex("a+", null, 0)))
                .as("a regex is a leaf of the tree vocabulary")
                .isEqualTo(new PatternNode.Regex("a+", null));
        for (final MatchKind kind : new MatchKind[]{MatchKind.REGEX, MatchKind.TREE, MatchKind.PARTS}) {
            assertThat(Templates.holdsPattern(Templates.blank(kind))).as(kind.label()).isTrue();
            assertThat(kind.holdsPattern()).isTrue();
            assertThat(Templates.singlePattern(Templates.blank(kind))).as(kind.label()).isNotNull();
        }
    }

    @Test
    void severalPartsHoldAPatternThatIsNotOne() {
        // The one refusal left: takes, seeks and reads beside a pattern are not a single pattern.
        final MatchExpression parts = new MatchExpression.Parts(List.of(
                new MatchPart.Pattern(new PatternNode.Tag("#")),
                new MatchPart.Take(new MatchExpression.Length.Literal(2), "code")));
        assertThat(Templates.holdsPattern(parts)).isTrue();
        assertThat(Templates.singlePattern(parts)).isNull();
    }
}
