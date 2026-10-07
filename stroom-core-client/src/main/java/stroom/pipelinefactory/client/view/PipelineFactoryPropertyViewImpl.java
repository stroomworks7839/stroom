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

package stroom.pipelinefactory.client.view;

import stroom.pipelinefactory.client.presenter.PipelineFactoryPropertyPresenter.PipelineFactoryPropertyView;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

/// The dialog for one property value a Pipeline Factory writes.
public class PipelineFactoryPropertyViewImpl extends ViewImpl implements PipelineFactoryPropertyView {

    private final Widget widget;

    @UiField
    TextBox elementId;
    @UiField
    TextBox name;
    @UiField
    TextBox value;

    @Inject
    public PipelineFactoryPropertyViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public String getElementId() {
        return elementId.getValue();
    }

    @Override
    public void setElementId(final String elementId) {
        this.elementId.setValue(elementId);
    }

    @Override
    public String getName() {
        return name.getValue();
    }

    @Override
    public void setName(final String name) {
        this.name.setValue(name);
    }

    @Override
    public String getValue() {
        return value.getValue();
    }

    @Override
    public void setValue(final String value) {
        this.value.setValue(value);
    }


    // --------------------------------------------------------------------------------


    /// Binds the dialog's layout.
    public interface Binder extends UiBinder<Widget, PipelineFactoryPropertyViewImpl> {

    }
}
