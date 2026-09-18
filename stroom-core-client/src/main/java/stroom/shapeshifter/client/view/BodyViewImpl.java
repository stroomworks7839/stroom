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

package stroom.shapeshifter.client.view;

import stroom.shapeshifter.client.presenter.BodyPresenter.BodyView;
import stroom.shapeshifter.client.presenter.BodyUiHandlers;
import stroom.shapeshifter.config.OutputNode;

import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.List;
import java.util.function.Consumer;

public class BodyViewImpl extends ViewWithUiHandlers<BodyUiHandlers> implements BodyView {

    private final FlowPanel widget = new FlowPanel();
    private final FlowPanel cards = new FlowPanel();
    private boolean enabled = true;

    @Inject
    public BodyViewImpl() {
        widget.setStyleName("ss-body");
        final Label title = new Label("body");
        title.setStyleName("ss-strip-k ss-body-title");
        widget.add(title);
        widget.add(cards);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void setBody(final List<OutputNode> body) {
        cards.clear();
        cards.add(new CardList(new int[0], body, getUiHandlers(), enabled, false));
    }

    @Override
    public void promptCase(final String current, final Consumer<String> then) {
        // A case is one value; the browser's prompt is the smallest honest dialog for it.
        then.accept(Window.prompt("Case value", current));
    }
}
