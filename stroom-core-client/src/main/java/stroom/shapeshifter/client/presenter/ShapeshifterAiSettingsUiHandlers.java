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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.shared.PlanStep;

import com.gwtplatform.mvp.client.UiHandlers;

import java.util.List;

public interface ShapeshifterAiSettingsUiHandlers extends UiHandlers {

    void onChange();

    /**
     * An example plan was chosen, and its steps are the ones to start from (A34, design 01 §10.2).
     * <p>
     * An example is a starting point and not a setting: what it loads is the plan's steps, which the
     * person then edits. The tab that owns the step list takes them; every other tab has none and does
     * nothing.
     */
    default void onPlanExample(final List<PlanStep> steps) {
        // Only the Learning tab holds a plan.
    }
}
