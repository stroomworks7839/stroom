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

import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.Toolbar;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.form.client.TextBox;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Widget;

/// A name in a title that is edited where it reads (the owner, 2026-11-18): the name, and an
/// edit button beside it. Pressed, the name is a field holding it, with the keyboard in it;
/// Enter takes what it holds, Escape puts it back, and the button pressed again takes it too.
///
/// It is what a template's, a mode's and a pattern part's title is. The thing named is made
/// first and named here afterwards, so there is no dialog and no field in the list to name it
/// in. What taking and dropping mean — and what else there is to edit while it is open — is
/// its owner's: this says only what was pressed.
///
/// ```
/// <ss:NameEdit ui:field="name"/>
/// ```
public class NameEdit extends Composite {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Label name;
    @UiField
    TextBox field;
    @UiField
    Toolbar actions;
    @UiField
    ToolbarButton edit;

    private boolean editable;
    private boolean open;
    private Runnable onToggle = () -> {
    };
    private Runnable onEnter = () -> {
    };
    private Runnable onCancel = () -> {
    };

    public NameEdit() {
        initWidget(BINDER.createAndBindUi(this));
        edit.setSvg(SvgImage.EDIT);
        edit.setOnClick(() -> {
            if (editable) {
                onToggle.run();
            }
        });
        name.addClickHandler(event -> {
            if (editable) {
                onToggle.run();
            }
        });
        field.addKeyDownHandler(event -> {
            if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
                event.preventDefault();
                onEnter.run();
            } else if (event.getNativeKeyCode() == KeyCodes.KEY_ESCAPE) {
                event.preventDefault();
                event.stopPropagation();
                onCancel.run();
            }
        });
        setEditable(false, "Edit the name", "Name");
    }

    /// @param text The name, as it reads at rest.
    public void setName(final String text) {
        name.setText(text);
    }

    /// @param editable Whether the name can be changed: the button is there only when it can.
    /// @param title What the button says it does, e.g. `Edit the name and settings`.
    /// @param label What the field is for, for a screen reader, e.g. `Template name`.
    public void setEditable(final boolean editable, final String title, final String label) {
        this.editable = editable;
        actions.setVisible(editable);
        edit.setTitle(title);
        field.setAriaLabel(label);
        name.setStyleName("name-edit__name--editable", editable);
        name.setTitle(editable
                ? title
                : "");
        if (!editable && open) {
            close();
        }
    }

    /// Opens the name: a field holding it, all of it selected so that typing replaces it, with
    /// the keyboard in it.
    public void open() {
        if (!editable || open) {
            return;
        }
        open = true;
        field.setText(name.getText());
        name.setVisible(false);
        field.setVisible(true);
        field.getElement().focus();
        field.selectAll();
    }

    /// Closes the field, leaving the name as it was last set.
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        field.setVisible(false);
        name.setVisible(true);
    }

    /// @return Whether the name is open as a field.
    public boolean isOpen() {
        return open;
    }

    /// @return What the field holds.
    public String getText() {
        return field.getText();
    }

    /// Puts the keyboard on the edit button.
    public void focus() {
        edit.getElement().focus();
    }

    /// @param onToggle Told when the edit button, or the name itself, is pressed.
    public void setOnToggle(final Runnable onToggle) {
        this.onToggle = onToggle;
    }

    /// @param onEnter Told when Enter is pressed in the field.
    public void setOnEnter(final Runnable onEnter) {
        this.onEnter = onEnter;
    }

    /// @param onCancel Told when Escape is pressed in the field.
    public void setOnCancel(final Runnable onCancel) {
        this.onCancel = onCancel;
    }

    interface Binder extends UiBinder<Widget, NameEdit> {

    }
}
