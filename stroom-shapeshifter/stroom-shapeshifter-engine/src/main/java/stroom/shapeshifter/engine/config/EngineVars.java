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
 * The questions a configuration can ask about <i>now</i> — how many times this template has
 * matched, where a walk is, how big a group is — named once.
 *
 * <p>Three parts of the engine have to agree about these: whatever <b>sets</b> them, whatever
 * <b>reads</b> them, and the reader that turns a spelling into one. The spellings are part of the
 * language — an author writes {@code position()} — so they live with the model, below both the
 * compiler and the run.
 *
 * <p><b>They are functions, not variables</b> (design 35 §6). Every variable has a declaration, a
 * scope and a type; a counter has none of the three, so leaving it in the variable namespace made
 * it the one name a reference could resolve that was never declared — and needed a reserved
 * {@code __} prefix and a refusal to keep configurations from colliding with it. As functions they
 * reserve nothing: a configuration may declare a variable called {@code match_count}.
 *
 * <p><b>They are special forms, not registry functions.</b> The compiler recognises them and
 * resolves each to a read of the frame that holds it — a compile-time pointer, exactly as design
 * 30 phase 4 made it — rather than a call through {@code FunctionRegistry}, which would put a
 * dispatch on the hottest path in the engine.
 *
 * <p><b>A name belongs here only once something sets it.</b> A function the reader accepts but
 * nothing writes reads as absent for ever, which is exactly what E21 deleted the
 * {@code is-first}/{@code is-last} conditions for — so the grouping functions arrive with
 * grouping, not before it (design/16 §14.3).
 */
public enum EngineVars {

    /** How many times the current template has matched, 1-based. */
    MATCH_COUNT("matchCount", true),

    /** The same count, 0-based, for the XSLT-shaped reading. */
    MATCH_INDEX("matchIndex", true),

    /** Within a {@code for-each}: the current entry's <b>store index</b> (design/16 §4.3). */
    INDEX("index", true),

    /** Within a {@code for-each}: the 1-based position in this iteration. */
    POSITION("position", true),

    /** Within a {@code for-each}: how many entries the iteration will run. */
    LAST("last", true),

    /** Within a {@code for-each-group}: the key this group was formed on. */
    GROUP_KEY("groupKey", true),

    /**
     * Within a {@code for-each-group}: the members, as a list of 1-based positions.
     *
     * <p><b>The one that is not framed.</b> Every other function here answers a scalar the
     * engine overwrites; this one is a sequence that is walked, indexed and folded exactly as an
     * authored sequence is, so it stays a store in the registry, which is what it is. Its slot is
     * interned under its {@link #spelling()}, which no declaration can take, so it collides with
     * nothing an author names.
     */
    GROUP("group", false),

    /** Within a {@code for-each-group}: how many members — known before the group opens. */
    GROUP_SIZE("groupSize", true);

    private final String functionName;
    private final boolean framed;

    EngineVars(final String functionName, final boolean framed) {
        this.functionName = functionName;
        this.framed = framed;
    }

    /** The function's name, as the configuration spells it in a {@code function} part. */
    public String functionName() {
        return functionName;
    }

    /** The name as an author reads it, {@code matchCount()}: the form messages and walks use. */
    public String spelling() {
        return functionName + "()";
    }

    /**
     * Whether the run holds this in an execution frame rather than in the variable registry.
     * True for every scalar; false for {@link #GROUP}, which is a sequence.
     */
    public boolean framed() {
        return framed;
    }

    private static final Map<String, EngineVars> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toMap(EngineVars::functionName, Function.identity()));

    /** The function a name means, or null if there is no such function. */
    public static EngineVars byName(final String name) {
        return name == null ? null : BY_NAME.get(name);
    }

    /** Every spelling, for the message that says what the functions are. */
    public static String spellings() {
        return Arrays.stream(values()).map(EngineVars::spelling).collect(Collectors.joining(", "));
    }

    /**
     * The functions only an iteration answers. Read outside one they are absent, and absence is
     * quiet: {@code position()} writes nothing, and worse, {@code $x[index()]} falls back
     * to the first entry rather than to nothing — a plausible wrong value. The conditions
     * with the same failure mode already draw a lint (E21's hazard); these draw the same one.
     */
    public static final Set<EngineVars> ITERATION_ONLY = Set.of(INDEX, POSITION, LAST);

    /** The functions only a grouping answers, which carry the same hazard outside one. */
    public static final Set<EngineVars> GROUP_ONLY = Set.of(GROUP_KEY, GROUP, GROUP_SIZE);
}
