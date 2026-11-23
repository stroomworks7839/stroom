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

import stroom.shapeshifter.client.model.Modes;
import stroom.shapeshifter.config.Dispatch;
import stroom.shapeshifter.config.Severity;
import stroom.shapeshifter.config.Template;
import stroom.widget.button.client.Button;
import stroom.widget.button.client.LinkButton;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.FormGroup;
import stroom.widget.form.client.TextArea;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/// One instruction's fields, generic on purpose: five text fields, two flags, one multi-line, a
/// severity, a dispatch, a condition and the wire form, each labelled and shown by the
/// controller per kind. One view for every kind beats a view each.
///
/// It is shown **in the card list, in place of the card it edits** (design 47 D), not in a
/// dialog: the kind first, then the kind's fields beside it, wrapping as they need. What it
/// holds is a draft until it is taken — Enter in a field, Ctrl+Enter in an area, or OK — and
/// Escape or Cancel drops it.
public class InstructionEdit extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    private Consumer<String> onKind;
    private Runnable onCommit = () -> {
    };
    private Runnable onCancel = () -> {
    };
    private Runnable onDeclare = () -> {
    };
    private Runnable onFieldTyped = () -> {
    };
    private Consumer<String> onSubscript = subscript -> {
    };

    @UiField
    FlowPanel form;
    @UiField
    LinkButton declare;
    @UiField
    FlowPanel subscripts;
    @UiField
    Button ok;
    @UiField
    Button cancel;
    @UiField
    SelectionBox<String> kind;
    @UiField
    Label note;
    @UiField
    FormGroup kindGroup;
    @UiField
    FormGroup field4Group;
    @UiField
    TextBox field4;
    @UiField
    FormGroup choiceGroup;
    @UiField
    SelectionBox<String> choice;
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
    TickBox flag0;
    @UiField
    FormGroup flag1Group;
    @UiField
    TickBox flag1;
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
    FormGroup sortGroup;
    @UiField
    SortListPanel sort;
    @UiField
    FormGroup wireGroup;
    @UiField
    TextArea wire;

    public InstructionEdit() {
        initWidget(BINDER.createAndBindUi(this));
        // A mode named `default` is not the templates with no mode, and must not read as them
        mode.setDisplayValueFunction(Modes::label);
        // The kind picker leads whatever else is ordered: a list asked to come first comes
        // after it, not above it.
        kindGroup.addStyleName("edit-form__group--first");
        kind.addValueChangeHandler(event -> onKindChange(event));
        severity.setDisplayValueFunction(s -> s.name().toLowerCase(Locale.ROOT));
        severity.addItems(Severity.values());
        dispatch.setNonSelectString("project's");
        dispatch.setDisplayValueFunction(d -> d.name().toLowerCase(Locale.ROOT));
        dispatch.addItems(Dispatch.values());
        condition.setEmptyText("(no clauses yet)");
        sort.setEmptyText("(walked in the order the entries are in)");
        declare.setOnClick(() -> onDeclare.run());
        ok.setOnClick(() -> onCommit.run());
        cancel.setOnClick(() -> onCancel.run());
        // Enter takes the draft from a one-line field. Only from those: Enter in a selection
        // box opens its list, and in an area it is a line break, where Ctrl+Enter takes it.
        for (int i = 0; i < FIELD_COUNT; i++) {
            field(i).addKeyDownHandler(event -> {
                if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
                    event.preventDefault();
                    onCommit.run();
                }
            });
            // Any change to what a field holds, typed or not: a paste with the pointer, a drop
            // and an autofill change the text with no key pressed
            listenForInput(field(i).getElement());
        }
        multi.addKeyDownHandler(event -> onAreaKey(event));
        wire.addKeyDownHandler(event -> onAreaKey(event));
        // Escape from anywhere in the card. A list a selection box has open is not in the
        // card, so the Escape that closes it never arrives here.
        form.addDomHandler(event -> {
            if (event.getNativeKeyCode() == KeyCodes.KEY_ESCAPE) {
                event.preventDefault();
                event.stopPropagation();
                onCancel.run();
            }
        }, KeyDownEvent.getType());
    }

    private static final int FIELD_COUNT = 5;

    private native void listenForInput(Element field) /*-{
        var self = this;
        field.addEventListener('input', function () {
            self.@stroom.shapeshifter.client.component.InstructionEdit::onFieldInput()();
        });
    }-*/;

    private void onFieldInput() {
        onFieldTyped.run();
    }

    private void onAreaKey(final KeyDownEvent event) {
        if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER
            && (event.isControlKeyDown() || event.isMetaKeyDown())) {
            event.preventDefault();
            onCommit.run();
        }
    }

    /// Offers to declare the name a variable binds, or offers nothing.
    ///
    /// @param name The name to offer, or null for no offer.
    public void setDeclare(final String name) {
        declare.setVisible(name != null);
        if (name != null) {
            declare.setText("declare " + name + " here");
            declare.setTitle("Declare " + name + " on this template, as a scalar");
        }
    }

    /// Offers what may follow the Select to say which of a list's entries it reads, or nothing.
    ///
    /// @param offered The subscripts, in order; empty for none.
    public void setSubscripts(final List<String> offered) {
        subscripts.clear();
        for (final String subscript : offered) {
            final LinkButton add = new LinkButton(subscript);
            add.addStyleName("add-button instruction-edit__subscript");
            add.setTitle("Add " + subscript + " after the name, to say which of the list's entries is read");
            add.setOnClick(() -> onSubscript.accept(subscript));
            subscripts.add(add);
        }
        subscripts.setVisible(!offered.isEmpty());
    }

    /// Sets what is told when an offer made by [#setSubscripts] is taken.
    ///
    /// @param onSubscript Told the subscript taken.
    public void setOnSubscript(final Consumer<String> onSubscript) {
        this.onSubscript = onSubscript;
    }

    /// @param onDeclare Told when the offer to declare is taken.
    public void setOnDeclare(final Runnable onDeclare) {
        this.onDeclare = onDeclare;
    }

    /// @param onFieldTyped Told whenever what one of the one-line fields holds changes.
    public void setOnFieldTyped(final Runnable onFieldTyped) {
        this.onFieldTyped = onFieldTyped;
    }

    /// @param onCommit Told when the draft is to be taken: Enter in a field, or OK.
    public void setOnCommit(final Runnable onCommit) {
        this.onCommit = onCommit;
    }

    /// @param onCancel Told when the draft is to be dropped: Escape, or Cancel.
    public void setOnCancel(final Runnable onCancel) {
        this.onCancel = onCancel;
    }

    /// Puts the keyboard in the first field the kind has, which is what an author opening a
    /// card has come to change; in the kind box when the kind has no field of that sort.
    public void focusFirst() {
        for (int i = 0; i < FIELD_COUNT; i++) {
            if (fieldGroup(i).isVisible()) {
                field(i).setFocus(true);
                return;
            }
        }
        if (multiGroup.isVisible()) {
            multi.setFocus(true);
        } else if (wireGroup.isVisible()) {
            wire.setFocus(true);
        } else {
            kind.focus();
        }
    }

    /// Puts the keyboard in the kind box and opens its list: what clicking a card's kind asks
    /// for.
    public void chooseKind() {
        kind.focus();
        kind.open();
    }


    private void onKindChange(final ValueChangeEvent<String> e) {
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
            case 3:
                return field3Group;
            default:
                return field4Group;
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
            case 3:
                return field3;
            default:
                return field4;
        }
    }

    public String getKind() {
        return kind.getValue();
    }

    /// @param kinds The kinds the card can be, in the order they are offered.
    public void setKinds(final List<String> kinds) {
        kind.clear();
        kind.addItems(kinds);
    }

    public void setKind(final String value) {
        kind.setValue(value, false);
    }

    /// Told when the instruction's kind is chosen.
    ///
    /// @param onKind The handler.
    public void setOnKind(final Consumer<String> onKind) {
        this.onKind = onKind;
    }

    public void setModes(final List<String> modes) {
        final String current = mode.getValue();
        mode.clear();
        mode.setNonSelectString(Template.DEFAULT_MODE);
        mode.addItems(modes);
        mode.setValue(current, false);
    }

    public String getMode() {
        final String value = mode.getValue();
        return value == null
                ? ""
                : value;
    }

    public void setMode(final String value) {
        mode.setValue(value == null || value.isEmpty()
                ? null
                : value, false);
    }

    public void setModeVisible(final boolean visible) {
        modeGroup.setVisible(visible);
    }

    public void setForced(final boolean forced) {
        kind.setEnabled(!forced);
    }

    /// Whether the multi-line area comes before the text fields rather than after them. A form
    /// whose substance is a list — an add's inputs, a string-join's values, a call's arguments —
    /// otherwise asks what the result is called before saying what it operates on, because the
    /// one view renders its fields first. The form is a flex column, so this is an order and not
    /// a rearrangement.
    ///
    /// @param first Whether the list leads.
    public void setMultiFirst(final boolean first) {
        multiGroup.setStyleName("edit-form__group--leads", first);
    }

    /// The choice box's items and value, for a field that is a closed set of words.
    ///
    /// @param items   The words to offer.
    /// @param value   The one chosen, or null.
    /// @param label   What the field is called.
    /// @param visible Whether the kind has one.
    public void setChoice(final List<String> items, final String value, final String label,
                          final boolean visible) {
        choice.clear();
        choice.addItems(items);
        choice.setValue(value, false);
        choiceGroup.setLabel(label);
        choiceGroup.setVisible(visible);
    }

    /// @return The word chosen, or null.
    public String getChoice() {
        return choice.getValue();
    }

    /// @param visible Whether the kind has a choice.
    public void setChoiceVisible(final boolean visible) {
        choiceGroup.setVisible(visible);
    }

    public String getField(final int index) {
        return field(index).getText();
    }

    public void setField(final int index, final String value) {
        field(index).setText(value);
    }

    public void setField(final int index, final String label, final String value, final boolean visible) {
        fieldGroup(index).setLabel(label);
        field(index).setText(value);
        fieldGroup(index).setVisible(visible);
    }

    public void setFieldLabel(final int index, final String label, final String help) {
        fieldGroup(index).setLabel(label);
        fieldGroup(index).setHelpText(help);
    }

    public void setFieldVisible(final int index, final boolean visible) {
        fieldGroup(index).setVisible(visible);
    }

    /// @param index Which field.
    /// @param placeholder What the field says while it is empty, or an empty string for nothing.
    public void setFieldPlaceholder(final int index, final String placeholder) {
        field(index).setPlaceholder(placeholder);
    }

    public boolean getFlag(final int index) {
        return (index == 0
                ? flag0
                : flag1).getValue();
    }

    public void setFlag(final int index, final boolean value) {
        (index == 0
                ? flag0
                : flag1).setValue(value);
    }

    public void setFlag(final int index, final String label, final boolean value, final boolean visible) {
        (index == 0
                ? flag0Group
                : flag1Group).setLabel(label);
        setFlag(index, value);
        setFlagVisible(index, visible);
    }

    public void setFlagLabel(final int index, final String label) {
        (index == 0
                ? flag0Group
                : flag1Group).setLabel(label);
    }

    public void setFlagVisible(final int index, final boolean visible) {
        (index == 0
                ? flag0Group
                : flag1Group).setVisible(visible);
    }

    public String getMulti() {
        return multi.getText();
    }

    public void setMulti(final String value) {
        multi.setText(value);
    }

    public void setMulti(final String label, final String value, final boolean visible) {
        multiGroup.setLabel(label);
        multi.setText(value);
        multiGroup.setVisible(visible);
    }

    public void setMultiLabel(final String label) {
        multiGroup.setLabel(label);
    }

    public void setMultiVisible(final boolean visible) {
        multiGroup.setVisible(visible);
    }

    public Severity getSeverity() {
        return severity.getValue();
    }

    public void setSeverity(final Severity value) {
        severity.setValue(value, false);
    }

    public void setSeverity(final Severity value, final boolean visible) {
        setSeverity(value);
        severityGroup.setVisible(visible);
    }

    public void setSeverityVisible(final boolean visible) {
        severityGroup.setVisible(visible);
    }

    public Dispatch getDispatch() {
        return dispatch.getValue();
    }

    public void setDispatch(final Dispatch value) {
        dispatch.setValue(value, false);
    }

    public void setDispatch(final Dispatch value, final boolean visible) {
        setDispatch(value);
        dispatchGroup.setVisible(visible);
    }

    public void setDispatchVisible(final boolean visible) {
        dispatchGroup.setVisible(visible);
    }

    public ClauseListPanel getCondition() {
        return condition;
    }

    public SortListPanel getSort() {
        return sort;
    }

    public void setSortVisible(final boolean visible) {
        sortGroup.setVisible(visible);
    }

    public void setConditionVisible(final boolean visible) {
        conditionGroup.setVisible(visible);
    }

    public String getWire() {
        return wire.getText();
    }

    public void setWire(final String json) {
        wire.setText(json);
    }

    public boolean isWireVisible() {
        return wireGroup.isVisible();
    }

    public void setWireVisible(final boolean visible) {
        wireGroup.setVisible(visible);
    }

    public void setNote(final String text) {
        note.setText(text == null
                ? ""
                : text);
        note.setVisible(text != null);
    }

    interface Binder extends UiBinder<Widget, InstructionEdit> {

    }
}
