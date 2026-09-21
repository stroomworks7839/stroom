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
    REGEX("regex", "regex"),
    TREE("tree", "pattern tree"),
    PARTS("parts", "parts"),
    DELIMITER("delimiter", "delimiter"),
    SOURCE("source", "source"),
    ALL("all", "all"),
    NAMED("named", "named");

    private final String spelling;
    private final String label;

    MatchKind(final String spelling, final String label) {
        this.spelling = spelling;
        this.label = label;
    }

    public String spelling() {
        return spelling;
    }

    public String label() {
        return label;
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
