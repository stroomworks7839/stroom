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

import java.util.List;

/**
 * A whole configuration: where the data comes from, and the templates that shape it.
 *
 * <p>This is the authoring and serialisation model, not the execution model. It is a plain tree
 * of records with no behaviour and no framework annotations — binding lives in
 * {@code ProjectReader}, so the format can change hands without the model moving.
 *
 * <p>The design is XSLT's, taken deliberately: a flat list of templates, each combining a match,
 * its captures and its output, dispatched by mode and guard rather than by position in a tree.
 *
 * @param name      a human-readable name for the configuration
 * @param version   the format version; 3 is the template format described here, and 4 is the
 *                  same format with the dispatch default flipped to strict (E20) — a
 *                  configuration that says nothing about dispatch runs lax at version 3 and
 *                  strict from version 4
 * @param source    settings for the input as a whole
 * @param templates the templates, in the order they are tried
 * @param patterns  named combinator patterns, reusable across templates by id
 */
public record Project(String name,
                      int version,
                      SourceConfig source,
                      List<Template> templates,
                      List<CombinatorPattern> patterns) {

    public Project {
        source = source == null ? SourceConfig.defaults() : source;
        templates = templates == null ? List.of() : List.copyOf(templates);
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
    }

    /**
     * Settings that apply to the input as a whole.
     *
     * @param bufferSize   the capacity of the sliding window the input is read through (E13).
     *                     Consumed content slides out and more slides in, so this is not a
     *                     record-size cap on the stream — but a single match must fit within
     *                     the window, so it is the largest one match the configuration can make
     * @param ignoreErrors suppress the root level's skip and unmatched-content reports — DS3's
     *                     {@code ignoreErrors} on the {@code dataSplitter} element itself
     * @param encoding     the input encoding, or {@code auto} to detect it from a byte-order mark
     * @param dispatch     the default dispatch mode for every level (D36), or null to let the
     *                     configuration's version decide: strict from version 4, lax before
     * @param strictValues warn when a non-numeric value reaches an arithmetic instruction
     *                     (design/17 §10) — off by default, because messy data is the normal
     *                     case; on when "why is this element empty" needs evidence
     * @param maxSequenceEntries how many entries one sequence may hold before the run is
     *                     stopped (design/16 §10). A configuration that accumulates nothing
     *                     keeps the sliding window's bound exactly; one that accumulates is
     *                     bounded by this instead, and says so
     */
    public record SourceConfig(int bufferSize,
                               boolean ignoreErrors,
                               String encoding,
                               Dispatch dispatch,
                               boolean strictValues,
                               int maxSequenceEntries) {

        /** The buffer size a configuration gets if it does not ask for one. */
        public static final int DEFAULT_BUFFER_SIZE = 20_000;

        /** How many entries one sequence may hold before the run stops (design/16 §10). */
        public static final int DEFAULT_MAX_SEQUENCE_ENTRIES = 100_000;

        /** The encoding label meaning "detect it". */
        public static final String AUTO = "auto";

        public SourceConfig {
            encoding = encoding == null ? AUTO : encoding;
            if (bufferSize <= 0) {
                throw new ConfigException("A buffer size must be positive: " + bufferSize);
            }
            if (maxSequenceEntries <= 0) {
                throw new ConfigException(
                        "A max sequence entries must be positive: " + maxSequenceEntries);
            }
        }

        /** The settings an input gets when a configuration says nothing about it. */
        public static SourceConfig defaults() {
            return new SourceConfig(DEFAULT_BUFFER_SIZE, false, AUTO, null, false,
                    DEFAULT_MAX_SEQUENCE_ENTRIES);
        }
    }
}
