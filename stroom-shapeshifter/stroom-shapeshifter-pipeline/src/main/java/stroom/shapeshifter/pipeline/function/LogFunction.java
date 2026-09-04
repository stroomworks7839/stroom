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

package stroom.shapeshifter.pipeline.function;

import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;

/**
 * {@code log}: as {@code stroom.pipeline.xsltfunctions.Log}: a message at a severity named as
 * Stroom names them ({@code INFO}, {@code WARN}, {@code ERROR}, {@code FATAL}), into the run's
 * messages, which the pipeline's error receiver hears.
 */
public final class LogFunction extends StroomFunction {

    public LogFunction() {
        super("log", Signature.of(Kind.STRING, Kind.STRING, Kind.STRING), Purity.IMPURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final String severity = requiredString(context, arguments, 0);
            final String message = requiredString(context, arguments, 1);
            if (severity == null || message == null) {
                return null;
            }
            final stroom.util.shared.Severity stroomSeverity = stroom.util.shared.Severity.getSeverity(severity);
            if (stroomSeverity == null) {
                context.error("Unknown severity specified: " + severity);
                return null;
            }
            final Severity mapped = switch (stroomSeverity) {
                case INFO -> Severity.INFO;
                case WARNING -> Severity.WARNING;
                case ERROR -> Severity.ERROR;
                case FATAL_ERROR -> Severity.FATAL;
            };
            context.message(mapped, message);
            return null;
        };
    }
}
