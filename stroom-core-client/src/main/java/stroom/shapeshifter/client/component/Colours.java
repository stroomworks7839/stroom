/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.client.component;

/// Colours reach inline styles; only a colour-shaped value may.
final class Colours {

    /// The lightness of a hue this code generates, as a theme token rather than a number: a
    /// capture's hue is its text colour, and one lightness cannot clear 4.5:1 against both the
    /// light theme's control surface and the dark theme's (see `theme-root.css`, `theme-dark.css`).
    static final String HUE_LIGHTNESS = "var(--mark__lightness)";

    private Colours() {
    }

    /// The colour if it is a hex triplet/sextet, a word or the hue form `hsl(h, s%, l%)`;
    /// `transparent` otherwise.
    static String safe(final String colour) {
        if (colour == null) {
            return "transparent";
        }
        if (colour.matches("#[0-9a-fA-F]{3,8}") || colour.matches("[a-zA-Z]{1,24}")
            || colour.matches("hsl\\([0-9]{1,3}, [0-9]{1,3}%, [0-9]{1,3}%\\)")
            // The hue form this code generates, whose lightness is [#HUE_LIGHTNESS]. The token is
            // spelled out rather than taken from a variable, so that nothing but that one token
            // passes: the value is still only digits, a percent and a name this code chose.
            || colour.matches("hsl\\([0-9]{1,3}, [0-9]{1,3}%, var\\(--mark__lightness\\)\\)")) {
            return colour;
        }
        return "transparent";
    }
}
