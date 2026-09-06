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
        final int colon = qName.indexOf(':');
        return colon < 0 ? "" : qName.substring(0, colon);
    }

    /** The local part after the colon, or the whole name when there is none. */
    static String localOf(final String qName) {
        final int colon = qName.indexOf(':');
        return colon < 0 ? qName : qName.substring(colon + 1);
    }
}
