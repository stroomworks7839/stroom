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
import stroom.shapeshifter.client.presenter.InstructionEditPresenter;
import stroom.shapeshifter.client.presenter.InstructionEditPresenter.InstructionEditView;
import stroom.shapeshifter.config.Dispatch;
import stroom.shapeshifter.config.Severity;
import stroom.widget.form.client.FormGroup;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The instruction dialog's fields, generic on purpose: four text fields, two flags, one
 * multi-line, a severity, a dispatch, a condition and the wire form, each labelled and shown by
 * the presenter per kind. One view for forty-six kinds beats forty-six views.
 */
public class InstructionEditViewImpl extends ViewImpl implements InstructionEditView {

    private final Widget widget;
    private Consumer<String> onKind;

    @UiField
    SelectionBox<String> kind;
    @UiField
    Label note;
    @UiField
    FormGroup field0Group;
    @UiField
    TextBox field0;
    @UiField
    FormGroup field1Group;
    @UiField
    TextBox field1;
    @UiField
    FormGroup field2Group;
    @UiField
    TextBox field2;
    @UiField
    FormGroup field3Group;
    @UiField
    TextBox field3;
    @UiField
    FormGroup flag0Group;
    @UiField
    CustomCheckBox flag0;
    @UiField
    FormGroup flag1Group;
    @UiField
    CustomCheckBox flag1;
    @UiField
    FormGroup multiGroup;
    @UiField
    TextArea multi;
    @UiField
    FormGroup modeGroup;
    @UiField
    SelectionBox<String> mode;
    @UiField
    FormGroup severityGroup;
    @UiField
    SelectionBox<Severity> severity;
    @UiField
    FormGroup dispatchGroup;
    @UiField
    SelectionBox<Dispatch> dispatch;
    @UiField
    FormGroup conditionGroup;
    @UiField
    ClauseListPanel condition;
    @UiField
    FormGroup wireGroup;
    @UiField
    TextArea wire;

    @Inject
    public InstructionEditViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        kind.addItems(InstructionEditPresenter.kinds());
        severity.setDisplayValueFunction(s -> s.name().toLowerCase(Locale.ROOT));
        severity.addItems(Severity.values());
        dispatch.setNonSelectString("project's");
        dispatch.setDisplayValueFunction(d -> d.name().toLowerCase(Locale.ROOT));
        dispatch.addItems(Dispatch.values());
        condition.setEmptyText("(no clauses yet)");
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @UiHandler("kind")
    void onKindChange(final ValueChangeEvent<String> e) {
        if (onKind != null) {
            onKind.accept(e.getValue());
        }
    }

    private FormGroup fieldGroup(final int index) {
        switch (index) {
            case 0:
                return field0Group;
            case 1:
                return field1Group;
            case 2:
                return field2Group;
            default:
                return field3Group;
        }
    }

    private TextBox field(final int index) {
        switch (index) {
            case 0:
                return field0;
            case 1:
                return field1;
            case 2:
                return field2;
            default:
                return field3;
        }
    }

    @Override
    public String getKind() {
        return kind.getValue();
    }

    @Override
    public void setKind(final String value) {
        kind.setValue(value, false);
    }

    @Override
    public void setOnKind(final Consumer<String> onKind) {
        this.onKind = onKind;
    }

    @Override
    public void setModes(final List<String> modes) {
        final String current = mode.getValue();
        mode.clear();
        mode.setNonSelectString("root");
        mode.addItems(modes);
        mode.setValue(current, false);
    }

    @Override
    public String getMode() {
        final String value = mode.getValue();
        return value == null
                ? ""
                : value;
    }

    @Override
    public void setMode(final String value) {
        mode.setValue(value == null || value.isEmpty()
                ? null
                : value, false);
    }

    @Override
    public void setModeVisible(final boolean visible) {
        modeGroup.setVisible(visible);
    }

    @Override
    public void setForced(final boolean forced) {
        kind.setEnabled(!forced);
    }

    @Override
    public String getField(final int index) {
        return field(index).getText();
    }

    @Override
    public void setField(final int index, final String value) {
        field(index).setText(value);
    }

    @Override
    public void setField(final int index, final String label, final String value, final boolean visible) {
        fieldGroup(index).setLabel(label);
        field(index).setText(value);
        fieldGroup(index).setVisible(visible);
    }

    @Override
    public void setFieldLabel(final int index, final String label, final String help) {
        fieldGroup(index).setLabel(label);
        fieldGroup(index).setHelpText(help);
    }

    @Override
    public void setFieldVisible(final int index, final boolean visible) {
        fieldGroup(index).setVisible(visible);
    }

    @Override
    public boolean getFlag(final int index) {
        return (index == 0
                ? flag0
                : flag1).getValue();
    }

    @Override
    public void setFlag(final int index, final boolean value) {
        (index == 0
                ? flag0
                : flag1).setValue(value);
    }

    @Override
    public void setFlag(final int index, final String label, final boolean value, final boolean visible) {
        (index == 0
                ? flag0Group
                : flag1Group).setLabel(label);
        setFlag(index, value);
        setFlagVisible(index, visible);
    }

    @Override
    public void setFlagLabel(final int index, final String label) {
        (index == 0
                ? flag0Group
                : flag1Group).setLabel(label);
    }

    @Override
    public void setFlagVisible(final int index, final boolean visible) {
        (index == 0
                ? flag0Group
                : flag1Group).setVisible(visible);
    }

    @Override
    public String getMulti() {
        return multi.getText();
    }

    @Override
    public void setMulti(final String value) {
        multi.setText(value);
    }

    @Override
    public void setMulti(final String label, final String value, final boolean visible) {
        multiGroup.setLabel(label);
        multi.setText(value);
        multiGroup.setVisible(visible);
    }

    @Override
    public void setMultiLabel(final String label) {
        multiGroup.setLabel(label);
    }

    @Override
    public void setMultiVisible(final boolean visible) {
        multiGroup.setVisible(visible);
    }

    @Override
    public Severity getSeverity() {
        return severity.getValue();
    }

    @Override
    public void setSeverity(final Severity value) {
        severity.setValue(value, false);
    }

    @Override
    public void setSeverity(final Severity value, final boolean visible) {
        setSeverity(value);
        severityGroup.setVisible(visible);
    }

    @Override
    public void setSeverityVisible(final boolean visible) {
        severityGroup.setVisible(visible);
    }

    @Override
    public Dispatch getDispatch() {
        return dispatch.getValue();
    }

    @Override
    public void setDispatch(final Dispatch value) {
        dispatch.setValue(value, false);
    }

    @Override
    public void setDispatch(final Dispatch value, final boolean visible) {
        setDispatch(value);
        dispatchGroup.setVisible(visible);
    }

    @Override
    public void setDispatchVisible(final boolean visible) {
        dispatchGroup.setVisible(visible);
    }

    @Override
    public ClauseListPanel getCondition() {
        return condition;
    }

    @Override
    public void setConditionVisible(final boolean visible) {
        conditionGroup.setVisible(visible);
    }

    @Override
    public String getWire() {
        return wire.getText();
    }

    @Override
    public void setWire(final String json) {
        wire.setText(json);
    }

    @Override
    public boolean isWireVisible() {
        return wireGroup.isVisible();
    }

    @Override
    public void setWireVisible(final boolean visible) {
        wireGroup.setVisible(visible);
    }

    @Override
    public void setNote(final String text) {
        note.setText(text == null
                ? ""
                : text);
        note.setVisible(text != null);
    }

    public interface Binder extends UiBinder<Widget, InstructionEditViewImpl> {

    }
}
