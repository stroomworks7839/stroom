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

import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Signature;

/** {@code manifest}: the running stream's attributes. */
public final class ManifestNoArgsFunction extends ManifestFunction {

    public ManifestNoArgsFunction() {
        super("manifest", Signature.of(Kind.STRING));
    }
}
