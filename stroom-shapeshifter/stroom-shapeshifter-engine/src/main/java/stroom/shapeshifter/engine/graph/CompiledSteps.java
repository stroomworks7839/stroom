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

package stroom.shapeshifter.engine.graph;

import stroom.shapeshifter.engine.match.Decoding;
import stroom.shapeshifter.engine.text.Encoding;

import java.util.List;

/**
 * A compiled step sequence and the reading it runs under — everything {@link Steps} needs to run
 * a progressive match, in one object.
 *
 * <p>One object, and flat, for a measured reason. These three were once reached as a chain — the
 * match node held a pair, the pair held the reading, the reading held the encoding — and walking
 * it on every match attempt cost <b>4.2%</b> on the {@code progressive} workload, six interleaved
 * rounds out of six. Every call in the chain was inlined and nothing allocated more; what cost
 * was the dependent loads, which inlining cannot remove. Held together they are one hop and then
 * adjacent fields.
 *
 * <p>The encoding is the reading's own, taken here rather than asked for, so the two cannot
 * disagree: a step's bytes and the tag on the value it produces answer to one fact, not two.
 */
public final class CompiledSteps {

    private final List<CompiledStep> steps;
    private final Decoding decoding;
    private final Encoding encoding;

    public CompiledSteps(final List<CompiledStep> steps, final Decoding decoding) {
        this.steps = List.copyOf(steps);
        this.decoding = decoding;
        this.encoding = decoding.encoding();
    }

    /** The compiled steps, in order. */
    public List<CompiledStep> steps() {
        return steps;
    }

    /** How bytes read as characters under this reading. */
    public Decoding decoding() {
        return decoding;
    }

    /** The encoding every value a step produces is tagged with. */
    public Encoding encoding() {
        return encoding;
    }
}
