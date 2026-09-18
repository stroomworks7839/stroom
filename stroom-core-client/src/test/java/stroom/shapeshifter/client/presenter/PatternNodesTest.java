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

import stroom.shapeshifter.config.BinaryCast;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.PatternNode.Choice;
import stroom.shapeshifter.config.PatternNode.Labelled;
import stroom.shapeshifter.config.PatternNode.Optional;
import stroom.shapeshifter.config.PatternNode.Repeat;
import stroom.shapeshifter.config.PatternNode.Sequence;
import stroom.shapeshifter.config.PatternNode.Tag;
import stroom.shapeshifter.config.PatternNode.TakeUntil;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The tree editor's rewrites: every operation returns a tree the reader accepts, labels kept. */
class PatternNodesTest {

    private static final Tag A = new Tag("a");
    private static final Tag B = new Tag("b");
    private static final Tag C = new Tag("c");
    private static final PatternNode ROOT = new Sequence(List.of(
            A,
            new Labelled(new Optional(B), "opt", null),
            new Choice(List.of(C, new TakeUntil(" ", false)))));

    @Test
    void pathsRoundTripAndAddress() {
        assertThat(PatternNodes.path(new int[]{2, 1})).isEqualTo("2.1");
        assertThat(PatternNodes.path("2.1")).containsExactly(2, 1);
        assertThat(PatternNodes.path("")).isEmpty();
        assertThat(PatternNodes.get(ROOT, PatternNodes.path(""))).isSameAs(ROOT);
        assertThat(PatternNodes.get(ROOT, PatternNodes.path("1.0"))).isEqualTo(B);
        assertThat(PatternNodes.get(ROOT, PatternNodes.path("2.1"))).isEqualTo(new TakeUntil(" ", false));
        assertThat(PatternNodes.get(ROOT, PatternNodes.path("0.0"))).isNull();
        assertThat(PatternNodes.get(ROOT, PatternNodes.path("5"))).isNull();
    }

    @Test
    void replaceKeepsTheLabelAroundARewrittenBody() {
        final PatternNode next = PatternNodes.replace(ROOT, PatternNodes.path("1.0"), C);
        assertThat(PatternNodes.get(next, PatternNodes.path("1")))
                .isEqualTo(new Labelled(new Optional(C), "opt", null));
        assertThat(PatternNodes.get(next, PatternNodes.path("0"))).isEqualTo(A);
        assertThat(ROOT).as("the original is untouched").isEqualTo(ROOT);
    }

    @Test
    void removeInsertAndMoveAmongSiblings() {
        PatternNode next = PatternNodes.remove(ROOT, PatternNodes.path("0"));
        assertThat(PatternNodes.children(next)).hasSize(2);
        next = PatternNodes.insert(next, PatternNodes.path(""), 0, A);
        assertThat(next).isEqualTo(ROOT);
        next = PatternNodes.move(next, PatternNodes.path("0"), 2);
        assertThat(PatternNodes.children(next).get(2)).isEqualTo(A);
        assertThat(PatternNodes.move(ROOT, PatternNodes.path("0"), -1)).isSameAs(ROOT);
        assertThat(PatternNodes.move(ROOT, PatternNodes.path(""), 1)).isSameAs(ROOT);
        assertThat(PatternNodes.remove(ROOT, PatternNodes.path(""))).isNull();
    }

    @Test
    void singleBodyContainerGrowsASequenceAndNeverGoesEmpty() {
        // Inserting beside an optional's body makes the body a sequence.
        PatternNode next = PatternNodes.insert(ROOT, PatternNodes.path("1"), 1, C);
        assertThat(PatternNodes.get(next, PatternNodes.path("1")))
                .isEqualTo(new Labelled(new Optional(new Sequence(List.of(B, C))), "opt", null));
        // Removing an optional's only body leaves the placeholder, not a null body.
        next = PatternNodes.remove(ROOT, PatternNodes.path("1.0"));
        assertThat(PatternNodes.get(next, PatternNodes.path("1")))
                .isEqualTo(new Labelled(new Optional(PatternNodes.PLACEHOLDER), "opt", null));
        // A repeat keeps its bounds through a rewrite of its body.
        final PatternNode repeat = new Repeat(A, 2, 5, false);
        assertThat(PatternNodes.withChildren(repeat, List.of(B))).isEqualTo(new Repeat(B, 2, 5, false));
        assertThatThrownBy(() -> PatternNodes.insert(ROOT, PatternNodes.path("0"), 0, B))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unwrapReplacesAContainerByItsOneChild() {
        final PatternNode next = PatternNodes.unwrap(ROOT, PatternNodes.path("1"));
        assertThat(PatternNodes.get(next, PatternNodes.path("1"))).isEqualTo(B);
        assertThatThrownBy(() -> PatternNodes.unwrap(ROOT, PatternNodes.path("2")))
                .as("a choice of two cannot be unwrapped")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PatternNodes.unwrap(ROOT, PatternNodes.path("0")))
                .as("a leaf cannot be unwrapped")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void kindsAreClassified() {
        assertThat(PatternNodes.isList(ROOT)).isTrue();
        assertThat(PatternNodes.isContainer(new Labelled(new Optional(A), "x", BinaryCast.UINT8))).isTrue();
        assertThat(PatternNodes.isList(new Labelled(new Optional(A), "x", null))).isFalse();
        assertThat(PatternNodes.isContainer(A)).isFalse();
        assertThat(PatternNodes.bare(new Labelled(A, "x", null))).isEqualTo(A);
    }
}
