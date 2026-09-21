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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The delimiter form's spelling of control characters round-trips, and a stray backslash is itself. */
class ControlEscapesTest {

    @Test
    void controlCharactersSpellAsEscapes() {
        assertThat(ControlEscapes.escape("\n")).isEqualTo("\\n");
        assertThat(ControlEscapes.escape("a\tb\r\n")).isEqualTo("a\\tb\\r\\n");
        assertThat(ControlEscapes.escape("c:\\dir")).isEqualTo("c:\\\\dir");
        assertThat(ControlEscapes.escape(null)).isEmpty();
    }

    @Test
    void escapesReadBackAsControlCharacters() {
        assertThat(ControlEscapes.unescape("\\n")).isEqualTo("\n");
        assertThat(ControlEscapes.unescape("a\\tb\\r\\n")).isEqualTo("a\tb\r\n");
        assertThat(ControlEscapes.unescape("c:\\\\dir")).isEqualTo("c:\\dir");
    }

    @Test
    void anythingElseStandsForItself() {
        assertThat(ControlEscapes.unescape("a\\qb")).isEqualTo("a\\qb");
        assertThat(ControlEscapes.unescape("trailing\\")).isEqualTo("trailing\\");
        assertThat(ControlEscapes.unescape(",")).isEqualTo(",");
        for (final String text : new String[]{"\n", ",", "\\n", "|\t|", "\\"}) {
            assertThat(ControlEscapes.unescape(ControlEscapes.escape(text))).isEqualTo(text);
        }
    }
}
