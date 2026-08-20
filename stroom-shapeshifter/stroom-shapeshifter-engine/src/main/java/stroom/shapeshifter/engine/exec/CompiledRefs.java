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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.compile.CompiledRef;
import stroom.shapeshifter.engine.text.Encoding;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Resolving compiled references — {@link Refs} with the interpretation already done.
 *
 * <p>Same two ways in, same rules: {@link #write} streams parts to the sink, {@link #resolve}
 * builds the value, and <b>empty is absent</b>. The difference is what no longer happens per
 * call: literal text is bytes that were encoded at compile time, and the shape of the
 * expression is a dispatch, not a walk.
 */
final class CompiledRefs {

    private CompiledRefs() {
    }

    /**
     * Write a reference to the sink.
     *
     * @return true if anything was written
     */
    static boolean write(final CompiledRef ref,
                         final MatchResult match,
                         final int matchCount,
                         final VarRegistry vars,
                         final Encoding encoding,
                         final OutputSink sink) {
        switch (ref) {
            case CompiledRef.Empty ignored -> {
                return false;
            }
            case CompiledRef.Bytes bytes -> {
                if (bytes.value().length == 0) {
                    return false;
                }
                sink.write(bytes.value());
                return true;
            }
            case CompiledRef.Composite composite -> {
                boolean wrote = false;
                for (final CompiledRef part : composite.parts()) {
                    wrote |= write(part, match, matchCount, vars, encoding, sink);
                }
                return wrote;
            }
            default -> {
                final TypedValue value = value(ref, match, matchCount, vars);
                if (value == null || value.isEmpty()) {
                    return false;
                }
                sink.write(Refs.bytes(value, encoding));
                return true;
            }
        }
    }

    /** The value of a reference as bytes, or null if it resolves to nothing. */
    static byte[] resolve(final CompiledRef ref,
                          final MatchResult match,
                          final int matchCount,
                          final VarRegistry vars,
                          final Encoding encoding) {
        switch (ref) {
            case CompiledRef.Empty ignored -> {
                return null;
            }
            case CompiledRef.Bytes bytes -> {
                return bytes.value().length == 0 ? null : bytes.value();
            }
            case CompiledRef.Composite composite -> {
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                boolean any = false;
                for (final CompiledRef part : composite.parts()) {
                    final byte[] resolved = resolve(part, match, matchCount, vars, encoding);
                    if (resolved != null) {
                        buffer.writeBytes(resolved);
                        any = true;
                    }
                }
                return any ? buffer.toByteArray() : null;
            }
            default -> {
                final TypedValue value = value(ref, match, matchCount, vars);
                return value == null || value.isEmpty() ? null : Refs.bytes(value, encoding);
            }
        }
    }

    /** The value of a reference as text, or null. */
    static String resolveText(final CompiledRef ref,
                              final MatchResult match,
                              final int matchCount,
                              final VarRegistry vars,
                              final Encoding encoding) {
        final byte[] bytes = resolve(ref, match, matchCount, vars, encoding);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** One capture reference: a group of the current match, or of a named variable. */
    private static TypedValue value(final CompiledRef ref,
                                    final MatchResult match,
                                    final int matchCount,
                                    final VarRegistry vars) {
        return switch (ref) {
            case CompiledRef.LocalGroup group -> match.group(group.group());
            case CompiledRef.RemoteVar remote ->
                    Refs.lookup(remote.varId(), remote.group(), remote.matchIndex(), matchCount, vars);
            default -> null;
        };
    }
}
