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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.client.component.PatternNodeEditController.Kind;
import stroom.shapeshifter.config.BinaryCast;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.FormGroup;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

public class PatternNodeEdit extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);


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
    TickBox greedy;
    @UiField
    FormGroup caseInsensitiveGroup;
    @UiField
    TickBox caseInsensitive;
    @UiField
    FormGroup dotAllGroup;
    @UiField
    TickBox dotAll;
    @UiField
    FormGroup refGroup;
    @UiField
    SelectionBox<String> ref;
    @UiField
    TextBox label;
    @UiField
    SelectionBox<BinaryCast> cast;

    public PatternNodeEdit() {
        initWidget(BINDER.createAndBindUi(this));
        kind.addValueChangeHandler(event -> onKind(event));
        kind.setDisplayValueFunction(Kind::spelling);
        kind.addItems(Kind.values());
        cast.setNonSelectString("none");
        cast.setDisplayValueFunction(BinaryCast::label);
        cast.addItems(BinaryCast.values());
        showKind(Kind.REGEX);
    }


    public void focus() {
        text.setFocus(true);
    }

    private void onKind(final ValueChangeEvent<Kind> e) {
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

    public Kind getKind() {
        return kind.getValue();
    }

    public void setKind(final Kind value) {
        kind.setValue(value, false);
        showKind(value);
    }

    public String getText() {
        return text.getText();
    }

    public void setText(final String value) {
        text.setText(value);
    }

    public String getCount() {
        return count.getText();
    }

    public void setCount(final String value) {
        count.setText(value);
    }

    public String getMin() {
        return min.getText();
    }

    public void setMin(final String value) {
        min.setText(value);
    }

    public String getMax() {
        return max.getText();
    }

    public void setMax(final String value) {
        max.setText(value);
    }

    public boolean isGreedy() {
        return greedy.getValue();
    }

    public void setGreedy(final boolean value) {
        greedy.setValue(value);
    }

    public boolean isCaseInsensitive() {
        return caseInsensitive.getValue();
    }

    public void setCaseInsensitive(final boolean value) {
        caseInsensitive.setValue(value);
    }

    public boolean isDotAll() {
        return dotAll.getValue();
    }

    public void setDotAll(final boolean value) {
        dotAll.setValue(value);
    }

    public String getRef() {
        return ref.getValue();
    }

    public void setRef(final String value) {
        ref.setValue(value, false);
    }

    public void setLibrary(final List<String> names) {
        ref.clear();
        ref.addItems(names);
    }

    public String getLabel() {
        return label.getText();
    }

    public void setLabel(final String value) {
        label.setText(value);
    }

    public BinaryCast getCast() {
        return cast.getValue();
    }

    public void setCast(final BinaryCast value) {
        cast.setValue(value, false);
    }

    public void setChildrenNote(final String note) {
        childrenNote.setText(note == null
                ? ""
                : note);
        childrenNote.setVisible(note != null);
    }

    interface Binder extends UiBinder<Widget, PatternNodeEdit> {

    }
}
