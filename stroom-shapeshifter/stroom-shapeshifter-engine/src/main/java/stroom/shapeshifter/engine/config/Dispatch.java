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

package stroom.shapeshifter.engine.config;

/**
 * How a level's templates are dispatched against its content — D36's named points in the
 * unbundled control-flow space (design/11-strict-dispatch.md). Chosen per apply site, with
 * a source-level default; when nothing says otherwise, a configuration's version decides:
 * version 4 and later default {@link #STRICT}, earlier versions — the migrated DS3 era —
 * default {@link #LAX}, which is how "migration marks migrated configurations lax" is spelt
 * without editing them.
 */
public enum Dispatch {

    /**
     * The cursor moves only by matching at it. Every template is asked the anchored
     * question; skipping is authored (a {@code consume}-marked template) or an error.
     */
    STRICT,

    /**
     * DS3's search-and-skip: templates search the remaining region, the first in list order
     * that matches anywhere wins, and the skipped prefix is consumed with a report.
     */
    LAX,

    /**
     * DS3's {@code matchOrder="any"}: templates search a working copy of the content, and each
     * match's span is excised — the matched bytes leave and the pieces close up before the next
     * pass. List priority beats data position: the first template in order that matches
     * anywhere wins, however far into the content its match lies. After the first excision,
     * offsets in the working copy no longer correspond to the input, so match attribution goes
     * dark from there on.
     */
    ANY,

    /**
     * One pass, every matching template runs, nothing consumes. Classification and
     * validation sweeps over the same content.
     */
    CLASSIFY,

    /**
     * Maximal munch: every template attempts at the cursor, the longest match wins, ties go
     * to list order.
     */
    LEXER;

    /**
     * The dispatch in force at an apply site: the directive's, else the source's, else the
     * version default — strict from version 4, lax before.
     */
    public static Dispatch effective(final Dispatch declared, final Project project) {
        if (declared != null) {
            return declared;
        }
        if (project.source().dispatch() != null) {
            return project.source().dispatch();
        }
        return project.version() >= 4 ? STRICT : LAX;
    }
}
