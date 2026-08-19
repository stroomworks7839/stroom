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

package stroom.shapeshifter.regex;

/**
 * Thrown when a pattern cannot be compiled, carrying the reason so a caller can tell a syntax
 * error from a pattern that is merely outside what this engine supports.
 */
public class PatternCompileException extends RuntimeException {

    /**
     * Why a pattern was rejected. The distinction matters: {@link #SYNTAX} is the author's
     * mistake, while the others are statements about engine capability and each has a
     * documented route forward.
     */
    public enum Reason {
        /** The pattern is not valid regex syntax. */
        SYNTAX,
        /** Valid syntax, but uses something this engine does not yet implement. */
        UNSUPPORTED
    }

    private final Reason reason;
    private final String pattern;
    private final int position;

    public PatternCompileException(final Reason reason,
                                   final String pattern,
                                   final int position,
                                   final String message) {
        super(render(reason, pattern, position, message));
        this.reason = reason;
        this.pattern = pattern;
        this.position = position;
    }

    public Reason getReason() {
        return reason;
    }

    public String getPattern() {
        return pattern;
    }

    /** Offset within the pattern, or -1 if the problem is not localised. */
    public int getPosition() {
        return position;
    }

    private static String render(final Reason reason,
                                 final String pattern,
                                 final int position,
                                 final String message) {
        final StringBuilder sb = new StringBuilder()
                .append(reason)
                .append(": ")
                .append(message)
                .append('\n')
                .append(pattern);
        if (position >= 0 && position <= pattern.length()) {
            sb.append('\n').append(" ".repeat(position)).append('^');
        }
        return sb.toString();
    }
}
