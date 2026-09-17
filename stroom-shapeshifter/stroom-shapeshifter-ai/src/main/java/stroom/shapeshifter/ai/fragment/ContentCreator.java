/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.ai.fragment;

import stroom.docref.DocRef;
import stroom.util.shared.DocPath;

/**
 * Where learned content goes. A document created straight through its store has no explorer node and
 * is invisible in the tree; in a node the explorer creates it, in the per-feed folder of design §7.5.
 * Tests create through the stores and ignore the folder.
 */
public interface ContentCreator {

    /**
     * @param folder The folder to create in, created itself if need be.
     * @param type   The document type, e.g. {@code Pipeline}.
     * @param name   The document name.
     * @return The new, empty document.
     */
    DocRef create(DocPath folder, String type, String name);
}
