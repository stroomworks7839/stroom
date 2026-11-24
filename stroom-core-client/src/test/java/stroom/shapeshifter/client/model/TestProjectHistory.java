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

import stroom.shapeshifter.client.model.ProjectHistory.State;
import stroom.shapeshifter.config.Project;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 49: the steps a document's edits are undone in.
class TestProjectHistory {

    private final ProjectHistory history = new ProjectHistory();

    @Test
    void nothingDoneIsNothingToUndo() {
        assertThat(history.canUndo()).isFalse();
        assertThat(history.canRedo()).isFalse();
        assertThat(history.undo(state("a"))).isNull();
        assertThat(history.redo(state("a"))).isNull();
        // and asking has not made anything
        assertThat(history.canUndo()).isFalse();
        assertThat(history.canRedo()).isFalse();
    }

    @Test
    void editsAreUndoneAndRedoneInOrder() {
        edit("a", null, 0);
        edit("b", null, 1);
        assertThat(name(history.undo(state("c")))).isEqualTo("b");
        assertThat(name(history.undo(state("b")))).isEqualTo("a");
        assertThat(history.canUndo()).isFalse();
        assertThat(name(history.redo(state("a")))).isEqualTo("b");
        assertThat(name(history.redo(state("b")))).isEqualTo("c");
        assertThat(history.canRedo()).isFalse();
    }

    @Test
    void anEditAfterAnUndoDropsWhatWasAhead() {
        edit("a", null, 0);
        history.undo(state("b"));
        edit("a", null, 1);
        assertThat(history.canRedo()).isFalse();
        assertThat(name(history.undo(state("c")))).isEqualTo("a");
    }

    @Test
    void typingIsOneStepWhileItGoesOn() {
        edit("a", "pattern", 0);
        edit("b", "pattern", 1500);
        edit("c", "pattern", 3000);
        assertThat(name(history.undo(state("d")))).isEqualTo("a");
        assertThat(history.canUndo()).isFalse();
    }

    @Test
    void aPauseOrAnotherFieldOrAnUndoEndsIt() {
        edit("a", "pattern", 0);
        edit("b", "pattern", 2000);
        assertThat(name(history.undo(state("c")))).isEqualTo("b");
        history.clear();
        edit("a", "pattern", 0);
        edit("b", "other", 1);
        edit("c", null, 2);
        edit("d", null, 3);
        assertThat(name(history.undo(state("e")))).isEqualTo("d");
        assertThat(name(history.undo(state("d")))).isEqualTo("c");
        assertThat(name(history.undo(state("c")))).isEqualTo("b");
        history.clear();
        edit("a", "pattern", 0);
        edit("b", null, 1);
        history.undo(state("c"));
        edit("b", "pattern", 2);
        assertThat(name(history.undo(state("d")))).isEqualTo("b");
    }

    @Test
    void aSpellInTheSourceTabIsOneStepHoweverLong() {
        edit("a", ProjectHistory.SOURCE, 0);
        edit("b", ProjectHistory.SOURCE, 60_000);
        assertThat(name(history.undo(state("c")))).isEqualTo("a");
        assertThat(history.canUndo()).isFalse();
    }

    @Test
    void oneActionIsOneStep() {
        edit("a", null, 0);
        history.record(state("b"), null, true, 0);
        assertThat(name(history.undo(state("c")))).isEqualTo("a");
        assertThat(history.canUndo()).isFalse();
    }

    @Test
    void whatIsNextIsWhatARedoShows() {
        edit("a", null, 0);
        assertThat(history.next()).isNull();
        history.undo(state("b"));
        assertThat(name(history.next())).isEqualTo("b");
        // and asking is not redoing
        assertThat(name(history.redo(state("a")))).isEqualTo("b");
        assertThat(history.next()).isNull();
    }

    @Test
    void theOldestStepsGo() {
        for (int i = 0; i < ProjectHistory.LIMIT + 5; i++) {
            edit(String.valueOf(i), null, i);
        }
        State last = state("now");
        int undone = 0;
        while (history.canUndo()) {
            last = history.undo(last);
            undone++;
        }
        assertThat(undone).isEqualTo(ProjectHistory.LIMIT);
        assertThat(name(last)).isEqualTo("5");
    }

    @Test
    void aStateIsNotChangedByWhatItWasTakenFrom() {
        final Map<String, String> colours = new HashMap<>(Map.of("t", "red"));
        final State state = new State(state("a").project(), colours, Set.of(), null);
        colours.clear();
        assertThat(state.colours()).containsEntry("t", "red");
    }

    private void edit(final String before, final String subject, final double now) {
        history.record(state(before), subject, false, now);
    }

    private static State state(final String name) {
        return new State(new Project(name, 5, null, List.of(), null), Map.of(), Set.of(), null);
    }

    private static String name(final State state) {
        return state.project().name();
    }
}
