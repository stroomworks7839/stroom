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

import stroom.shapeshifter.client.presenter.TemplateWorkbenchPresenter.TemplateWorkbenchView;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewImpl;

public class TemplateWorkbenchViewImpl extends ViewImpl implements TemplateWorkbenchView {

    private final Widget widget;

    @UiField
    Label name;
    @UiField
    Label detail;
    @UiField
    SimplePanel matchEditor;
    @UiField
    SimplePanel declarations;
    @UiField
    SimplePanel captures;

    @Inject
    public TemplateWorkbenchViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setHeader(final String nameText, final String detailText) {
        name.setText(nameText);
        detail.setText(detailText);
    }

    @Override
    public void setMatchEditor(final View view) {
        matchEditor.setWidget(view.asWidget());
    }

    @Override
    public void setDeclarations(final View view) {
        declarations.setWidget(view.asWidget());
    }

    @Override
    public void setCaptures(final View view) {
        captures.setWidget(view.asWidget());
    }

    public interface Binder extends UiBinder<Widget, TemplateWorkbenchViewImpl> {

    }
}
