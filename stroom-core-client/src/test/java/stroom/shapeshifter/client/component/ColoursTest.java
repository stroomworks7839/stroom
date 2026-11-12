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

package stroom.shapeshifter.client.component;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Only a colour-shaped value reaches an inline style: the palette's hex, a word, and the capture hues. */
class ColoursTest {

    @Test
    void colourShapedValuesPass() {
        assertThat(Colours.safe("#4e79a7")).isEqualTo("#4e79a7");
        assertThat(Colours.safe("transparent")).isEqualTo("transparent");
        assertThat(Colours.safe("hsl(47, 62%, 58%)")).isEqualTo("hsl(47, 62%, 58%)");
        assertThat(Colours.safe("hsl(0, 62%, 58%)")).isEqualTo("hsl(0, 62%, 58%)");
    }

    @Test
    void theGeneratedHueKeepsItsThemeLightness() {
        // A capture's hue is its text colour, so its lightness is a theme token rather than a
        // number (Colours.HUE_LIGHTNESS): one number cannot clear 4.5:1 on both themes' surfaces.
        // Written as RegexController.hue writes it, so the guard and the generator stay in step.
        final String generated = "hsl(" + 47 + ", 62%, " + Colours.HUE_LIGHTNESS + ")";
        assertThat(Colours.safe(generated)).isEqualTo(generated);
        assertThat(Colours.safe("hsl(0, 62%, var(--mark__lightness))"))
                .isEqualTo("hsl(0, 62%, var(--mark__lightness))");
    }

    @Test
    void onlyThatOneTokenPasses() {
        assertThat(Colours.safe("hsl(0, 62%, var(--anything-else))")).isEqualTo("transparent");
        assertThat(Colours.safe("hsl(0, 62%, var(--mark__lightness)); background: url(x)"))
                .isEqualTo("transparent");
        assertThat(Colours.safe("hsl(0, 62%, var(--mark__lightness, 99%))")).isEqualTo("transparent");
    }

    @Test
    void anythingElseIsTransparent() {
        assertThat(Colours.safe(null)).isEqualTo("transparent");
        assertThat(Colours.safe("red\"; background: url(x)")).isEqualTo("transparent");
        assertThat(Colours.safe("hsl(47, 62%, 58%); x")).isEqualTo("transparent");
        assertThat(Colours.safe("url(x)")).isEqualTo("transparent");
    }
}
