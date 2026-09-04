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

import stroom.pipeline.state.CurrentUserHolder;
import stroom.security.api.UserIdentity;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;

/**
 * {@code current-user}: as {@code stroom.pipeline.xsltfunctions.CurrentUser}: the processing
 * user's identity for audit by default, or by type: {@code display}, {@code subject}, {@code full}.
 */
public final class CurrentUserFunction extends StroomFunction {

    public CurrentUserFunction() {
        super("current-user", Signature.of(0, Kind.STRING, Kind.STRING), Purity.CONTEXT);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final CurrentUserHolder holder = context.service(CurrentUserHolder.class);
            final UserIdentity user = holder == null ? null : holder.getCurrentUser();
            if (user == null) {
                return null;
            }
            final String type = arguments.string(0);
            try {
                final String identity = switch (type == null ? "" : type) {
                    case "subject" -> user.subjectId();
                    case "full" -> user.getFullName().orElse("");
                    default -> user.getUserIdentityForAudit();
                };
                return text(identity);
            } catch (final RuntimeException e) {
                context.error(e.getMessage());
                return null;
            }
        };
    }
}
