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

import stroom.shapeshifter.config.Project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Where a document's edits came from and, once undone, where they go again (design 49). A step is
/// a state of the project rather than the change made to it: the model is immutable records, so a
/// state is a reference, two neighbouring states share what did not change, and no edit needs an
/// inverse.
public final class ProjectHistory {

    /// How many steps are kept.
    public static final int LIMIT = 100;

    /// What the Source tab's edits are about: one spell of editing there is one step, however
    /// long the pauses in it.
    public static final String SOURCE = "source";

    // How soon a typed edit has to follow the last to be more of it
    private static final double JOIN_MILLIS = 2000;

    private final List<State> behind = new ArrayList<>();
    private final List<State> ahead = new ArrayList<>();
    private String subject;
    private double at;

    /// An edit is about to be made.
    ///
    /// @param before The state it leaves.
    /// @param subject What it is typed into, so that typing is one step, or null for an edit that
    ///                is a step of its own.
    /// @param sameAction Whether the author's last recorded action made this edit too: a title
    ///                   taken is its name and its colour, and one step.
    /// @param now The time, in milliseconds.
    public void record(final State before, final String subject, final boolean sameAction, final double now) {
        if (!behind.isEmpty() && (sameAction || continues(subject, now))) {
            at = now;
            return;
        }
        behind.add(before);
        if (behind.size() > LIMIT) {
            behind.remove(0);
        }
        ahead.clear();
        this.subject = subject;
        at = now;
    }

    private boolean continues(final String next, final double now) {
        return next != null && next.equals(subject) && ahead.isEmpty()
               && (SOURCE.equals(next) || now - at < JOIN_MILLIS);
    }

    /// @param now The state shown.
    /// @return The state before the last edit, to be shown instead, or null where there is none.
    public State undo(final State now) {
        return step(behind, ahead, now);
    }

    /// @param now The state shown.
    /// @return The state before the last undo, to be shown instead, or null where there is none.
    public State redo(final State now) {
        return step(ahead, behind, now);
    }

    private State step(final List<State> from, final List<State> to, final State now) {
        if (from.isEmpty()) {
            return null;
        }
        // Typing after this is not more of what was typed before it
        subject = null;
        to.add(now);
        return from.remove(from.size() - 1);
    }

    /// @return What the next redo will show, or null where there is nothing to redo.
    public State next() {
        return ahead.isEmpty()
                ? null
                : ahead.get(ahead.size() - 1);
    }

    /// @return Whether there is an edit to undo.
    public boolean canUndo() {
        return !behind.isEmpty();
    }

    /// @return Whether there is an undo to undo.
    public boolean canRedo() {
        return !ahead.isEmpty();
    }

    /// Forgets every step: another document, or this one as someone else has left it.
    public void clear() {
        behind.clear();
        ahead.clear();
        subject = null;
    }

    /// What an undo puts back.
    ///
    /// @param project The project.
    /// @param colours The templates' chosen colours, by id.
    /// @param declaredModes The modes named and not used yet, in order.
    /// @param selected The row that was selected, or null for none.
    public record State(Project project, Map<String, String> colours, Set<String> declaredModes, String selected) {

        public State {
            colours = new HashMap<>(colours);
            declaredModes = new LinkedHashSet<>(declaredModes);
        }
    }
}
