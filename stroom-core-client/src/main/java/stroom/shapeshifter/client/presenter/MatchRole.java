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

/**
 * What a template's match is <em>for</em> (design 44 §5f) — the editor's reading of D36's
 * {@code consume} marker. Both roles find the match and advance the cursor by it, and both run
 * the body over the matched content; the role decides only whether that match becomes a record:
 * whether it counts, opens a frame the navigator can step, and binds captures.
 *
 * <p>It is a choice of meaning, not a setting, so it is a picker rather than a tick — for the
 * reason §1 made the match kind one. "Skip" and "eater" were both tried and both mislead:
 * nothing is discarded and nothing stops.
 */
public enum MatchRole {
    RECORD("a record", "each match counts, binds its captures and opens a frame to step"),
    ADVANCE("advance only", "the cursor moves past each match and nothing is counted, captured "
                            + "or framed — the body still runs over the content, which is where "
                            + "an emit-error goes");

    private final String label;
    private final String note;

    MatchRole(final String label, final String note) {
        this.label = label;
        this.note = note;
    }

    /** The picker's word. */
    public String label() {
        return label;
    }

    /** What the role means, in a line, beside the picker. */
    public String note() {
        return note;
    }

    /** The wire's bit: {@code consume} is true for advance-only, as the fixtures and DS3 spell it. */
    public boolean consume() {
        return this == ADVANCE;
    }

    public static MatchRole of(final boolean consume) {
        return consume
                ? ADVANCE
                : RECORD;
    }
}
