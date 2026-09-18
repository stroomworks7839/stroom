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

import stroom.shapeshifter.client.presenter.TracePanePresenter.TracePaneView;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

/**
 * A cell of the frame the trace will fill in phase B — the breadcrumb, the input pane, the
 * variables pane, the output pane — present now with its title and design 18 §5.7's empty
 * state, so that the tab has the mockup's shape before it has the trace. Each is replaced by
 * its own presenter when the trace lands; nothing else changes.
 */
public class TracePanePresenter extends MyPresenterWidget<TracePaneView> {

    @Inject
    public TracePanePresenter(final EventBus eventBus, final TracePaneView view) {
        super(eventBus, view);
    }

    /** The pane's quiet uppercase title (null for none), and what it says while there is no run. */
    public TracePanePresenter as(final String title, final String emptyText) {
        getView().setTitle(title);
        getView().setEmpty(emptyText);
        return this;
    }

    public interface TracePaneView extends View {

        void setTitle(String title);

        void setEmpty(String text);
    }
}
