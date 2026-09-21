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
import stroom.shapeshifter.client.presenter.PatternNodeEditPresenter.Kind;
import stroom.shapeshifter.client.presenter.PatternNodeEditPresenter.PatternNodeEditView;
import stroom.shapeshifter.config.BinaryCast;
import stroom.widget.form.client.FormGroup;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.List;

public class PatternNodeEditViewImpl extends ViewImpl implements PatternNodeEditView {

    private final Widget widget;

    @UiField
    SelectionBox<Kind> kind;
    @UiField
    Label childrenNote;
    @UiField
    FormGroup textGroup;
    @UiField
    TextBox text;
    @UiField
    FormGroup countGroup;
    @UiField
    TextBox count;
    @UiField
    FormGroup minGroup;
    @UiField
    TextBox min;
    @UiField
    FormGroup maxGroup;
    @UiField
    TextBox max;
    @UiField
    FormGroup greedyGroup;
    @UiField
    CustomCheckBox greedy;
    @UiField
    FormGroup caseInsensitiveGroup;
    @UiField
    CustomCheckBox caseInsensitive;
    @UiField
    FormGroup dotAllGroup;
    @UiField
    CustomCheckBox dotAll;
    @UiField
    FormGroup refGroup;
    @UiField
    SelectionBox<String> ref;
    @UiField
    TextBox label;
    @UiField
    SelectionBox<BinaryCast> cast;

    @Inject
    public PatternNodeEditViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        kind.setDisplayValueFunction(Kind::spelling);
        kind.addItems(Kind.values());
        cast.setNonSelectString("none");
        cast.setDisplayValueFunction(BinaryCast::label);
        cast.addItems(BinaryCast.values());
        showKind(Kind.REGEX);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void focus() {
        text.setFocus(true);
    }

    @UiHandler("kind")
    void onKind(final ValueChangeEvent<Kind> e) {
        showKind(e.getValue());
    }

    private void showKind(final Kind value) {
        final boolean text = value == Kind.TAG || value == Kind.TAKE_WHILE || value == Kind.TAKE_UNTIL
                             || value == Kind.TAKE_THROUGH || value == Kind.REGEX;
        textGroup.setVisible(text);
        switch (value) {
            case TAG:
                textGroup.setLabel("Text");
                break;
            case TAKE_WHILE:
                textGroup.setLabel("Character class");
                break;
            case TAKE_UNTIL:
            case TAKE_THROUGH:
                textGroup.setLabel("Terminator");
                break;
            case REGEX:
                textGroup.setLabel("Pattern");
                break;
            default:
                break;
        }
        countGroup.setVisible(value == Kind.TAKE);
        minGroup.setVisible(value == Kind.TAKE_WHILE || value == Kind.REPEAT);
        maxGroup.setVisible(value == Kind.TAKE_WHILE || value == Kind.REPEAT);
        greedyGroup.setVisible(value == Kind.REPEAT);
        caseInsensitiveGroup.setVisible(value == Kind.REGEX);
        dotAllGroup.setVisible(value == Kind.REGEX);
        refGroup.setVisible(value == Kind.REF);
    }

    @Override
    public Kind getKind() {
        return kind.getValue();
    }

    @Override
    public void setKind(final Kind value) {
        kind.setValue(value, false);
        showKind(value);
    }

    @Override
    public String getText() {
        return text.getText();
    }

    @Override
    public void setText(final String value) {
        text.setText(value);
    }

    @Override
    public String getCount() {
        return count.getText();
    }

    @Override
    public void setCount(final String value) {
        count.setText(value);
    }

    @Override
    public String getMin() {
        return min.getText();
    }

    @Override
    public void setMin(final String value) {
        min.setText(value);
    }

    @Override
    public String getMax() {
        return max.getText();
    }

    @Override
    public void setMax(final String value) {
        max.setText(value);
    }

    @Override
    public boolean isGreedy() {
        return greedy.getValue();
    }

    @Override
    public void setGreedy(final boolean value) {
        greedy.setValue(value);
    }

    @Override
    public boolean isCaseInsensitive() {
        return caseInsensitive.getValue();
    }

    @Override
    public void setCaseInsensitive(final boolean value) {
        caseInsensitive.setValue(value);
    }

    @Override
    public boolean isDotAll() {
        return dotAll.getValue();
    }

    @Override
    public void setDotAll(final boolean value) {
        dotAll.setValue(value);
    }

    @Override
    public String getRef() {
        return ref.getValue();
    }

    @Override
    public void setRef(final String value) {
        ref.setValue(value, false);
    }

    @Override
    public void setLibrary(final List<String> names) {
        ref.clear();
        ref.addItems(names);
    }

    @Override
    public String getLabel() {
        return label.getText();
    }

    @Override
    public void setLabel(final String value) {
        label.setText(value);
    }

    @Override
    public BinaryCast getCast() {
        return cast.getValue();
    }

    @Override
    public void setCast(final BinaryCast value) {
        cast.setValue(value, false);
    }

    @Override
    public void setChildrenNote(final String note) {
        childrenNote.setText(note == null
                ? ""
                : note);
        childrenNote.setVisible(note != null);
    }

    public interface Binder extends UiBinder<Widget, PatternNodeEditViewImpl> {

    }
}
