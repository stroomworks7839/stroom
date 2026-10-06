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

package stroom.shapeshifter.client.view;

import stroom.item.client.SelectionBox;
import stroom.shapeshifter.client.presenter.BuiltInTextPresenter.BuiltInTextView;
import stroom.shapeshifter.shared.Template;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.List;
import java.util.function.Consumer;

public class BuiltInTextViewImpl extends ViewImpl implements BuiltInTextView {

    private final Widget widget;
    @UiField
    SelectionBox<Template> choice;
    @UiField
    TextArea preview;
    private Consumer<Template> onChoice;

    @Inject
    public BuiltInTextViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        preview.setReadOnly(true);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setChoices(final List<Template> templates) {
        choice.clear();
        choice.addItems(templates);
    }

    @Override
    public Template getChoice() {
        return choice.getValue();
    }

    @Override
    public void setChoice(final Template template) {
        choice.setValue(template);
    }

    @Override
    public void setPreview(final String text) {
        preview.setValue(text);
    }

    @Override
    public void setOnChoice(final Consumer<Template> onChoice) {
        this.onChoice = onChoice;
    }

    @UiHandler("choice")
    public void onChoice(final ValueChangeEvent<Template> event) {
        if (onChoice != null) {
            onChoice.accept(event.getValue());
        }
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, BuiltInTextViewImpl> {

    }
}
