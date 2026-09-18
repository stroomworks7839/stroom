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

import com.gwtplatform.mvp.client.UiHandlers;

public interface PatternTreeUiHandlers extends UiHandlers {

    /** A node's row was clicked; the path is its dotted child-index path from the root. */
    void onSelect(String path);

    /** A node's row was double-clicked: open it for editing. */
    void onOpen(String path);
}
