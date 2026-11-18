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

import stroom.shapeshifter.client.model.MatchRole;
import stroom.widget.component.client.Component;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;
import stroom.widget.util.client.CssProperties;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.function.Consumer;

public class TemplateStrip extends Component {

    private Runnable onEditIdentity = () -> {
    };
    private Consumer<MatchRole> onRole = role -> {
    };
    private Runnable onOpenWorkbench = () -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    Label title;
    @UiField
    Label chip;
    @UiField
    Label name;
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
        name.addClickHandler(event -> onName(event));
        role.addValueChangeHandler(event -> onRole(event));
        matchChip.addClickHandler(event -> onMatchChip(event));
        guardLimits.addClickHandler(event -> onGuardLimits(event));
        role.setDisplayValueFunction(MatchRole::label);
        role.addItems(MatchRole.values());
    }


    private void onChip(final ClickEvent e) {
        editIdentity();
    }

    private void onName(final ClickEvent e) {
        editIdentity();
    }

    private void onRole(final ValueChangeEvent<MatchRole> e) {
        if (e.getValue() != null) {
            onRole.accept(e.getValue());
        }
    }

    private void onMatchChip(final ClickEvent e) {
        openWorkbench();
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
        name.setText(nameText);
        name.setStyleDependentName("editable", colour != null);
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

    public void setMatch(final String kind, final String summary, final String guardAndLimits) {
        matchKind.setText(kind);
        matchSummary.setText(summary);
        matchSummary.setTitle(summary);
        guardLimits.setText(guardAndLimits);
    }

    public void setMatchVisible(final boolean visible) {
        matchLine.setVisible(visible);
        guardLine.setVisible(visible);
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


    /// @param onEditIdentity Told when editidentity.
    public void setOnEditIdentity(final Runnable onEditIdentity) {
        this.onEditIdentity = onEditIdentity;
    }

    /// @param onRole Told when role.
    public void setOnRole(final Consumer<MatchRole> onRole) {
        this.onRole = onRole;
    }

    /// @param onOpenWorkbench Told when openworkbench.
    public void setOnOpenWorkbench(final Runnable onOpenWorkbench) {
        this.onOpenWorkbench = onOpenWorkbench;
    }

    interface Binder extends UiBinder<Widget, TemplateStrip> {

    }
}
