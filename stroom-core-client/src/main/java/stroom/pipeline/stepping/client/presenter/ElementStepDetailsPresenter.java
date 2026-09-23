/*
 * Copyright 2026 Crown Copyright
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

package stroom.pipeline.stepping.client.presenter;

import stroom.pipeline.shared.stepping.ElementStepDetails;

import com.gwtplatform.mvp.client.View;

/**
 * Shows what one element had to say about a step beyond the text it read and wrote (A30, design 01
 * §11.7), in place of the code pane.
 * <p>
 * An element whose behaviour is a decision has no single document to show as code, and cannot say what
 * it decided in a log line without losing the links and the actions that go with it. Whoever supplies a
 * subtype of {@link ElementStepDetails} supplies one of these for it and registers the pair with
 * {@link ElementStepDetailsPresenterRegistry}; the stepper knows nothing about either.
 */
public interface ElementStepDetailsPresenter {

    /**
     * Show these details. Called once per step, with the details of the element at the cursor.
     */
    void setDetails(ElementStepDetails details);

    View getView();
}
