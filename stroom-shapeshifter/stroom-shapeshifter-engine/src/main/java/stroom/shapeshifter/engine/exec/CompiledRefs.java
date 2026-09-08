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

import stroom.shapeshifter.engine.compile.CompiledRef;
import stroom.shapeshifter.engine.match.MatchResult;
import stroom.shapeshifter.engine.value.TypedValue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Resolving compiled references — {@link Refs} with the interpretation already done.
 *
 * <p>Two ways in: {@link #write} streams parts through the seam, {@link #resolve} builds the
 * value; and <b>empty is absent</b>, as it is for {@link Refs}. The difference is what no longer happens per
 * call: literal text is a value made at compile time, written as its own array by a UTF-8
 * sink (design 25), and the shape of the expression is a dispatch, not a walk.
 *
 * <p>Two resolvers stay, by design: bodies and capture bindings resolve compiled references
 * here, while {@code Conditions} still resolves the authored expression through {@code Refs},
 * because compiling conditions is design 10 §2's open performance row and shape follows
 * measurement there (design 27 §2.7, E39; the captures came here with design 25 phase 3).
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
                         final Output out) {
        switch (ref) {
            case CompiledRef.Empty ignored -> {
                return false;
            }
            case CompiledRef.Bytes bytes -> {
                if (bytes.value().isEmpty()) {
                    return false;
                }
                out.write(bytes.value());
                return true;
            }
            case CompiledRef.Composite composite -> {
                boolean wrote = false;
                for (final CompiledRef part : composite.parts()) {
                    wrote |= write(part, match, matchCount, vars, out);
                }
                return wrote;
            }
            case CompiledRef.LocalGroup group -> {
                final TypedValue value = match.group(group.group());
                if (value == null || value.isEmpty()) {
                    return false;
                }
                out.write(value);
                return true;
            }
            case CompiledRef.RemoteVar remote -> {
                final TypedValue value = lookup(remote, matchCount, vars);
                if (value == null || value.isEmpty()) {
                    return false;
                }
                out.write(value);
                return true;
            }
        }
    }

    /**
     * The value of a reference with its type preserved, or null if it resolves to nothing.
     *
     * <p>Only a single capture can carry a type (design/17 §3.1): literal text and a
     * multi-part composite are strings by construction. A captured value carries its own
     * encoding (design 25), so nothing here converts.
     */
    static TypedValue resolveValue(final CompiledRef ref,
                                   final MatchResult match,
                                   final int matchCount,
                                   final VarRegistry vars) {
        switch (ref) {
            case CompiledRef.Empty ignored -> {
                return null;
            }
            case CompiledRef.Bytes bytes -> {
                return bytes.value().isEmpty() ? null : bytes.value();
            }
            case CompiledRef.Composite composite -> {
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                boolean any = false;
                for (final CompiledRef part : composite.parts()) {
                    final TypedValue resolved = resolveValue(part, match, matchCount, vars);
                    if (resolved != null) {
                        buffer.writeBytes(resolved.asUtf8());
                        any = true;
                    }
                }
                return any ? TypedValue.utf8(buffer.toByteArray()) : null;
            }
            case CompiledRef.LocalGroup group -> {
                final TypedValue value = match.group(group.group());
                return value == null || value.isEmpty() ? null : value;
            }
            case CompiledRef.RemoteVar remote -> {
                final TypedValue value = lookup(remote, matchCount, vars);
                return value == null || value.isEmpty() ? null : value;
            }
        }
    }

    /** The value of a reference as UTF-8 bytes, or null if it resolves to nothing. */
    static byte[] resolve(final CompiledRef ref,
                          final MatchResult match,
                          final int matchCount,
                          final VarRegistry vars) {
        final TypedValue value = resolveValue(ref, match, matchCount, vars);
        return value == null ? null : value.asUtf8();
    }

    /** The value of a reference as text, or null. */
    static String resolveText(final CompiledRef ref,
                              final MatchResult match,
                              final int matchCount,
                              final VarRegistry vars) {
        final byte[] bytes = resolve(ref, match, matchCount, vars);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** A variable's value by name, group and match index, or null. */
    private static TypedValue lookup(final CompiledRef.RemoteVar remote,
                                     final int matchCount,
                                     final VarRegistry vars) {
        return Refs.lookup(remote.varId(), remote.group(), remote.matchIndex(), matchCount, vars);
    }
}
