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

package stroom.shapeshifter.ai.stage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The record shape signature of design §5: a hash of a record's structure with its values removed.
 * For raw text it is the token-class skeleton of the first non-blank line — runs of digits, letters,
 * whitespace and each punctuation character reduced to a class — so two lines differing only in values
 * share a signature. For XML it is the element skeleton of the first record — the root's first child
 * element and its subtree — tag names and nesting, no text or attribute values, so that a stream's
 * signature does not vary with how many records it carries.
 * <p>
 * Provisional. Ruling A6 (normalisation) is open and waits for real feeds; scenarios depend only on the
 * same shape yielding the same key and a different shape a different one, which is what this gives.
 */
public final class ShapeSignature {

    private static final int DIGEST_BYTES = 8;

    private ShapeSignature() {
    }

    /**
     * Markup begins with a tag, a declaration or a comment; a BSD syslog line begins with {@code <34>},
     * which is text.
     */
    private static final Pattern MARKUP = Pattern.compile("^\\s*<(\\?|!|[A-Za-z_])");

    public static String of(final String data) {
        final String skeleton = isMarkup(data)
                ? xmlSkeleton(data)
                : textSkeleton(data);
        return digest(skeleton);
    }

    static boolean isMarkup(final String data) {
        return MARKUP.matcher(data).find();
    }

    static String textSkeleton(final String data) {
        final String line = data.lines()
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse("");
        final StringBuilder skeleton = new StringBuilder();
        char previous = 0;
        for (final char c : line.toCharArray()) {
            final char cls = Character.isDigit(c)
                    ? '9'
                    : Character.isLetter(c)
                            ? 'a'
                            : Character.isWhitespace(c)
                                    ? ' '
                                    : c;
            if (cls != previous || !(cls == '9' || cls == 'a' || cls == ' ')) {
                skeleton.append(cls);
            }
            previous = cls;
        }
        return skeleton.toString();
    }

    static String xmlSkeleton(final String data) {
        final StringBuilder skeleton = new StringBuilder();
        // Depth 0 is outside the root, 1 is the root, 2 is a record. The skeleton is the root's name and
        // the first record's subtree; later records, which share the record's shape, are not walked.
        int depth = 0;
        boolean recordSeen = false;
        int i = 0;
        while ((i = data.indexOf('<', i)) >= 0) {
            final int end = data.indexOf('>', i);
            if (end < 0) {
                break;
            }
            final String tag = data.substring(i + 1, end);
            i = end + 1;
            if (tag.startsWith("?") || tag.startsWith("!")) {
                continue;
            }
            final boolean closing = tag.startsWith("/");
            final boolean selfClosing = !closing && tag.endsWith("/");
            final String name = tag.replaceFirst("^/", "").split("[\\s/]", 2)[0];
            if (closing) {
                depth--;
                if (depth == 1) {
                    // The first record has closed; nothing after it changes the shape.
                    skeleton.append("</").append(name).append('>');
                    break;
                }
                if (depth == 0) {
                    break;
                }
            } else {
                if (depth == 1 && recordSeen) {
                    break;
                }
                if (depth == 1) {
                    recordSeen = true;
                }
                depth++;
            }
            skeleton.append(closing
                    ? "</"
                    : "<").append(name).append('>');
            if (selfClosing) {
                // An empty element is one shape however it is written: <data/> and <data></data> agree.
                skeleton.append("</").append(name).append('>');
                depth--;
                if (depth == 1) {
                    break;
                }
            }
        }
        return skeleton.toString();
    }

    private static String digest(final String skeleton) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(skeleton.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, DIGEST_BYTES);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
