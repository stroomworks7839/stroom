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

package stroom.shapeshifter.engine;

/**
 * Something the engine has to say about a run.
 *
 * <p>Messages are collected rather than thrown, because one bad record in a million-record
 * stream is a message, not a failure. What makes a run fail is a configuration that will not
 * compile, or an input that cannot be read at all.
 *
 * @param severity how much it matters
 * @param text     what happened, in words a person reading a processing log can act on
 */
public record Message(Severity severity, String text) {

    /**
     * The form the fixture goldens record: severity, a tab, and the text. The goldens pin this
     * exact shape, so changing it is a fixture migration, not a formatting choice.
     */
    @Override
    public String toString() {
        final String name = severity.name();
        return name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT) + "\t" + text;
    }
}
