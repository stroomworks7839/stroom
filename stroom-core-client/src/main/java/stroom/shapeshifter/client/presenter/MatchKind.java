/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.config.MatchExpression;

/**
 * The kinds a template's match can be, as the match editor offers them (design 44 §1): one
 * choice, not four places to configure. {@link #spelling()} is the chip's word; {@link #label()}
 * is the picker's.
 */
public enum MatchKind {
    REGEX("regex", "regex", "one expression, matched by the engine"),
    TREE("tree", "pattern tree", "composition: sequences, choices and repeats over parts, "
                                 + "and refs to the library"),
    PARTS("parts", "framed sequence", "patterns interleaved with take, seek and read - for "
                                      + "length-prefixed and binary formats, where a field says "
                                      + "how long the next one is"),
    DELIMITER("delimiter", "delimiter", "a separator, with an escape and a container: the CSV case"),
    SOURCE("source", "source", "the document itself, matched once"),
    ALL("all", "all", "everything handed to this template"),
    NAMED("named", "named", "invoked by name only");

    private final String spelling;
    private final String label;
    private final String note;

    MatchKind(final String spelling, final String label, final String note) {
        this.spelling = spelling;
        this.label = label;
        this.note = note;
    }

    /** What the kind is, in a line: the picker's title and the note beside it. */
    public String note() {
        return note;
    }

    public String spelling() {
        return spelling;
    }

    public String label() {
        return label;
    }

    /** Whether this kind holds a pattern: the three that convert between one another. */
    public boolean holdsPattern() {
        return this == REGEX || this == TREE || this == PARTS;
    }

    public static MatchKind of(final MatchExpression match) {
        if (match instanceof MatchExpression.Regex) {
            return REGEX;
        } else if (match instanceof MatchExpression.Pattern) {
            return TREE;
        } else if (match instanceof MatchExpression.Parts) {
            return PARTS;
        } else if (match instanceof MatchExpression.Delimiter) {
            return DELIMITER;
        } else if (match instanceof MatchExpression.Source) {
            return SOURCE;
        } else if (match instanceof MatchExpression.All) {
            return ALL;
        }
        return NAMED;
    }
}
