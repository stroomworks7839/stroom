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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.RefExpression.MatchIndex;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;

import java.nio.charset.StandardCharsets;

/**
 * A reference expression with its resolution strategy already decided.
 *
 * <p>The authored {@link RefExpression} is a list of parts to be interpreted; this is what the
 * interpretation concluded, once, at compile time. Literal text is <b>pre-encoded bytes</b> —
 * the single change that stops every write re-encoding the same string — and the common
 * one-part shapes are named so the executor dispatches on what an expression <i>is</i> rather
 * than walking what it says. Kept as compiled nodes rather
 * than annotations on the model, because the model stays the model (D35).
 */
public sealed interface CompiledRef {

    /** An expression with no parts. Resolves to nothing, writes nothing. */
    record Empty() implements CompiledRef {

    }

    /** Pure literal text, encoded once. */
    record Bytes(byte[] value) implements CompiledRef {

    }

    /** One group of the current match. */
    record LocalGroup(int group) implements CompiledRef {

    }

    /** One group of a named variable, with the reference's index rule. */
    record RemoteVar(String varId, int group, MatchIndex matchIndex) implements CompiledRef {

    }

    /** Several parts, concatenated. Each element is one of the three shapes above. */
    record Composite(CompiledRef[] parts) implements CompiledRef {

    }

    /** Decide an expression's strategy. */
    static CompiledRef of(final RefExpression expression) {
        if (expression == null || expression.parts().isEmpty()) {
            return new Empty();
        }
        if (expression.parts().size() == 1) {
            return part(expression.parts().getFirst());
        }
        final CompiledRef[] parts = new CompiledRef[expression.parts().size()];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = part(expression.parts().get(i));
        }
        return new Composite(parts);
    }

    private static CompiledRef part(final RefPart part) {
        return switch (part) {
            case RefPart.Text text -> new Bytes(text.value().getBytes(StandardCharsets.UTF_8));
            case RefPart.Capture capture -> capture.varId() == null
                    ? new LocalGroup(capture.group())
                    : new RemoteVar(capture.varId(), capture.group(), capture.matchIndex());
        };
    }
}
