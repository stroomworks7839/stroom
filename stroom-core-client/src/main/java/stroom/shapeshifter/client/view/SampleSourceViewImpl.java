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

import stroom.item.client.SelectionBox;
import stroom.shapeshifter.client.presenter.SampleSourcePresenter.Kind;
import stroom.shapeshifter.client.presenter.SampleSourcePresenter.SampleSourceView;
import stroom.shapeshifter.client.presenter.SampleSourceUiHandlers;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.SimpleLayoutPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

public class SampleSourceViewImpl
        extends ViewWithUiHandlers<SampleSourceUiHandlers>
        implements SampleSourceView {

    private final Widget widget;
    private final SimplePanel pastePane = new SimplePanel();

    @UiField
    SelectionBox<Kind> kind;
    @UiField
    SimpleLayoutPanel body;

    private Widget streamView;

    @Inject
    public SampleSourceViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        kind.setDisplayValueFunction(Kind::label);
        kind.addItems(Kind.values());
        pastePane.setStyleName("max");
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @UiHandler("kind")
    void onKind(final ValueChangeEvent<Kind> e) {
        show(e.getValue());
        if (getUiHandlers() != null && e.getValue() != null) {
            getUiHandlers().onKind(e.getValue());
        }
    }

    @Override
    public void setStreamView(final Widget view) {
        streamView = view;
        show(getKind());
    }

    @Override
    public void setPasteView(final View view) {
        pastePane.setWidget(view.asWidget());
    }

    private void show(final Kind which) {
        if (which == Kind.PASTED) {
            body.setWidget(pastePane);
        } else if (streamView != null) {
            body.setWidget(streamView);
        }
        body.onResize();
    }

    @Override
    public Kind getKind() {
        return kind.getValue() == null
                ? Kind.STREAM
                : kind.getValue();
    }

    @Override
    public void setKind(final Kind value) {
        kind.setValue(value, false);
        show(value);
    }

    public interface Binder extends UiBinder<Widget, SampleSourceViewImpl> {

    }
}
