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

package stroom.shapeshifter.client.model;

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
    private static final PatternNode ROOT_ITEM_1 = new Labelled(new Optional(B), "opt", null);

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

    @Test
    void everyNodeButTheRootIsLabelledForTheTrial() {
        final PatternNode labelled = PatternNodes.labelEvery(ROOT);
        // The root is the match itself, and a node the author labelled keeps that label
        assertThat(labelled).isInstanceOf(Sequence.class);
        assertThat(PatternNodes.get(labelled, new int[]{0}))
                .isEqualTo(new Labelled(A, PatternNodes.trialLabel(new int[]{0}), null));
        assertThat(((Labelled) PatternNodes.get(labelled, new int[]{1})).label()).isEqualTo("opt");
        // ...and what it holds is labelled too, at its own path
        assertThat(PatternNodes.get(labelled, new int[]{1, 0}))
                .isEqualTo(new Labelled(B, PatternNodes.trialLabel(new int[]{1, 0}), null));
        assertThat(PatternNodes.get(labelled, new int[]{2, 1}))
                .isEqualTo(new Labelled(new TakeUntil(" ", false), PatternNodes.trialLabel(new int[]{2, 1}), null));
        // Each node is reported under the label it was tried with
        for (final int[] path : List.of(new int[]{0}, new int[]{1}, new int[]{1, 0}, new int[]{2},
                new int[]{2, 0})) {
            final PatternNode node = PatternNodes.get(labelled, path);
            assertThat(PatternNodes.reportedLabel(ROOT, path, true)).isEqualTo(((Labelled) node).label());
        }
        assertThat(PatternNodes.reportedLabel(ROOT, new int[0], true)).isNull();
    }

    @Test
    void trialLabelsAreDistinctNamesNoAuthorWrites() {
        assertThat(PatternNodes.trialLabel(new int[]{1, 0})).isNotEqualTo(PatternNodes.trialLabel(new int[]{10}));
        assertThat(PatternNodes.trialLabel(new int[]{2, 1})).matches("[a-z]+[0-9x]*");
        assertThat(PatternNodes.isTrialLabel(PatternNodes.trialLabel(new int[]{3}))).isTrue();
        assertThat(PatternNodes.isTrialLabel("opt")).isFalse();
        assertThat(PatternNodes.isTrialLabel(null)).isFalse();
        assertThat(PatternNodes.pathOfTrialLabel(PatternNodes.trialLabel(new int[]{2, 10, 0})))
                .containsExactly(2, 10, 0);
    }

    @Test
    void whatALookaroundHoldsIsNotLabelled() {
        // A lookaround consumes nothing, so a group inside it would report nothing
        final PatternNode root = new Sequence(List.of(new PatternNode.Not(A), new PatternNode.Peek(B), C));
        final PatternNode labelled = PatternNodes.labelEvery(root);
        assertThat(PatternNodes.bare(PatternNodes.get(labelled, new int[]{0}))).isEqualTo(new PatternNode.Not(A));
        assertThat(PatternNodes.get(labelled, new int[]{0, 0})).isEqualTo(A);
        assertThat(PatternNodes.reportedLabel(root, new int[]{0, 0}, true)).isNull();
        assertThat(PatternNodes.reportedLabel(root, new int[]{1, 0}, true)).isNull();
        assertThat(PatternNodes.reportedLabel(root, new int[]{2}, true))
                .isEqualTo(PatternNodes.trialLabel(new int[]{2}));
        // A path that is not there has nothing to report
        assertThat(PatternNodes.reportedLabel(root, new int[]{7}, true)).isNull();
    }

    @Test
    void aNodeWithOneTextIsEditedInPlaceAndKeepsTheRest() {
        // A terminator's newline is spelt out, so it can be read and typed in a field
        final PatternNode until = new Labelled(new TakeUntil("\n", true), "line", null);
        assertThat(PatternNodes.inlineText(until)).isEqualTo("\\n");
        assertThat(PatternNodes.withInlineText(until, "\\t"))
                .isEqualTo(new Labelled(new TakeUntil("\t", true), "line", null));
        assertThat(PatternNodes.withInlineText(new PatternNode.TakeWhile("[0-9]", 2, 4), "[a-f]"))
                .isEqualTo(new PatternNode.TakeWhile("[a-f]", 2, 4));
        assertThat(PatternNodes.inlineText(new PatternNode.Regex("a+", null))).isEqualTo("a+");
        assertThat(PatternNodes.withInlineText(A, ":")).isEqualTo(new Tag(":"));
        // A kind with more to say than one text is the dialog's
        assertThat(PatternNodes.inlineText(new Repeat(A, 1, 3, true))).isNull();
        assertThat(PatternNodes.inlineText(new PatternNode.Ref("WORD"))).isNull();
        assertThat(PatternNodes.withInlineText(new PatternNode.Take(3), "x")).isEqualTo(new PatternNode.Take(3));
    }

    @Test
    void theAddLinePutsWhatItAddsAfterTheSelectedNode() {
        final PatternNode added = new Sequence(List.of(new Tag("x"), new Tag("y")));
        // A sequence selected: at its end, its items one by one; the last is the next place
        final PatternNodes.Placed atEnd = PatternNodes.addAfter(ROOT, new int[0], added);
        assertThat(PatternNodes.children(atEnd.root())).hasSize(5);
        assertThat(PatternNodes.get(atEnd.root(), new int[]{3})).isEqualTo(new Tag("x"));
        assertThat(atEnd.last()).containsExactly(4);
        // A node in a sequence selected: beside it
        final PatternNodes.Placed beside = PatternNodes.addAfter(ROOT, new int[]{0}, C);
        assertThat(PatternNodes.get(beside.root(), new int[]{1})).isEqualTo(C);
        assertThat(PatternNodes.get(beside.root(), new int[]{2})).isEqualTo(ROOT_ITEM_1);
        assertThat(beside.last()).containsExactly(1);
        // In a choice there is no "after": the alternative and the added become one sequence
        final PatternNodes.Placed inChoice = PatternNodes.addAfter(ROOT, new int[]{2, 0}, A);
        assertThat(PatternNodes.get(inChoice.root(), new int[]{2, 0})).isEqualTo(new Sequence(List.of(C, A)));
        assertThat(inChoice.last()).containsExactly(2, 0, 1);
        // So does a leaf at the root
        final PatternNodes.Placed leaf = PatternNodes.addAfter(A, new int[0], B);
        assertThat(leaf.root()).isEqualTo(new Sequence(List.of(A, B)));
        assertThat(leaf.last()).containsExactly(1);
    }

    @Test
    void anAuthorsLabelIsTheAuthorsWhateverItLooksLikeAndWhereverItIs() {
        // Only the stem and a path are a trial's label: "ssnodeId" is an author's
        assertThat(PatternNodes.isTrialLabel("ssnodeId")).isFalse();
        assertThat(PatternNodes.isTrialLabel("ssnode2x")).isFalse();
        assertThat(PatternNodes.isTrialLabel("ssnode")).isTrue();
        assertThat(PatternNodes.isTrialLabel("ssnode2x0x11")).isTrue();
        // A label the author put inside a lookahead is still reported, an unlabelled node there not
        final PatternNode root = new Sequence(List.of(new PatternNode.Peek(new Labelled(A, "ahead", null)),
                new PatternNode.Peek(B)));
        assertThat(PatternNodes.reportedLabel(root, new int[]{0, 0}, true)).isEqualTo("ahead");
        assertThat(PatternNodes.reportedLabel(root, new int[]{1, 0}, true)).isNull();
    }

    @Test
    void aLibraryPartsTreeReportsOnlyItsOwnLabels() {
        // A part is tried through a ref to it, which the trial does not label inside: an
        // unlabelled node of it has nothing to report, so choosing it isolates nothing
        assertThat(PatternNodes.reportedLabel(ROOT, new int[]{0}, false)).isNull();
        assertThat(PatternNodes.reportedLabel(ROOT, new int[]{1}, false)).isEqualTo("opt");
    }
}
