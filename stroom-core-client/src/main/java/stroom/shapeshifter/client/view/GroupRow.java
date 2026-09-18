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

import stroom.shapeshifter.client.presenter.GroupRowData;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;

/**
 * One row of the groups panel: chip · {@code $n} · → · the declaration's name. The name commits
 * when the field is left or Enter is pressed — the same live convention as everything else.
 */
public class GroupRow extends Composite {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    FlowPanel row;
    @UiField
    Label chip;
    @UiField
    Label index;
    @UiField
    TextBox name;

    private final GroupRowData data;
    private final Listener listener;
    private String committed;

    public GroupRow(final GroupRowData data, final Listener listener, final boolean enabled) {
        this.data = data;
        this.listener = listener;
        initWidget(BINDER.createAndBindUi(this));
        chip.getElement().getStyle().setBackgroundColor(data.getHue());
        index.setText("$" + data.getIndex());
        committed = data.getBoundName() == null
                ? ""
                : data.getBoundName();
        name.setText(committed);
        name.getElement().setPropertyString("placeholder", data.getSyntaxName() == null
                ? "declaration"
                : data.getSyntaxName());
        name.setEnabled(enabled);
        if (data.isSelected()) {
            row.addStyleName("ss-group-row--sel");
        }
    }

    @UiHandler("chip")
    void onChip(final ClickEvent e) {
        listener.onGroupSelect(data.getIndex());
    }

    @UiHandler("index")
    void onIndex(final ClickEvent e) {
        listener.onGroupSelect(data.getIndex());
    }

    @UiHandler("name")
    void onBlur(final BlurEvent e) {
        commit();
    }

    @UiHandler("name")
    void onKeyDown(final KeyDownEvent e) {
        if (e.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
            e.preventDefault();
            commit();
        }
    }

    private void commit() {
        final String typed = name.getText().trim();
        if (typed.equals(committed)) {
            return;
        }
        committed = typed;
        listener.onGroupName(data.getIndex(), typed);
    }

    public interface Listener {

        void onGroupName(int index, String name);

        void onGroupSelect(int index);
    }

    interface Binder extends UiBinder<Widget, GroupRow> {

    }
}
