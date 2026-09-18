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

public interface BodyUiHandlers extends UiHandlers {

    void onEdit(String path);

    /** The run's note on a dispatch card was clicked: go to the frame it made. */
    void onDescend(long frameId);

    /** The pointer is over a top-level card, or over none for -1. */
    void onHover(int index);

    void onRemove(String path);

    void onMove(String path, int by);

    /** The add line of a list, or a card's add-after: where the menu opens. */
    void onAdd(String listPath, int index, int x, int y);

    void onEditBranch(String path, int branch);

    void onAddBranch(String path);

    void onRemoveBranch(String path, int branch);
}
