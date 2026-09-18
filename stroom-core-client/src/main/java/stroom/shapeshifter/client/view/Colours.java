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

package stroom.shapeshifter.client.view;

/** Colours reach inline styles; only a colour-shaped value may. */
final class Colours {

    private Colours() {
    }

    /** The colour if it is a hex triplet/sextet or a word; {@code transparent} otherwise. */
    static String safe(final String colour) {
        if (colour == null) {
            return "transparent";
        }
        if (colour.matches("#[0-9a-fA-F]{3,8}") || colour.matches("[a-zA-Z]{1,24}")) {
            return colour;
        }
        return "transparent";
    }
}
