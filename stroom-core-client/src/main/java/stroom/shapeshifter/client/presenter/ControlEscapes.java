/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client.presenter;

/**
 * How the delimiter form spells control characters: a text box strips a newline typed or set
 * into it, so the separators that matter most — a newline, a tab — need a spelling that fits on
 * one line. The four escapes JSON has for them: {@code \n}, {@code \r}, {@code \t} and
 * {@code \\}; every other character stands for itself, including a lone backslash.
 */
public final class ControlEscapes {

    private ControlEscapes() {
    }

    /** The model's text as the form shows it. */
    public static String escape(final String text) {
        if (text == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            switch (c) {
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    /** The form's text as the model holds it. */
    public static String unescape(final String text) {
        if (text == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            final char next = i + 1 < text.length()
                    ? text.charAt(i + 1)
                    : 0;
            if (c == '\\' && (next == 'n' || next == 'r' || next == 't' || next == '\\')) {
                out.append(next == 'n'
                        ? '\n'
                        : next == 'r'
                                ? '\r'
                                : next == 't'
                                        ? '\t'
                                        : '\\');
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
