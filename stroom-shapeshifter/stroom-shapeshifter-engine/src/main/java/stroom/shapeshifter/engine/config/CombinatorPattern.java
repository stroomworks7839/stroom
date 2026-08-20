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
import java.util.UUID;

/**
 * A named sequence of match steps, reusable across templates.
 *
 * <p>Referenced by id from {@link MatchStep.PatternRef}, which is what lets a project build a
 * small vocabulary of its own — a timestamp, a quoted field — and use it in several places
 * without repeating it.
 *
 * @param id    the identifier referenced by {@link MatchStep.PatternRef}
 * @param name  a human-readable name
 * @param steps the sequence
 */
public record CombinatorPattern(UUID id, String name, List<MatchStep> steps) {

    public CombinatorPattern {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
