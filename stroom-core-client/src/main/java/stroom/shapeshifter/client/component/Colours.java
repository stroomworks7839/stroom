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

    /// How far round the wheel each successive index steps (design 18 §5.13.7, measured
    /// 2026-11-17).
    ///
    /// 43°, where this was 47° for most of the editor's life. Both are coprime with 360, so
    /// both need 360 items before a hue *repeats* — which is what the design used to claim and
    /// is the wrong question. What matters is how close two hues get, and 47° has a cliff: at
    /// eight items on screen its closest pair is 31° apart, at twenty-four it is **1°**, which
    /// is a repeat for a reader however distinct the numbers are. Measured over every step
    /// coprime with 360, at 8 items and at 24, 43° is on the Pareto front and 47° is not:
    ///
    /// | items | 4 | 8 | 12 | 24 | 48 |
    /// |---|---|---|---|---|---|
    /// | 47° | 47° | 31° | 16° | 1° | 1° |
    /// | 137° (golden angle) | 51° | 35° | 16° | 3° | 3° |
    /// | 43° | 43° | 43° | 16° | 11° | 5° |
    ///
    /// The golden angle is the textbook answer and is beaten here by four degrees' difference
    /// from where we started, because the wheel is 360 and an integer step's arithmetic matters
    /// more than its irrational ideal. 43° gives up 4° at four items, which nobody can see, and
    /// buys an order of magnitude at twenty-four, which anybody can.
    private static final int STEP = 43;

    private Colours() {
    }

    /// A generated hue by position (design 18 §10): a capture group's chip, its row and its span
    /// in the pattern map, and the numbering [CaptureHues] allocates from. Its lightness comes
    /// from the theme ([#HUE_LIGHTNESS]) rather than being a number here: the hue is the text's
    /// own colour, and one lightness cannot clear 4.5:1 against both the light theme's control
    /// surface and the dark theme's.
    ///
    /// The index is a position in an allocation: [CaptureHues]'s over everything a cursor can
    /// see, and [GroupHues]'s for the pattern workbench, which gives a group bound to a variable
    /// that variable's colour and numbers the rest past the panes' (ruled 2026-11-20), so one
    /// capture is one colour beside its pattern and in the panes.
    ///
    /// It lives here rather than on a controller because [CaptureHues] has to be testable: a
    /// controller's static initialiser calls `GWT.create`, which a plain JUnit test cannot.
    ///
    /// @param index The position being coloured, counted from one.
    /// @return The colour.
    static String hue(final int index) {
        // floorMod, not %: a caller counting from zero would otherwise produce a negative hue,
        // which Colours.safe rejects and paints transparent — one capture silently uncoloured
        // rather than a visibly wrong colour, which is the hardest kind of bug to notice.
        return "hsl(" + Math.floorMod((index - 1) * STEP, 360) + ", 62%, " + HUE_LIGHTNESS + ")";
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
