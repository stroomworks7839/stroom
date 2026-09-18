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


package stroom.shapeshifter.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * One step of a dialogue (design 01 §10.2): a question kind, when it is asked, and its limits — the candidates
 * it may spend (the document's maximum when unset) and, for a target step, how many kinds of record are asked
 * about. Written and read as one line: {@code TARGET when text candidates 3 kinds 2}.
 */
@JsonPropertyOrder({"kind", "when", "candidates", "kinds"})
@JsonInclude(Include.NON_NULL)
public class DialogueStep {

    public static final int DEFAULT_KINDS = 3;
    private static final String WHEN = "when";
    private static final String CANDIDATES = "candidates";
    private static final String KINDS = "kinds";

    @JsonProperty
    private final QuestionKind kind;
    @JsonProperty
    private final StepGuard when;
    @JsonProperty
    private final Integer candidates;
    @JsonProperty
    private final Integer kinds;

    @JsonCreator
    public DialogueStep(@JsonProperty("kind") final QuestionKind kind,
                        @JsonProperty("when") final StepGuard when,
                        @JsonProperty("candidates") final Integer candidates,
                        @JsonProperty("kinds") final Integer kinds) {
        this.kind = Objects.requireNonNull(kind, "A step needs a question kind");
        this.when = when == null
                ? StepGuard.ALWAYS
                : when;
        if (candidates != null && candidates < 1) {
            throw new IllegalArgumentException("'candidates' must be at least 1, in step " + kind.name());
        }
        if (kinds != null && kinds < 1) {
            throw new IllegalArgumentException("'kinds' must be at least 1, in step " + kind.name());
        }
        this.candidates = candidates;
        this.kinds = kinds;
    }

    public static DialogueStep of(final QuestionKind kind) {
        return new DialogueStep(kind, StepGuard.ALWAYS, null, null);
    }

    public static DialogueStep of(final QuestionKind kind, final StepGuard when) {
        return new DialogueStep(kind, when, null, null);
    }

    public QuestionKind getKind() {
        return kind;
    }

    public StepGuard getWhen() {
        return when;
    }

    /**
     * @return The candidates this step may spend, or null for the document's maximum.
     */
    public Integer getCandidates() {
        return candidates;
    }

    /**
     * @return How many kinds of record a target step asks about, or null for {@link #DEFAULT_KINDS}.
     */
    public Integer getKinds() {
        return kinds;
    }

    /**
     * The step as one line, the form the Learning tab edits: the kind, then {@code when}, {@code candidates}
     * and {@code kinds} where they are set.
     */
    public String format() {
        final StringBuilder text = new StringBuilder(kind.name());
        if (when != StepGuard.ALWAYS) {
            text.append(' ').append(WHEN).append(' ').append(when.getDisplayValue());
        }
        if (candidates != null) {
            text.append(' ').append(CANDIDATES).append(' ').append(candidates);
        }
        if (kinds != null) {
            text.append(' ').append(KINDS).append(' ').append(kinds);
        }
        return text.toString();
    }

    /**
     * One line back into a step.
     *
     * @throws IllegalArgumentException Naming what is wrong with the line.
     */
    public static DialogueStep parse(final String line) {
        final String[] words = line.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            throw new IllegalArgumentException("A step needs a question kind");
        }
        final QuestionKind kind = kind(words[0]);
        StepGuard when = StepGuard.ALWAYS;
        Integer candidates = null;
        Integer kinds = null;
        int i = 1;
        while (i < words.length) {
            if (i + 1 >= words.length) {
                throw new IllegalArgumentException("'" + words[i] + "' needs a value, in step '" + line.trim() + "'");
            }
            final String key = words[i].toLowerCase();
            final String value = words[i + 1];
            switch (key) {
                case WHEN -> {
                    when = StepGuard.parse(value);
                    if (when == null) {
                        throw new IllegalArgumentException("'when " + value + "' is not one of always, text or xml, "
                                                           + "in step '" + line.trim() + "'");
                    }
                }
                case CANDIDATES -> candidates = positive(key, value, line);
                case KINDS -> kinds = positive(key, value, line);
                default -> throw new IllegalArgumentException("'" + words[i] + "' is not when, candidates or kinds, "
                                                              + "in step '" + line.trim() + "'");
            }
            i += 2;
        }
        return new DialogueStep(kind, when, candidates, kinds);
    }

    private static QuestionKind kind(final String word) {
        for (final QuestionKind kind : QuestionKind.values()) {
            if (kind.name().equalsIgnoreCase(word)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("'" + word + "' is not a question kind; the kinds are CHAIN, SPLIT, "
                                           + "TARGET and CONFIGURE");
    }

    private static int positive(final String key, final String value, final String line) {
        final int number;
        try {
            number = Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + " " + value + "' is not a number, in step '" + line.trim()
                                               + "'");
        }
        if (number < 1) {
            throw new IllegalArgumentException("'" + key + "' must be at least 1, in step '" + line.trim() + "'");
        }
        return number;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DialogueStep that = (DialogueStep) o;
        return kind == that.kind && when == that.when && Objects.equals(candidates, that.candidates)
               && Objects.equals(kinds, that.kinds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, when, candidates, kinds);
    }

    @Override
    public String toString() {
        return format();
    }
}
