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

public interface RegexUiHandlers extends UiHandlers {

    /** The pattern text or a flag changed; the presenter commits on a debounce. */
    void onChange();

    void onExplode();

    /** A group's name was edited and left: bind the group to a declaration of that name, or unbind it. */
    void onGroupName(int index, String name);

    /** A group was clicked in the pattern map or its row: isolate it. */
    void onGroupSelect(int index);
}
