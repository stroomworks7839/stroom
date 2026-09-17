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

import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.regex.BytePattern;

/**
 * A condition, compiled.
 *
 * <p>The vocabulary is the authored {@link Condition}'s, kind for kind, with one difference that
 * is the whole reason this exists: a {@code matches} test holds the {@link BytePattern} it runs,
 * where the authored form holds the pattern's <em>text</em> and the interpreter hashed that text
 * against the project's pattern map on every evaluation. A pattern can nest arbitrarily deep
 * inside {@code and}, {@code or} and {@code not}, so there is nowhere to put the compiled one
 * without a compiled tree to hold it.
 *
 * <p>That makes every compiled node hold its own pattern without exception —
 * {@link CompiledMatch.Regex} since design 10, the step interpreter's regex step since design
 * 29 phase 2 (retired by design 38), the body's regex replace since phase 3, and now
 * conditions. It also takes the map out of the run: {@code Conditions.evaluate} no longer
 * takes one, so nothing threads it through a guard evaluation on every template on every
 * record.
 *
 * <p><b>The references are compiled too, since design 30 phase 6.</b> They were not, and the
 * reason was that design 29 phase 4 measured the path at 0.4% of {@code win_sec_strict} and 0.7%
 * of {@code ausearch}, which is not a reason to move anything. What changed is not the
 * measurement: once phase 5 made a name a slot, a condition walking the authored form was the
 * last place in the engine that resolved a name by string while a record ran — the defect this
 * design exists to remove. With it gone the engine has one reference resolver rather than two,
 * and {@code Refs} is deleted.
 *
 * <p>A {@code Compare}'s operands are {@link CompiledOperand}s, which finishes a literal at
 * compile time: the authored form allocated its value and applied its cast on every evaluation,
 * and both are constant.
 */
public sealed interface CompiledCondition {

    /** Two operands compared, each a reference or a literal, each with its declared cast. */
    record Compare(Condition.Compare.Op op,
                   CompiledOperand left,
                   CompiledOperand right) implements CompiledCondition {

    }

    /** A value matched against a pattern this node holds. */
    record Matches(CompiledRef select, BytePattern pattern) implements CompiledCondition {

    }

    /** A value containing a substring. */
    record Contains(CompiledRef select, String substring) implements CompiledCondition {

    }

    /** A value starting with a prefix. */
    record StartsWith(CompiledRef select, String prefix) implements CompiledCondition {

    }

    /** Every one of them. */
    record And(CompiledCondition[] conditions) implements CompiledCondition {

    }

    /** Any one of them. */
    record Or(CompiledCondition[] conditions) implements CompiledCondition {

    }

    /** The opposite. */
    record Not(CompiledCondition condition) implements CompiledCondition {

    }

    /** Whether there was a value at all, which is not the same question as what it equals. */
    record Exists(CompiledRef select) implements CompiledCondition {

    }

    /** The iteration's first position. */
    record IsFirst() implements CompiledCondition {

    }

    /** The iteration's last position. */
    record IsLast() implements CompiledCondition {

    }
}
