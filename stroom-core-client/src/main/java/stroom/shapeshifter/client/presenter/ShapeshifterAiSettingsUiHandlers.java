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

import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.Template;

import com.gwtplatform.mvp.client.UiHandlers;

import java.util.List;

public interface ShapeshifterAiSettingsUiHandlers extends UiHandlers {

    void onChange();

    /**
     * An example flow was chosen, and its steps are the ones to start from (A34, design 01 §10.2).
     * <p>
     * An example is a starting point and not a setting: what it loads is the flow's steps, which the
     * person then edits. The tab that owns the step list takes them; every other tab has none and does
     * nothing.
     */
    default void onFlowExample(final List<FlowStep> steps) {
        // Only the Flow tab holds a flow.
    }

    /**
     * The button beneath the flow was pressed, at this point on the screen: which examples there are to
     * load, and what replacing a flow that has been edited means, belong to the tab that owns the flow.
     *
     * @param x Where the menu of examples goes, across.
     * @param y Where the menu of examples goes, down.
     */
    /// The flow's text form was edited (A87). Only the Flow tab holds a flow.
    default void onFlowText(final String text) {
        // Only the Flow tab holds a flow.
    }

    /// A step is added after the selected one, or at the end. Only the Flow tab holds a flow.
    default void onAddStep() {
        // Only the Flow tab holds a flow.
    }

    /// The selected step is copied, without its id, after itself.
    default void onCopyStep() {
        // Only the Flow tab holds a flow.
    }

    /// The selected step is removed, after confirmation where a transition names it.
    default void onDeleteStep() {
        // Only the Flow tab holds a flow.
    }

    /// The selected step moves up (-1) or down (1).
    default void onMoveStep(final int by) {
        // Only the Flow tab holds a flow.
    }

    /// A built-in text is to be loaded into one of the selected node's texts, chosen from a popup.
    default void onLoadBuiltIn(final Template slot) {
        // Only the Flow tab holds a flow.
    }

    default void onLoadExample(final int x, final int y) {
        // Only the Flow tab holds a flow.
    }
}
