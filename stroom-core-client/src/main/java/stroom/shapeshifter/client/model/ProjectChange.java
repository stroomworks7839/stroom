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
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/// What is different between two states of a project (design 49).
public final class ProjectChange {

    private ProjectChange() {
        // Static utility
    }

    /// The one thing a change is about, as it is in the later state: what to show for the change
    /// to be on screen.
    ///
    /// @param before The earlier state.
    /// @param after The later state.
    /// @param settingsRow The row that stands for the project's settings.
    /// @return The row of the one template or pattern part that is new or different, or of the
    ///         settings; null where nothing is, or more than one thing, or what changed is gone.
    public static String row(final Project before, final Project after, final String settingsRow) {
        final Map<String, Template> were = byId(before);
        String row = null;
        int changes = 0;
        for (final Template template : after.templates()) {
            if (!template.equals(were.get(template.id()))) {
                row = template.id();
                changes++;
            }
        }
        for (final Map.Entry<String, PatternNode> part : after.patterns().entrySet()) {
            if (!part.getValue().equals(before.patterns().get(part.getKey()))) {
                row = Patterns.rowId(part.getKey());
                changes++;
            }
        }
        if (changes == 0 && !sameSettings(before, after)) {
            return settingsRow;
        }
        return changes == 1
                ? row
                : null;
    }

    /// What an edit was typed into, where it is one that arrives a pause at a time: the pattern of
    /// a template's regex.
    ///
    /// @param before The state before the edit.
    /// @param after The state after it.
    /// @return The field it was typed into, or null for any other edit.
    public static String typedInto(final Project before, final Project after) {
        final String id = row(before, after, null);
        final Template was = id == null
                ? null
                : byId(before).get(id);
        final Template is = id == null
                ? null
                : byId(after).get(id);
        if (was == null || is == null
            || !(was.match() instanceof final MatchExpression.Regex from)
            || !(is.match() instanceof final MatchExpression.Regex to)) {
            return null;
        }
        final boolean onlyThePattern = new MatchExpression.Regex(to.pattern(), from.flags(), from.advance()).equals(to)
                                       && Templates.withMatch(was, to).equals(is);
        return onlyThePattern
                ? "pattern of " + id
                : null;
    }

    private static boolean sameSettings(final Project before, final Project after) {
        return Objects.equals(before.name(), after.name())
               && before.version() == after.version()
               && Objects.equals(before.source(), after.source());
    }

    private static Map<String, Template> byId(final Project project) {
        final Map<String, Template> templates = new HashMap<>();
        for (final Template template : project.templates()) {
            templates.put(template.id(), template);
        }
        return templates;
    }
}
