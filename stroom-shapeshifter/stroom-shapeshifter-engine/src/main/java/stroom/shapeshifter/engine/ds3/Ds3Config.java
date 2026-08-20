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

package stroom.shapeshifter.engine.ds3;

import java.util.List;
import java.util.Set;

/**
 * A parsed Data Splitter v3 configuration.
 *
 * <p>DS3 is a small language — seven elements and nothing else — and this is its tree, kept
 * separate from {@link stroom.shapeshifter.engine.config.Project} because the two say different
 * things. DS3 describes a <i>nesting</i>: an expression contains groups, groups contain
 * expressions and data. The template model describes a <i>dispatch</i>. Converting between them
 * is {@link Ds3Migration}'s job, and it is easier to read with the two shapes named separately.
 *
 * <p>Three of the seven are expressions — they consume input. The rest describe output or
 * capture.
 */
public sealed interface Ds3Config {

    /** The children of a node that can have them. */
    List<Ds3Config> children();

    /** True for the three elements that consume input. */
    default boolean isExpression() {
        return this instanceof Split || this instanceof Regex || this instanceof All;
    }

    /** {@code <dataSplitter>} — the document. */
    record Root(int bufferSize, boolean ignoreErrors, List<Ds3Config> children) implements Ds3Config {

    }

    /** {@code <split>} — break the input on a separator. */
    record Split(String id,
                 String delimiter,
                 String escape,
                 String containerStart,
                 String containerEnd,
                 int minMatch,
                 int maxMatch,
                 Set<Integer> onlyMatch,
                 List<Ds3Config> children) implements Ds3Config {

    }

    /** {@code <regex>} — match the input against a pattern. */
    record Regex(String id,
                 String pattern,
                 boolean dotAll,
                 boolean caseInsensitive,
                 int minMatch,
                 int maxMatch,
                 Set<Integer> onlyMatch,
                 List<Ds3Config> children) implements Ds3Config {

    }

    /** {@code <all>} — take everything given. */
    record All(String id, List<Ds3Config> children) implements Ds3Config {

    }

    /** {@code <group>} — a record, or part of one. */
    record Group(String id,
                 String value,
                 boolean ignoreErrors,
                 List<Ds3Config> children) implements Ds3Config {

    }

    /** {@code <data>} — a named value in the output. */
    record Data(String id,
                String name,
                String value,
                boolean hasChildren,
                List<Ds3Config> children) implements Ds3Config {

    }

    /** {@code <var>} — capture a value for use elsewhere. */
    record Var(String id, String value) implements Ds3Config {

        @Override
        public List<Ds3Config> children() {
            return List.of();
        }
    }
}
