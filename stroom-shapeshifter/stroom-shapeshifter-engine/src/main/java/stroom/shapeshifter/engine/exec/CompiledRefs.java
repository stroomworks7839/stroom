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
import stroom.shapeshifter.engine.match.MatchResult;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.value.TypedValue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Resolving compiled references — {@link Refs} with the interpretation already done.
 *
 * <p>Same two ways in, same rules: {@link #write} streams parts to the sink, {@link #resolve}
 * builds the value, and <b>empty is absent</b>. The difference is what no longer happens per
 * call: literal text is bytes that were encoded at compile time, and the shape of the
 * expression is a dispatch, not a walk.
 *
 * <p>Two resolvers stay, by design: bodies resolve compiled references here, while
 * {@code Conditions} and a level's capture binding still resolve the authored expression
 * through {@code Refs}, because compiling conditions and capture selects is design 10 §2's
 * open performance row and shape follows measurement there (design 27 §2.7, E39).
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
            case CompiledRef.LocalGroup group -> {
                final TypedValue value = match.group(group.group());
                if (value == null || value.isEmpty()) {
                    return false;
                }
                // Only the current match's bytes need converting; stores hold UTF-8 (E3).
                sink.write(Refs.bytes(value, encoding));
                return true;
            }
            case CompiledRef.RemoteVar remote -> {
                final TypedValue value = lookup(remote, matchCount, vars);
                if (value == null || value.isEmpty()) {
                    return false;
                }
                sink.write(value.asBytes());
                return true;
            }
        }
    }

    /**
     * The value of a reference with its type preserved, or null if it resolves to nothing.
     *
     * <p>Only a single capture can carry a type (design/17 §3.1): literal text and a
     * multi-part composite are strings by construction. A slice of the current match is
     * converted from the content encoding on the way out; a stored value was normalised at
     * capture and passes through untouched (E3).
     */
    static TypedValue resolveValue(final CompiledRef ref,
                                   final MatchResult match,
                                   final int matchCount,
                                   final VarRegistry vars,
                                   final Encoding encoding) {
        switch (ref) {
            case CompiledRef.Empty ignored -> {
                return null;
            }
            case CompiledRef.Bytes bytes -> {
                return bytes.value().length == 0 ? null : TypedValue.of(bytes.value());
            }
            case CompiledRef.Composite composite -> {
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                boolean any = false;
                for (final CompiledRef part : composite.parts()) {
                    final TypedValue resolved = resolveValue(part, match, matchCount, vars, encoding);
                    if (resolved != null) {
                        buffer.writeBytes(resolved.asBytes());
                        any = true;
                    }
                }
                return any ? TypedValue.of(buffer.toByteArray()) : null;
            }
            case CompiledRef.LocalGroup group -> {
                final TypedValue value = match.group(group.group());
                if (value == null || value.isEmpty()) {
                    return null;
                }
                if (value instanceof TypedValue.Bytes && !encoding.isUtf8Compatible()) {
                    return TypedValue.of(Refs.bytes(value, encoding));
                }
                return value;
            }
            case CompiledRef.RemoteVar remote -> {
                final TypedValue value = lookup(remote, matchCount, vars);
                return value == null || value.isEmpty() ? null : value;
            }
        }
    }

    /** The value of a reference as bytes, or null if it resolves to nothing. */
    static byte[] resolve(final CompiledRef ref,
                          final MatchResult match,
                          final int matchCount,
                          final VarRegistry vars,
                          final Encoding encoding) {
        final TypedValue value = resolveValue(ref, match, matchCount, vars, encoding);
        return value == null ? null : value.asBytes();
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

    /** A variable's value by name, group and match index, or null. */
    private static TypedValue lookup(final CompiledRef.RemoteVar remote,
                                     final int matchCount,
                                     final VarRegistry vars) {
        return Refs.lookup(remote.varId(), remote.group(), remote.matchIndex(), matchCount, vars);
    }
}
