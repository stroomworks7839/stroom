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

package stroom.shapeshifter.engine.function;

import java.util.List;

/**
 * A function's arity and kinds, checked at compile time (arity) and applied at run time
 * (kinds, by casting). {@code argKinds} has {@code maxArgs} entries; a call may pass fewer,
 * down to {@code minArgs}, and the missing trailing positions are absent.
 */
public record Signature(int minArgs, int maxArgs, List<Kind> argKinds, Kind result) {

    public Signature {
        if (minArgs < 0 || maxArgs < minArgs) {
            throw new IllegalArgumentException("A signature's arity must be 0 <= min <= max, not "
                                               + minArgs + ".." + maxArgs);
        }
        if (argKinds.size() != maxArgs) {
            throw new IllegalArgumentException("A signature must name a kind for each of its "
                                               + maxArgs + " positions, not " + argKinds.size());
        }
        argKinds = List.copyOf(argKinds);
    }

    /** Exactly {@code kinds.length} arguments. */
    public static Signature of(final Kind result, final Kind... kinds) {
        return new Signature(kinds.length, kinds.length, List.of(kinds), result);
    }

    /** From {@code minArgs} up to {@code kinds.length} arguments. */
    public static Signature of(final int minArgs, final Kind result, final Kind... kinds) {
        return new Signature(minArgs, kinds.length, List.of(kinds), result);
    }
}
