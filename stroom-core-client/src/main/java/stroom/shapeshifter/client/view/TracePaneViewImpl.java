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

import stroom.shapeshifter.client.presenter.TracePanePresenter.TracePaneView;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

public class TracePaneViewImpl extends ViewImpl implements TracePaneView {

    private final Widget widget;

    @UiField
    Label title;
    @UiField
    Label empty;

    @Inject
    public TracePaneViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setTitle(final String text) {
        title.setText(text == null
                ? ""
                : text);
        title.setVisible(text != null);
    }

    @Override
    public void setEmpty(final String text) {
        empty.setText(text);
    }

    public interface Binder extends UiBinder<Widget, TracePaneViewImpl> {

    }
}
