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

package stroom.shapeshifter.pipeline;

import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.function.FunctionRegistry;
import stroom.shapeshifter.engine.graph.CompiledProject;
import stroom.shapeshifter.engine.text.Encoding;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One configuration, compiled once per reading it is asked for (design 32 §4).
 *
 * <p>A compiled model carries its encoding in every pattern, delimiter and step, so it serves one
 * reading and should say which. A feed is usually uniform, so this holds one entry; a mixed one
 * holds a handful, built the first time each is seen.
 *
 * <p><b>Compilation is lazy, which is a change and an improvement.</b> The factory used to compile
 * in its constructor, so a configuration that was never parsed with still paid for compiling.
 * Nothing is built here until a stream arrives and says what it is.
 *
 * <p>The map is keyed by the sniffed or declared encoding — <em>data</em>, which design 30 §1
 * exempts — and its lifetime is the pipeline element's rather than global, which is where a cache
 * keyed by data belongs.
 *
 * <h2>One run at a time, and this does not enforce it</h2>
 *
 * <p><b>A compiled model is not safe for two runs at once</b>, and that is easy to miss because
 * nothing about it looks stateful. It is: {@code CompiledMatch.Regex}, {@code CompiledStep.Regex}
 * and {@code Replacer} each hold a {@code ByteMatcher} as a field — design 10's change 1, a
 * matcher made once instead of per attempt — and a matcher carries the positions and groups of
 * the match it is running. Two runs sharing one would overwrite each other's.
 *
 * <p>{@code Replacer}'s javadoc calls that "the graph's one-run-at-a-time contract". <b>What
 * enforces it is a lease, and the lease is not here.</b> Stroom's {@code AbstractDocPool} hands a
 * {@code StoredParserFactory} to one processing run at a time — {@code borrowObject} in
 * {@code ShapeshifterParser.createReader}, {@code returnObject} in a {@code finally} in
 * {@code endProcessing} — and this pool lives inside the object being leased. So exclusivity is
 * already owned, once, above.
 *
 * <p><b>Which is why there are no leases here.</b> Nesting a second exclusivity mechanism inside
 * an existing one splits a guarantee that currently has a single owner, and a guarantee with two
 * owners is how it comes to have none. The dependency is written down instead — because a future
 * caller that holds a {@code CompiledProjects} somewhere longer-lived than one lease, a
 * singleton or a shared cache, would break the contract silently and at speed.
 */
public final class CompiledProjects {

    private final Project project;
    private final FunctionRegistry registry;
    private final Map<Encoding, CompiledProject> byEncoding = new ConcurrentHashMap<>();

    public CompiledProjects(final Project project, final FunctionRegistry registry) {
        this.project = Objects.requireNonNull(project, "project");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** The configuration as authored, for whatever needs to read it without running. */
    public Project project() {
        return project;
    }

    /**
     * The model compiled for a reading, building it if this is the first stream to need one.
     *
     * <p>A configuration error surfaces here rather than when the factory was made, which is
     * later than it used to be and is the price of not compiling what is never used. It is still
     * before any byte of the input has been matched.
     */
    public CompiledProject forEncoding(final Encoding encoding) {
        return byEncoding.computeIfAbsent(encoding,
                reading -> Shapeshifter.compile(project, registry, reading));
    }

    /** How many readings have been needed, which is the number §8 says to instrument. */
    public int size() {
        return byEncoding.size();
    }
}
