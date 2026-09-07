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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.match.PatternKey;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.regex.BytePattern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A configuration ready to run — the executable graph, and the second of the only two layers
 * there are (D35).
 *
 * <p>Its nodes own their state, so the graph executes <b>one run at a time</b> and is reused
 * sequentially — a {@code ByteMatcher}'s contract, one level up. Concurrency is one compiled
 * graph per instance, which compilation prices at milliseconds.
 *
 * <p>The graph also owns its <b>dispatch indexes</b>: which templates answer to a mode, and
 * which template a name means, are compile-time facts computed once at construction. Dispatch
 * reads a field; nothing filters the template list per call.
 */
public final class CompiledProject {

    private final Project project;
    private final List<CompiledTemplate> templates;
    private final Map<PatternKey, BytePattern> patterns;
    private final Encoding encoding;

    /** The source encoding a whole-source transcode decodes from, or null for none. */
    private final Encoding transcodeFrom;
    /** The registered functions the configuration calls, each once — what a run binds. */
    private final List<FunctionDefinition> functions;
    private final List<Message> warnings;
    /** Whether any body carries an element, attribute or namespace instruction (design 22 phase 2). */
    private final boolean structured;

    /** Templates per mode, in authored order; the no-mode templates sit under the null key. */
    private final Map<String, List<CompiledTemplate>> templatesByMode = new HashMap<>();

    /** The first template of each name — {@code call-template}'s meaning of a name. */
    private final Map<String, CompiledTemplate> templatesByName = new HashMap<>();

    /**
     * Build the graph.
     *
     * @param project   the authored configuration
     * @param templates its templates, compiled, in their authored order
     * @param patterns  every pattern the templates use except a regex match's own — bodies',
     *                  conditions' and progressive steps' — compiled once and keyed by text,
     *                  flags and encoding
     * @param encoding  the encoding its input is matched in: the declared one, or UTF-8 when a
     *                  transcode-family source is decoded first; a byte-order mark on the input
     *                  may still override it
     * @param transcodeFrom the source encoding a transcode-family input is decoded from, or
     *                  null when the input is matched as it arrives
     * @param warnings  anything worth saying that did not stop compilation
     * @param functions the definitions the configuration calls, bound once per run
     * @param structured whether any template writes structure, decided by the structure check
     */
    public CompiledProject(final Project project,
                           final List<CompiledTemplate> templates,
                           final Map<PatternKey, BytePattern> patterns,
                           final Encoding encoding,
                           final Encoding transcodeFrom,
                           final List<Message> warnings,
                           final List<FunctionDefinition> functions,
                           final boolean structured) {
        this.transcodeFrom = transcodeFrom;
        this.functions = List.copyOf(functions);
        this.project = project;
        this.templates = List.copyOf(templates);
        this.patterns = Map.copyOf(patterns);
        this.encoding = encoding;
        this.warnings = List.copyOf(warnings);
        this.structured = structured;

        for (final CompiledTemplate template : this.templates) {
            templatesByMode
                    .computeIfAbsent(template.template().mode(), mode -> new ArrayList<>())
                    .add(template);
            templatesByName.putIfAbsent(template.template().name(), template);
        }
        templatesByMode.replaceAll((mode, list) -> List.copyOf(list));
    }

    /**
     * Whether the configuration writes structure. A structured configuration can be run straight
     * into an event sink; a text one must be serialised and parsed (design 20 §7, design 22
     * phase 2).
     */
    public boolean structured() {
        return structured;
    }

    /** The authored configuration. */
    public Project project() {
        return project;
    }

    /** The templates, compiled, in authored order. */
    public List<CompiledTemplate> templates() {
        return templates;
    }

    /** The templates answering to a mode, in authored order — or none. */
    public List<CompiledTemplate> templates(final String mode) {
        return templatesByMode.getOrDefault(mode, List.of());
    }

    /** The template a name means, or null. A duplicated name means its first bearer. */
    public CompiledTemplate template(final String name) {
        return templatesByName.get(name);
    }

    /** The registered functions the configuration calls (design 26 §3), each once. */
    public List<FunctionDefinition> functions() {
        return functions;
    }

    /** The encoding the input stream is transcoded from before matching, or null. */
    public Encoding transcodeFrom() {
        return transcodeFrom;
    }

    /** The interned patterns, keyed by text, flags and encoding. */
    public Map<PatternKey, BytePattern> patterns() {
        return patterns;
    }

    /** The encoding the input is matched in: the declared one, or UTF-8 when the source is transcoded first. */
    public Encoding encoding() {
        return encoding;
    }

    /** Compilation's messages, reported at the start of every run. */
    public List<Message> warnings() {
        return warnings;
    }
}
