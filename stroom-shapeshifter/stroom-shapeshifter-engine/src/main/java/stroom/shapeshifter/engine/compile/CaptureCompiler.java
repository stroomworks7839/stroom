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

import stroom.shapeshifter.engine.config.CaptureBinding;
import stroom.shapeshifter.engine.graph.CompiledCapture;

import java.util.ArrayList;
import java.util.List;

/** A template's capture bindings, compiled: the name interned, the source decided. */
final class CaptureCompiler {

    private CaptureCompiler() {
    }

    /** The compiled form of a template's bindings, in their authored order. */
    static CompiledCapture[] compile(final List<CaptureBinding> captures,
                                         final Interner names) {
        final List<CompiledCapture> compiled = new ArrayList<>(captures.size());
        for (final CaptureBinding capture : captures) {
            final CompiledCapture.Source source = switch (capture.select()) {
                case final CaptureBinding.CaptureSource.Group group ->
                        new CompiledCapture.Source.Group(group.group());
                case final CaptureBinding.CaptureSource.Label label ->
                        new CompiledCapture.Source.Group(names.group(label.label()));
                case final CaptureBinding.CaptureSource.Step step ->
                        new CompiledCapture.Source.Group(step.index() + 1);
                case final CaptureBinding.CaptureSource.Select select ->
                        new CompiledCapture.Source.Select(
                                RefCompiler.compile(select.select(), names));
                case final CaptureBinding.CaptureSource.KeyValue keyValue ->
                        new CompiledCapture.Source.KeyValue(
                                RefCompiler.compile(keyValue.keyRef(), names),
                                RefCompiler.compile(keyValue.valueRef(), names));
                // Refused before compilation reaches here (design 27 ruling 10).
                case final CaptureBinding.CaptureSource.Field ignored ->
                        throw new IllegalStateException(
                                "Field capture sources are refused at compile time");
            };
            // A capture is a value source (design 35 §4): it assigns a declared scalar, appends
            // to a declared list — absence when it fails — or puts into a declared map. Which is
            // the declaration's to say, and it was recorded before any body compiled.
            compiled.add(new CompiledCapture(names.intern(capture.name()), source, capture.as(),
                    names.typeOf(capture.name())));
        }
        return compiled.toArray(new CompiledCapture[0]);
    }
}
