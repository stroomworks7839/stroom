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

public interface GuardAndLimitsUiHandlers extends UiHandlers {

    /** A clause row changed (any field left, or an operator picked). */
    void onClauseChange(int index, GuardClause clause);

    void onClauseRemove(int index);

    void onClauseAdd();

    /** The guard's wire form was edited and left. */
    void onGuardJson(String json);

    /** A limit field was left. */
    void onLimitsChange(String min, String max, String only);
}
