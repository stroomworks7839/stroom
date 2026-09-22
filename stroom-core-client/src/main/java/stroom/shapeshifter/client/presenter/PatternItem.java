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

/**
 * One node of the pattern tree as the view lays it out: where it is, what it says, and whether
 * it holds children. The path is the identity — two nodes of a tree can be the same value and
 * still be different nodes — and the parent's path is carried rather than parsed, so the
 * spelling of a path stays {@link PatternNodes}'s business.
 */
public final class PatternItem {

    private final String path;
    private final String parentPath;
    private final String label;
    private final boolean container;
    private final boolean labelled;

    public PatternItem(final String path, final String parentPath, final String label,
                       final boolean container, final boolean labelled) {
        this.path = path;
        this.parentPath = parentPath;
        this.label = label;
        this.container = container;
        this.labelled = labelled;
    }

    public String getPath() {
        return path;
    }

    /** The path of the node this one sits in, or null for the root. */
    public String getParentPath() {
        return parentPath;
    }

    public String getLabel() {
        return label;
    }

    /** Whether the node holds children: a sequence, a choice, a repeat and the rest. */
    public boolean isContainer() {
        return container;
    }

    /** Whether the node carries a capture label: a box of its own colour, as an operator is. */
    public boolean isLabelled() {
        return labelled;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof PatternItem that && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    @Override
    public String toString() {
        return label;
    }
}
