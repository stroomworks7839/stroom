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

package stroom.shapeshifter.engine.exec;

/**
 * The variables the engine sets itself, named once.
 *
 * <p>Three parts of the engine have to agree about these: whatever <b>sets</b> them, whatever
 * <b>reads</b> them, and the compiler's unknown-reference refusal, which must know they are
 * writable or it would refuse every configuration that mentions one. They were previously
 * spelt as private constants in the executor and as string literals in the compiler, which is
 * the shape E27 had just finished removing from the body checks.
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

    /** Every name the engine sets, for the compiler's refusal to treat as writable. */
    public static final java.util.Set<String> ALL =
            java.util.Set.of(MATCH_COUNT, MATCH_INDEX, INDEX, POSITION, LAST);
}
