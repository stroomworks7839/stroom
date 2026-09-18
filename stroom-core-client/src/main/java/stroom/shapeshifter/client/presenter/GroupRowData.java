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

/** One row of the groups panel: a capture group and the declaration it is bound to, if any. */
public final class GroupRowData {

    private final int index;
    private final String boundName;
    private final String syntaxName;
    private final String hue;
    private final boolean selected;

    public GroupRowData(final int index, final String boundName, final String syntaxName, final String hue,
                        final boolean selected) {
        this.index = index;
        this.boundName = boundName;
        this.syntaxName = syntaxName;
        this.hue = hue;
        this.selected = selected;
    }

    public int getIndex() {
        return index;
    }

    /** The declaration a capture binds this group into, or null. */
    public String getBoundName() {
        return boundName;
    }

    /** The name the pattern gives the group, {@code (?<name>…)}, or null: the placeholder. */
    public String getSyntaxName() {
        return syntaxName;
    }

    public String getHue() {
        return hue;
    }

    public boolean isSelected() {
        return selected;
    }
}
