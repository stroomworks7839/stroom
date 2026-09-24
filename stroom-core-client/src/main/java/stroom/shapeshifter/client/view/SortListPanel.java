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

import stroom.shapeshifter.client.presenter.SortKey;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;

import java.util.ArrayList;
import java.util.List;

/**
 * An iteration's ordering as key rows, with <i>+ sort key</i> — the last of the wire form's
 * holdings, and the only one that was never a reference but a list of its own (design 44 §5ac).
 */
public class SortListPanel extends Composite implements SortKeyRow.Listener {

    private final FlowPanel rows = new FlowPanel();
    private final Label none = new Label();
    private final Label add = new Label("+ sort key");
    private final List<SortKeyRow> rowWidgets = new ArrayList<>();

    private boolean enabled = true;
    private Listener listener;

    public SortListPanel() {
        final FlowPanel panel = new FlowPanel();
        none.setStyleName("ss-none");
        add.setStyleName("ss-addbtn");
        add.addClickHandler((ClickEvent e) -> {
            if (enabled && listener != null) {
                listener.onSortAdd();
            }
        });
        panel.add(none);
        panel.add(rows);
        panel.add(add);
        initWidget(panel);
    }

    public void setListener(final Listener listener) {
        this.listener = listener;
    }

    /** What the rows say when there are none. */
    public void setEmptyText(final String text) {
        none.setText(text);
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
        add.setStyleDependentName("disabled", !enabled);
    }

    public void setKeys(final List<SortKey> list) {
        rows.clear();
        rowWidgets.clear();
        for (int i = 0; i < list.size(); i++) {
            final SortKeyRow row = new SortKeyRow(i, list.get(i), this, enabled);
            rowWidgets.add(row);
            rows.add(row);
        }
        none.setVisible(list.isEmpty());
    }

    /** The rows as they stand. */
    public List<SortKey> getKeys() {
        final List<SortKey> list = new ArrayList<>();
        for (final SortKeyRow row : rowWidgets) {
            list.add(row.getKey());
        }
        return list;
    }

    @Override
    public void onSortChange(final int index, final SortKey key) {
        if (listener != null) {
            listener.onSortChange(index, key);
        }
    }

    @Override
    public void onSortRemove(final int index) {
        if (listener != null) {
            listener.onSortRemove(index);
        }
    }

    public interface Listener {

        void onSortChange(int index, SortKey key);

        void onSortRemove(int index);

        void onSortAdd();
    }
}
