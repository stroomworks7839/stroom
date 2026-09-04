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

package stroom.shapeshifter.pipeline;

/**
 * Where an input offset is, in lines and columns, for the run in progress (design 26 phase 3):
 * what {@code line-from} and its siblings read. Bound per run by the reader over the same line
 * index that locates events, so a function's location and the event's agree.
 */
public final class RunLocations {

    private final InputLocations.Lines lines;

    RunLocations(final InputLocations.Lines lines) {
        this.lines = lines;
    }

    /** The one-based line an offset is on, or -1 when it cannot be located. */
    public int line(final long offset) {
        return lines.locate(offset).line();
    }

    /** The one-based column an offset is at, or -1 when it cannot be located. */
    public int column(final long offset) {
        return lines.locate(offset).column();
    }
}
