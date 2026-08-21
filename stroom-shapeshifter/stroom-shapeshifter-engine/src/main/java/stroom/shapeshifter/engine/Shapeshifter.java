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

package stroom.shapeshifter.engine;

import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.compile.Compiler;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.exec.Executor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * Running a configuration over an input.
 *
 * <p>The way in. A configuration is compiled once — which is where its own errors surface — and
 * then run as many times as there are inputs.
 */
public final class Shapeshifter {

    private Shapeshifter() {
    }

    /**
     * Compile a configuration, so it can be run more than once without recompiling.
     *
     * @throws stroom.shapeshifter.engine.config.ConfigException if it cannot be compiled
     */
    public static CompiledProject compile(final Project project) {
        return Compiler.compile(project);
    }

    /**
     * Run a compiled configuration over a stream.
     *
     * <p>Memory stays bounded by the configuration's buffer size: the input is read through a
     * sliding window of that capacity, so a single match must fit within it — but whether a
     * record parses never depends on where a read happened to end (E13).
     *
     * @return everything the engine had to say, in order
     */
    public static List<Message> run(final CompiledProject compiled,
                                    final InputStream input,
                                    final OutputSink sink) {
        return run(compiled, input, sink, Instrument.NONE);
    }

    /**
     * Run a compiled configuration, watching what it does.
     *
     * <p>The instrument is told which template matched which bytes, what each capture held, and
     * which part of the output came from where — enough for an editor to show a configuration
     * working, and enough to find the template that is being tried everywhere and matching
     * nowhere.
     *
     * @return everything the engine had to say, in order
     */
    public static List<Message> run(final CompiledProject compiled,
                                    final InputStream input,
                                    final OutputSink sink,
                                    final Instrument instrument) {
        return Executor.run(compiled, input, sink, instrument, false);
    }

    /**
     * Run a compiled configuration over an input held whole.
     *
     * <p>Some matches need the whole input addressable rather than a window onto it — an absolute
     * seek has no meaning otherwise — and a single buffer also removes the question of whether a
     * record was cut in half by one.
     *
     * @return everything the engine had to say, in order
     */
    public static List<Message> runWhole(final CompiledProject compiled,
                                         final byte[] input,
                                         final OutputSink sink) {
        return runWhole(compiled, input, sink, Instrument.NONE);
    }

    /** Run a compiled configuration over an input held whole, watching what it does. */
    public static List<Message> runWhole(final CompiledProject compiled,
                                         final byte[] input,
                                         final OutputSink sink,
                                         final Instrument instrument) {
        return Executor.run(compiled, new ByteArrayInputStream(input), sink, instrument, true);
    }
}
