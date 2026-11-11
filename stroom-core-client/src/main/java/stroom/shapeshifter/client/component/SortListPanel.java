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

import stroom.shapeshifter.client.model.SortKey;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.Label;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.user.client.ui.FlowPanel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/// An iteration's ordering as key rows, with *+ sort key* — the last of the wire form's
/// holdings, and the only one that was never a reference but a list of its own (design 44 §5ac).
public class SortListPanel extends Component {

    private final FlowPanel rows = new FlowPanel();
    private final Label none = new Label();
    private final Label add = new Label("+ sort key");
    private final List<SortKeyRow> rowWidgets = new ArrayList<>();

    private boolean enabled = true;
    private BiConsumer<Integer, SortKey> onChange = (at, key) -> {
    };
    private IntConsumer onRemove = at -> {
    };
    private Runnable onAdd = () -> {
    };

    public SortListPanel() {
        final FlowPanel panel = new FlowPanel();
        none.setStyleName("placeholder");
        add.setStyleName("add-button");
        add.addClickHandler((ClickEvent e) -> {
            if (enabled) {
                onAdd.run();
            }
        });
        panel.add(none);
        panel.add(rows);
        panel.add(add);
        initWidget(panel);
    }

    /// Told when a key changes, with its index and the key it now holds.
    ///
    /// @param onChange The handler.
    public void setOnChange(final BiConsumer<Integer, SortKey> onChange) {
        this.onChange = onChange;
    }

    /// Told when a key is removed, with its index.
    ///
    /// @param onRemove The handler.
    public void setOnRemove(final IntConsumer onRemove) {
        this.onRemove = onRemove;
    }

    /// Told when the add line is pressed.
    ///
    /// @param onAdd The handler.
    public void setOnAdd(final Runnable onAdd) {
        this.onAdd = onAdd;
    }

    /// What the rows say when there are none.
    public void setEmptyText(final String text) {
        none.setText(text);
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        this.enabled = !readOnly;
        add.setStyleDependentName("disabled", !enabled);
    }

    public void setKeys(final List<SortKey> list) {
        rows.clear();
        rowWidgets.clear();
        for (int i = 0; i < list.size(); i++) {
            final SortKeyRow row = new SortKeyRow(i, list.get(i), enabled);
            row.setOnChange((at, key) -> onChange.accept(at, key));
            row.setOnRemove(at -> onRemove.accept(at));
            rowWidgets.add(row);
            rows.add(row);
        }
        none.setVisible(list.isEmpty());
    }

    /// The rows as they stand.
    public List<SortKey> getKeys() {
        final List<SortKey> list = new ArrayList<>();
        for (final SortKeyRow row : rowWidgets) {
            list.add(row.getKey());
        }
        return list;
    }
}
