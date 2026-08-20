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
 * @param version   the format version; 3 is the template format described here
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
        templates = templates == null ? List.of() : List.copyOf(templates);
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
    }

    /**
     * Settings that apply to the input as a whole.
     *
     * @param bufferSize   how many bytes are read at a time. A match never spans two buffers, so
     *                     this is also the largest record the configuration can handle
     * @param ignoreErrors suppress the root level's skip and unmatched-content reports — DS3's
     *                     {@code ignoreErrors} on the {@code dataSplitter} element itself
     * @param encoding     the input encoding, or {@code auto} to detect it from a byte-order mark
     */
    public record SourceConfig(int bufferSize, boolean ignoreErrors, String encoding) {

        /** The buffer size a configuration gets if it does not ask for one. */
        public static final int DEFAULT_BUFFER_SIZE = 20_000;

        /** The encoding label meaning "detect it". */
        public static final String AUTO = "auto";

        public SourceConfig {
            encoding = encoding == null ? AUTO : encoding;
        }

        /** The settings an input gets when a configuration says nothing about it. */
        public static SourceConfig defaults() {
            return new SourceConfig(DEFAULT_BUFFER_SIZE, false, AUTO);
        }
    }
}
