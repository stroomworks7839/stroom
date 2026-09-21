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

import java.util.Objects;

/** One row of the mode editor: a mode and what holds it in existence - or nothing yet, while it waits. */
public final class ModeRowData {

    private final String name;
    private final int templates;
    private final int applySites;

    public ModeRowData(final String name, final int templates, final int applySites) {
        this.name = name;
        this.templates = templates;
        this.applySites = applySites;
    }

    public String getName() {
        return name;
    }

    public int getTemplates() {
        return templates;
    }

    public int getApplySites() {
        return applySites;
    }

    /** Declared this session and not yet given to a template: nothing in the project holds it. */
    public boolean isWaiting() {
        return templates == 0 && applySites == 0;
    }

    @Override
    public boolean equals(final Object o) {
        if (!(o instanceof ModeRowData)) {
            return false;
        }
        final ModeRowData that = (ModeRowData) o;
        return templates == that.templates && applySites == that.applySites && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, templates, applySites);
    }
}
