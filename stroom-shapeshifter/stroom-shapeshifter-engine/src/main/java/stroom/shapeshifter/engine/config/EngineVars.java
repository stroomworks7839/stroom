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

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The variables the engine sets itself, named once.
 *
 * <p>Three parts of the engine have to agree about these: whatever <b>sets</b> them, whatever
 * <b>reads</b> them, and the compiler's unknown-reference refusal, which must know they are
 * writable or it would refuse every configuration that mentions one. The names are part of the
 * language — an author writes {@code $__position} — so they live with the model, below both
 * the compiler and the run.
 *
 * <p><b>A name belongs here only once something sets it.</b> A name the refusal accepts but
 * nothing writes reads as absent for ever, which is exactly what E21 deleted the
 * {@code is-first}/{@code is-last} conditions for — so the grouping names arrive with
 * grouping, not before it (design/16 §14.3).
 *
 * <p>These are <b>values rather than strings</b> (design 30 phase 4) because a reference that
 * names one is compiled into a read of the frame that holds it, and a compiled read has to say
 * <i>which</i>. {@link #byName(String)} is the one place a name becomes the thing, and it runs
 * when a configuration compiles.
 */
public enum EngineVars {

    /** How many times the current template has matched, 1-based. */
    MATCH_COUNT("__match_count", true),

    /** The same count, 0-based, for the XSLT-shaped reading. */
    MATCH_INDEX("__match_idx", true),

    /** Within a {@code for-each}: the current entry's <b>store index</b> (design/16 §4.3). */
    INDEX("__index", true),

    /** Within a {@code for-each}: the 1-based position in this iteration. */
    POSITION("__position", true),

    /** Within a {@code for-each}: how many entries the iteration will run. */
    LAST("__last", true),

    /** Within a {@code for-each-group}: the key this group was formed on. */
    GROUP_KEY("__group_key", true),

    /**
     * Within a {@code for-each-group}: the members, as a dense sequence of store indices.
     *
     * <p><b>The one that is not framed.</b> Every other name here is a scalar the engine
     * overwrites; this one is a sequence that is walked, indexed and folded exactly as an
     * authored sequence is, so it stays a store in the registry, which is what it is.
     */
    GROUP("__group", false),

    /** Within a {@code for-each-group}: how many members — known before the group opens. */
    GROUP_SIZE("__group_size", true);

    private final String varName;
    private final boolean framed;

    EngineVars(final String varName, final boolean framed) {
        this.varName = varName;
        this.framed = framed;
    }

    /** The name an author writes, without the sigil. */
    public String varName() {
        return varName;
    }

    /**
     * Whether the run holds this in an execution frame rather than in the variable registry.
     * True for every scalar; false for {@link #GROUP}, which is a sequence.
     */
    public boolean framed() {
        return framed;
    }

    private static final Map<String, EngineVars> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toMap(EngineVars::varName, Function.identity()));

    /** The engine variable a name means, or null if the name is the author's own. */
    public static EngineVars byName(final String name) {
        return name == null ? null : BY_NAME.get(name);
    }

    /** Every name the engine sets, for the compiler's refusal to treat as writable. */
    public static final Set<String> ALL = Set.copyOf(BY_NAME.keySet());

    /**
     * The names only an iteration sets. Read outside one they are absent, and absence is
     * quiet: {@code $__position} writes nothing, and worse, {@code $x[$__index]} falls back
     * to the first entry rather than to nothing — a plausible wrong value. The conditions
     * with the same failure mode already draw a lint (E21's hazard); these draw the same one.
     */
    public static final Set<String> ITERATION_ONLY =
            Set.of(INDEX.varName, POSITION.varName, LAST.varName);

    /** The names only a grouping sets, which carry the same hazard outside one. */
    public static final Set<String> GROUP_ONLY =
            Set.of(GROUP_KEY.varName, GROUP.varName, GROUP_SIZE.varName);
}
