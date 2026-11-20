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

import stroom.shapeshifter.config.BinaryCast;
import stroom.shapeshifter.config.MatchExpression.Length;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.PatternNode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// The labels a framed sequence's part can take its length from (design 47 §7.19)
class TestPartLabels {

    // Rung 6: a varint read as len, then a take as long as it, then another take
    private static final List<MatchPart> PARTS = List.of(
            new MatchPart.Read(BinaryCast.VARINT, "len"),
            new MatchPart.Take(new Length.Label("len"), "name"),
            new MatchPart.Take(new Length.Literal(1), null));

    @Test
    void aPartIsOfferedTheLabelsThePartsBeforeItDefine() {
        assertThat(PartLabels.before(PARTS, 0)).isEmpty();
        assertThat(PartLabels.before(PARTS, 1)).containsExactly("len");
        // A part added at the end is after all of them
        assertThat(PartLabels.before(PARTS, PARTS.size())).containsExactly("len", "name");
    }

    @Test
    void aPatternPartsLabelsAreOfferedTooAsTheEngineBindsThem() {
        // A netstring: digits labelled len, then a colon, then the take
        final List<MatchPart> netstring = List.of(
                new MatchPart.Pattern(new PatternNode.Sequence(List.of(
                        new PatternNode.Labelled(new PatternNode.TakeWhile("[0-9]", 1, PatternNode.Repeat.UNBOUNDED),
                                "len", null),
                        new PatternNode.Tag(":")))),
                new MatchPart.Take(new Length.Label("len"), "body"));
        assertThat(PartLabels.before(netstring, 1)).containsExactly("len");
        // but it is not sure to be an integer, so it is listed and not filled in
        assertThat(PartLabels.integersBefore(netstring, 1)).isEmpty();
    }

    @Test
    void onlyAReadOfAnIntegerIsFilledIn() {
        final List<MatchPart> parts = List.of(
                new MatchPart.Take(new Length.Literal(4), "magic"),
                new MatchPart.Read(BinaryCast.FLOAT32LE, "ratio"),
                new MatchPart.Read(BinaryCast.UINT16BE, "size"));
        assertThat(PartLabels.integersBefore(parts, 3)).containsExactly("size");
        assertThat(PartLabels.before(parts, 3)).containsExactly("magic", "ratio", "size");
    }

    @Test
    void theOneIntegerFillsALengthThatHoldsNoLabelYet() {
        final List<String> one = List.of("len");
        // What a new part starts with, or nothing
        assertThat(PartLabels.filled("1", one)).isEqualTo("len");
        assertThat(PartLabels.filled("", one)).isEqualTo("len");
        // A count or a name the author typed is theirs
        assertThat(PartLabels.filled("16", one)).isEqualTo("16");
        assertThat(PartLabels.filled("size", one)).isEqualTo("size");
        // With two to choose from, or none, nothing is chosen for them
        assertThat(PartLabels.filled("1", List.of("len", "name"))).isEqualTo("1");
        assertThat(PartLabels.filled("1", List.of())).isEqualTo("1");
    }

    @Test
    void theHintNamesTheLabelsOrSaysThereAreNone() {
        assertThat(PartLabels.hint(List.of("len", "name"))).endsWith("len, name");
        assertThat(PartLabels.hint(List.of())).startsWith("No part before this one");
    }
}
