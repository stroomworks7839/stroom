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

package stroom.shapeshifter.engine.output;

/** The two halves of a qualified name, as the sinks read them. */
final class QNames {

    private QNames() {
    }

    /** The prefix before the colon, or the empty string for an unprefixed name. */
    static String prefixOf(final String qName) {
        return prefixOf(qName, qName.indexOf(':'));
    }

    /** The local part after the colon, or the whole name when there is none. */
    static String localOf(final String qName) {
        return localOf(qName, qName.indexOf(':'));
    }

    // A caller that wants both halves has already found the colon, and the two-argument forms let
    // it say so rather than scanning the name a second time. The colon is the caller's to find,
    // which is why these take it rather than caching it: the sinks hold the name, not this class.

    /** The prefix, given a colon position already found — negative for an unprefixed name. */
    static String prefixOf(final String qName, final int colon) {
        return colon < 0 ? "" : qName.substring(0, colon);
    }

    /** The local part, given a colon position already found — negative for an unprefixed name. */
    static String localOf(final String qName, final int colon) {
        return colon < 0 ? qName : qName.substring(colon + 1);
    }
}
