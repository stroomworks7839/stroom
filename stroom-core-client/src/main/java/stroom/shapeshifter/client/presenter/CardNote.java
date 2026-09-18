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
 * What the run says about one top-level card of the strip (design 18 §5.6): the hue the
 * output pane paints its output in, a note — what it wrote, how many matches a dispatch
 * made — and, for a dispatch that made matches, the first frame a click descends to.
 */
public final class CardNote {

    private final String colour;
    private final String text;
    private final long descendTo;

    public CardNote(final String colour, final String text, final long descendTo) {
        this.colour = colour;
        this.text = text;
        this.descendTo = descendTo;
    }

    /** The card's swatch colour, or null for none. */
    public String getColour() {
        return colour;
    }

    /** The note, or null for none. */
    public String getText() {
        return text;
    }

    /** The frame to descend to, or -1 when the card leads nowhere. */
    public long getDescendTo() {
        return descendTo;
    }
}
