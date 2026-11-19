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

import stroom.shapeshifter.client.model.MatchKind;
import stroom.shapeshifter.client.model.MatchLine;
import stroom.shapeshifter.client.model.MatchRole;
import stroom.shapeshifter.config.Dispatch;
import stroom.widget.button.client.LinkButton;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;
import stroom.widget.util.client.ContentFocus;
import stroom.widget.util.client.CssProperties;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class TemplateStrip extends Component {

    private Runnable onEditIdentity = () -> {
    };
    private Runnable onNameEnter = () -> {
    };
    private Runnable onNameCancel = () -> {
    };
    private Consumer<Dispatch> onModeDispatch = dispatch -> {
    };
    private Runnable onDispatchLink = () -> {
    };
    private Consumer<MatchRole> onRole = role -> {
    };
    private Runnable onOpenWorkbench = () -> {
    };
    private BiConsumer<MatchLine, Boolean> onMatch = (line, entered) -> {
    };
    private Consumer<String> onMatchTyped = pattern -> {
    };
    // What the match line would open on: null when the match has no one-line spelling, or
    // cannot be changed here.
    private MatchLine line;
    private boolean matchOpen;

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    Label title;
    @UiField
    Label chip;
    @UiField
    NameEdit name;
    @UiField
    SimplePanel settings;
    @UiField
    DefinitionList templates;
    @UiField
    FlowPanel dispatchLine;
    @UiField
    SelectionBox<Dispatch> dispatch;
    @UiField
    Label dispatchNote;
    @UiField
    LinkButton dispatchLink;
    @UiField
    Label mode;
    @UiField
    FlowPanel guardLine;
    @UiField
    FlowPanel roleLine;
    @UiField
    SelectionBox<MatchRole> role;
    @UiField
    Label roleNote;
    @UiField
    Label note;
    @UiField
    FlowPanel matchLine;
    @UiField
    FocusPanel matchChip;
    @UiField
    Label matchKind;
    @UiField
    FlowPanel matchEditor;
    @UiField
    SelectionBox<MatchKind> matchKindBox;
    @UiField
    TextBox matchField;
    @UiField
    FlowPanel delimiterFields;
    @UiField
    TextBox delimiterField;
    @UiField
    TextBox containerStartField;
    @UiField
    TextBox containerEndField;
    @UiField
    Label matchError;
    @UiField
    LinkButton workbench;
    @UiField
    Label matchSummary;
    @UiField
    Label guardLimits;
    @UiField
    SimplePanel content;
    @UiField
    DefinitionList declarations;
    @UiField
    DefinitionList captures;

    public TemplateStrip() {
        initWidget(BINDER.createAndBindUi(this));
        chip.addClickHandler(event -> onChip(event));
        name.setOnToggle(() -> editIdentity());
        name.setOnEnter(() -> onNameEnter.run());
        name.setOnCancel(() -> onNameCancel.run());
        templates.setRemovable(false);
        dispatch.setNonSelectString("project's");
        dispatch.setDisplayValueFunction(d -> d.name().toLowerCase(Locale.ROOT));
        dispatch.addItems(Dispatch.values());
        dispatch.addValueChangeHandler(event -> onModeDispatch.accept(event.getValue()));
        dispatchLink.setOnClick(() -> onDispatchLink.run());
        role.addValueChangeHandler(event -> onRole(event));
        matchChip.addClickHandler(event -> onMatchChip(event));
        matchChip.addKeyDownHandler(event -> {
            if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
                event.preventDefault();
                openMatch();
            }
        });
        workbench.setOnClick(() -> openWorkbench());
        // Enter in any of the line's fields takes it, and Escape anywhere in it puts it back.
        // The kind box's own Enter opens its list, so it is not one of the fields.
        for (final TextBox field : List.of(matchField, delimiterField, containerStartField, containerEndField)) {
            field.addKeyDownHandler(event -> {
                if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
                    event.preventDefault();
                    takeMatch(true);
                }
            });
        }
        matchEditor.addDomHandler(event -> {
            if (event.getNativeKeyCode() == KeyCodes.KEY_ESCAPE && matchOpen) {
                event.preventDefault();
                event.stopPropagation();
                closeMatch();
                matchChip.getElement().focus();
            }
        }, KeyDownEvent.getType());
        matchField.addKeyUpHandler(event -> {
            if (matchOpen) {
                onMatchTyped.accept(matchField.getText());
            }
        });
        matchKindBox.setDisplayValueFunction(MatchKind::label);
        matchKindBox.addItems(MatchLine.KINDS);
        matchKindBox.addValueChangeHandler(event -> onLineKind(event.getValue()));
        // Leaving the line takes what it holds, as doing anything else to a body takes the
        // card that is open in it. Moving between its own fields, or into the kind box's list,
        // is not leaving it.
        listenForLeaving(matchEditor.getElement());
        guardLimits.addClickHandler(event -> onGuardLimits(event));
        role.setDisplayValueFunction(MatchRole::label);
        role.addItems(MatchRole.values());
    }


    private void onChip(final ClickEvent e) {
        editIdentity();
    }

    private void onRole(final ValueChangeEvent<MatchRole> e) {
        if (e.getValue() != null) {
            onRole.accept(e.getValue());
        }
    }

    private void onMatchChip(final ClickEvent e) {
        openMatch();
    }

    // The match, opened: a regex or a delimiter that can be changed is its fields where it
    // stands, and anything else is the workbench's.
    private void openMatch() {
        if (line == null) {
            openWorkbench();
        } else {
            editMatch();
        }
    }

    /// Opens the match where it stands, with the keyboard in it, if it is a regex or a delimiter
    /// that can be changed; otherwise does nothing.
    public void editMatch() {
        if (line == null || matchOpen) {
            return;
        }
        matchOpen = true;
        matchField.setText(line.pattern());
        delimiterField.setText(line.delimiter());
        containerStartField.setText(line.containerStart());
        containerEndField.setText(line.containerEnd());
        matchKindBox.setValue(line.kind(), false);
        matchChip.setVisible(false);
        matchKind.setVisible(false);
        matchEditor.setVisible(true);
        showLineKind(line.kind());
        if (line.kind() == MatchKind.REGEX) {
            onMatchTyped.accept(line.pattern());
        }
    }

    // Another kind chosen on the open line: its fields, with the keyboard in the first. A
    // delimiter's arrives selected, so typing replaces the newline it starts as. Put there once
    // the list has closed, since closing it gives the keyboard back to the box.
    private void onLineKind(final MatchKind kind) {
        if (!matchOpen) {
            return;
        }
        if (kind == null) {
            // The box offers no "none": the line is still the kind it was
            matchKindBox.setValue(line.kind(), false);
            return;
        }
        showLineKind(kind);
        setMatchError(null);
        Scheduler.get().scheduleDeferred(() -> {
            if (matchOpen) {
                focusLine(kind);
            }
        });
        if (kind == MatchKind.REGEX) {
            onMatchTyped.accept(matchField.getText());
        }
    }

    private void showLineKind(final MatchKind kind) {
        matchField.setVisible(kind == MatchKind.REGEX);
        delimiterFields.setVisible(kind == MatchKind.DELIMITER);
        focusLine(kind);
    }

    private void focusLine(final MatchKind kind) {
        if (kind == MatchKind.DELIMITER) {
            delimiterField.setFocus(true);
            delimiterField.selectAll();
        } else {
            matchField.setFocus(true);
        }
    }

    /// @return What the open line holds: the kind chosen, and every field as it reads.
    public MatchLine getMatchLine() {
        final MatchKind opened = line == null
                ? MatchKind.REGEX
                : line.kind();
        return new MatchLine(opened, matchField.getText(), delimiterField.getText(),
                containerStartField.getText(), containerEndField.getText()).withKind(matchKindBox.getValue());
    }

    // Told by the browser when the keyboard leaves anything in the line, once the move is
    // complete: where it went is known by then.
    private void onFocusOut() {
        if (!matchOpen || matchKindBox.isOpen()
            || contains(matchEditor.getElement(), ContentFocus.activeElement())) {
            return;
        }
        takeMatch(false);
    }

    private static boolean contains(final Element container, final Element element) {
        return element != null && container.isOrHasChild(element);
    }

    private native void listenForLeaving(Element container) /*-{
        var self = this;
        container.addEventListener('focusout', function () {
            Promise.resolve().then(function () {
                self.@stroom.shapeshifter.client.component.TemplateStrip::onFocusOut()();
            });
        });
    }-*/;

    /// @return Whether the match is open as fields.
    public boolean isMatchOpen() {
        return matchOpen;
    }

    /// Puts the keyboard on the match as it reads at rest.
    public void focusMatch() {
        matchChip.getElement().focus();
    }

    /// Closes the match line, with no one told what it held: for its owner, which has taken what
    /// it held itself, or refused it. A line taken stays open until its owner says which, so a
    /// match refused is still there to be put right. Hiding it takes the keyboard from it, which
    /// would take it a second time, so it is closed before that.
    public void closeMatch() {
        matchOpen = false;
        matchEditor.setVisible(false);
        matchChip.setVisible(true);
        matchKind.setVisible(true);
        setMatchError(null);
    }

    /// Says what is wrong with the open line — a regex that does not compile, a delimiter with
    /// nothing to split on — or that nothing is. The field of the kind shown is the one marked.
    ///
    /// @param error The reason, or null when nothing is wrong or nothing is open.
    public void setMatchError(final String error) {
        final boolean invalid = error != null && matchOpen;
        matchError.setText(invalid
                ? error
                : "");
        matchError.setVisible(invalid);
        final TextBox marked = getMatchLine().kind() == MatchKind.DELIMITER
                ? delimiterField
                : matchField;
        for (final TextBox field : List.of(matchField, delimiterField)) {
            if (invalid && field == marked) {
                field.getElement().setAttribute("aria-invalid", "true");
            } else {
                field.getElement().removeAttribute("aria-invalid");
            }
        }
    }

    /// Puts the keyboard back in the open line, in the first field of the kind shown.
    public void focusOpenLine() {
        if (matchOpen) {
            focusLine(getMatchLine().kind());
        }
    }

    private void takeMatch(final boolean entered) {
        if (!matchOpen) {
            return;
        }
        onMatch.accept(getMatchLine(), entered);
    }

    private void onGuardLimits(final ClickEvent e) {
        openWorkbench();
    }

    private void editIdentity() {
        onEditIdentity.run();
    }

    private void openWorkbench() {
        onOpenWorkbench.run();
    }

    public void setHeader(final String titleText, final String colour, final String nameText, final String modeText,
                          final String noteText) {
        title.setText(titleText);
        chip.setVisible(colour != null);
        if (colour != null) {
            CssProperties.set(chip.getElement(), "--chip__colour", colour);
        }
        name.setName(nameText);
        mode.setText(modeText);
        note.setText(noteText);
        // The note is the part of the heading that gives way when the strip is narrow, so what
        // it says in full is on its tooltip
        note.setTitle(noteText);
    }

    public void setRole(final boolean visible, final MatchRole value, final boolean enabled, final String note,
                        final boolean problem) {
        roleLine.setVisible(visible);
        // Never fires: the strip is set again after every edit, and a picker that answered its
        // own refresh would write the project back with each one.
        role.setValue(value, false);
        role.setEnabled(enabled);
        roleNote.setText(note);
        roleNote.setStyleName("template-strip__role-note " + (problem
                ? "workbench-form__error"
                : "edit-form__description"));
    }

    /// What the template matches, as the match line says it.
    ///
    /// @param kind The kind of match.
    /// @param summary The match, described.
    /// @param guardAndLimits The guard and limits, described.
    /// @param line What the line opens on, when the match is a regex or a delimiter that can be
    ///             changed here, in place; null when it is not, and the workbench is the way in.
    public void setMatch(final String kind, final String summary, final String guardAndLimits,
                         final MatchLine line) {
        this.line = line;
        matchKind.setText(kind);
        matchSummary.setText(summary);
        matchSummary.setTitle(summary);
        matchChip.setTitle(line == null
                ? "Open the pattern workbench — editing, validation, groups, the plan"
                : "Click to change the match");
        guardLimits.setText(guardAndLimits);
        if (line == null && matchOpen) {
            // Read only now, or no longer a regex or a delimiter: there is nothing here to take
            closeMatch();
        }
    }

    /// @param editable Whether the name can be changed here.
    /// @param title What the edit button says it does.
    /// @param label What the name's field is for, for a screen reader.
    public void setNameEditable(final boolean editable, final String title, final String label) {
        name.setEditable(editable, title, label);
        chip.setStyleName("chip--editable", editable);
        chip.setTitle(editable
                ? title
                : "");
    }

    /// Opens the name as a field, with the keyboard in it.
    public void openName() {
        name.open();
    }

    /// Closes the name's field.
    public void closeName() {
        name.close();
    }

    /// @return Whether the name is open as a field.
    public boolean isNameOpen() {
        return name.isOpen();
    }

    /// @return What the name's field holds.
    public String getNameText() {
        return name.getText();
    }

    /// Puts the keyboard on the name's edit button.
    public void focusName() {
        name.focus();
    }

    /// @param widget The template's settings, shown under the title while it is being edited,
    ///               or null for none.
    public void setSettings(final IsWidget widget) {
        settings.setWidget(widget == null
                ? null
                : widget.asWidget());
        settings.setVisible(widget != null);
    }

    /// @return The list of a mode's templates.
    public DefinitionList getTemplates() {
        return templates;
    }

    /// @param visible Whether it is a mode that is shown, and so its templates that are listed.
    public void setTemplatesVisible(final boolean visible) {
        templates.setVisible(visible);
        dispatchLine.setVisible(visible);
    }

    /// How the mode that is shown is dispatched (design 47 C).
    ///
    /// @param value The strategy its dispatch sites share, or null for the project's.
    /// @param enabled Whether it can be set here: there are sites, they agree, and the project
    ///                can be changed.
    /// @param note What is true of its dispatch, said beside the picker.
    /// @param offer Whether to offer pointing the document's own dispatch at the mode.
    public void setModeDispatch(final Dispatch value, final boolean enabled, final String note,
                                final boolean offer) {
        // Never fires: the strip is set again after every edit, and a picker that answered
        // its own refresh would write the project back with each one.
        dispatch.setValue(value, false);
        dispatch.setEnabled(enabled);
        dispatchNote.setText(note);
        dispatchLink.setVisible(offer);
    }

    /// Puts the keyboard on the mode's dispatch picker.
    public void focusModeDispatch() {
        dispatch.focus();
    }

    /// @param onModeDispatch Told when the mode's strategy is chosen: the strategy, or null
    ///                       for the project's.
    public void setOnModeDispatch(final Consumer<Dispatch> onModeDispatch) {
        this.onModeDispatch = onModeDispatch;
    }

    /// @param onDispatchLink Told when dispatching from the document is asked for.
    public void setOnDispatchLink(final Runnable onDispatchLink) {
        this.onDispatchLink = onDispatchLink;
    }

    public void setMatchVisible(final boolean visible) {
        matchLine.setVisible(visible);
        guardLine.setVisible(visible);
        if (!visible && matchOpen) {
            closeMatch();
        }
    }

    public void setContent(final IsWidget view) {
        content.setWidget(view == null
                ? null
                : view.asWidget());
        content.setVisible(view != null);
    }

    /// @return The section its declarations controller attaches to.
    public DefinitionList getDeclarations() {
        return declarations;
    }

    /// @return The section its captures controller attaches to.
    public DefinitionList getCaptures() {
        return captures;
    }

    /// @param visible Whether there is a template to list the declarations and captures of.
    public void setDetailsVisible(final boolean visible) {
        declarations.setVisible(visible);
        captures.setVisible(visible);
    }


    /// @param onEditIdentity Told when the name's edit button, the name or the chip is pressed.
    public void setOnEditIdentity(final Runnable onEditIdentity) {
        this.onEditIdentity = onEditIdentity;
    }

    /// @param onNameEnter Told when Enter is pressed in the name's field.
    public void setOnNameEnter(final Runnable onNameEnter) {
        this.onNameEnter = onNameEnter;
    }

    /// @param onNameCancel Told when Escape is pressed in the name's field.
    public void setOnNameCancel(final Runnable onNameCancel) {
        this.onNameCancel = onNameCancel;
    }

    /// @param onRole Told when role.
    public void setOnRole(final Consumer<MatchRole> onRole) {
        this.onRole = onRole;
    }

    /// @param onMatch Told when the match line is taken, with what it held and whether it was
    ///                taken with Enter rather than by the keyboard leaving it.
    public void setOnMatch(final BiConsumer<MatchLine, Boolean> onMatch) {
        this.onMatch = onMatch;
    }

    /// @param onMatchTyped Told what the match field holds when it is opened and as it is
    ///                     typed in, so the engine can be asked whether it compiles.
    public void setOnMatchTyped(final Consumer<String> onMatchTyped) {
        this.onMatchTyped = onMatchTyped;
    }

    /// @param onOpenWorkbench Told when openworkbench.
    public void setOnOpenWorkbench(final Runnable onOpenWorkbench) {
        this.onOpenWorkbench = onOpenWorkbench;
    }

    interface Binder extends UiBinder<Widget, TemplateStrip> {

    }
}
