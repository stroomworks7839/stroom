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

/** One row of the template panel: the project itself (id null), a template, or a library entry. */
public final class TemplateRowData {

    private final String id;
    private final String name;
    private final String mode;
    private final String colour;
    private final String count;
    private final boolean zero;

    public TemplateRowData(final String id, final String name, final String mode, final String colour,
                           final String count, final boolean zero) {
        this.id = id;
        this.name = name;
        this.mode = mode;
        this.colour = colour;
        this.count = count;
        this.zero = zero;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** The mode group the row sits under; null for the project row and the root group. */
    public String getMode() {
        return mode;
    }

    public String getColour() {
        return colour;
    }

    /** What the row says at its right: a match count once there is a trace, the kind until then. */
    public String getCount() {
        return count;
    }

    /** Dimmed: no matches, or nothing to show yet. */
    public boolean isZero() {
        return zero;
    }
}
