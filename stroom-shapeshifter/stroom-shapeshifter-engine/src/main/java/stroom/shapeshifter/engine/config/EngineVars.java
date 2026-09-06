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

import java.util.Set;

/**
 * The variables the engine sets itself, named once.
 *
 * <p>Three parts of the engine have to agree about these: whatever <b>sets</b> them, whatever
 * <b>reads</b> them, and the compiler's unknown-reference refusal, which must know they are
 * writable or it would refuse every configuration that mentions one. The names are part of the
 * language — an author writes {@code $__position} — so they live with the model, below both
 * the compiler and the run (design 27 phase 8).
 *
 * <p><b>A name belongs here only once something sets it.</b> A name the refusal accepts but
 * nothing writes reads as absent for ever, which is exactly what E21 deleted the
 * {@code is-first}/{@code is-last} conditions for — so the grouping names arrive with
 * grouping, not before it (design/16 §14.3).
 */
public final class EngineVars {

    private EngineVars() {
    }

    /** How many times the current template has matched, 1-based. */
    public static final String MATCH_COUNT = "__match_count";

    /** The same count, 0-based, for the XSLT-shaped reading. */
    public static final String MATCH_INDEX = "__match_idx";

    /** Within a {@code for-each}: the current entry's <b>store index</b> (design/16 §4.3). */
    public static final String INDEX = "__index";

    /** Within a {@code for-each}: the 1-based position in this iteration. */
    public static final String POSITION = "__position";

    /** Within a {@code for-each}: how many entries the iteration will run. */
    public static final String LAST = "__last";

    /** Within a {@code for-each-group}: the key this group was formed on. */
    public static final String GROUP_KEY = "__group_key";

    /** Within a {@code for-each-group}: the members, as a dense sequence of store indices. */
    public static final String GROUP = "__group";

    /** Within a {@code for-each-group}: how many members — known before the group opens. */
    public static final String GROUP_SIZE = "__group_size";

    /** Every name the engine sets, for the compiler's refusal to treat as writable. */
    public static final Set<String> ALL = Set.of(
            MATCH_COUNT, MATCH_INDEX, INDEX, POSITION, LAST, GROUP_KEY, GROUP, GROUP_SIZE);

    /**
     * The names only an iteration sets. Read outside one they are absent, and absence is
     * quiet: {@code $__position} writes nothing, and worse, {@code $x[$__index]} falls back
     * to the first entry rather than to nothing — a plausible wrong value. The conditions
     * with the same failure mode already draw a lint (E21's hazard); these draw the same one.
     */
    public static final Set<String> ITERATION_ONLY =
            Set.of(INDEX, POSITION, LAST);

    /** The names only a grouping sets, which carry the same hazard outside one. */
    public static final Set<String> GROUP_ONLY =
            Set.of(GROUP_KEY, GROUP, GROUP_SIZE);
}
