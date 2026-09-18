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

import stroom.shapeshifter.client.presenter.ModeRowData;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;

/** One mode: its name, editable; how many templates and apply sites hold it; remove when empty. */
public class ModeRow extends Composite {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    TextBox name;
    @UiField
    Label count;
    @UiField
    Label remove;

    private final ModeRowData data;
    private final Listener listener;
    private boolean committed;

    public ModeRow(final ModeRowData data, final Listener listener, final boolean editable) {
        this.data = data;
        this.listener = listener;
        initWidget(BINDER.createAndBindUi(this));
        name.setText(data.getName());
        name.setEnabled(editable);
        count.setText(data.getTemplates() + (data.getTemplates() == 1
                ? " template · "
                : " templates · ") + data.getApplySites() + (data.getApplySites() == 1
                ? " apply site"
                : " apply sites"));
        final boolean removable = editable && data.getTemplates() == 0;
        remove.setVisible(removable);
        remove.setTitle(data.getApplySites() == 0
                ? "Remove this mode"
                : "Remove this mode: its apply sites become dispatches into the root");
    }

    @UiHandler("name")
    void onBlur(final BlurEvent e) {
        commit();
    }

    @UiHandler("name")
    void onKey(final KeyDownEvent e) {
        if (e.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
            e.preventDefault();
            commit();
        }
    }

    @UiHandler("remove")
    void onRemove(final ClickEvent e) {
        listener.onRemove(data.getName());
    }

    private void commit() {
        if (committed) {
            return;
        }
        final String typed = name.getText().trim();
        if (!typed.equals(data.getName())) {
            committed = true;
            listener.onRename(data.getName(), typed);
        }
    }

    public interface Listener {

        void onRename(String from, String to);

        void onRemove(String mode);
    }

    interface Binder extends UiBinder<Widget, ModeRow> {

    }
}
