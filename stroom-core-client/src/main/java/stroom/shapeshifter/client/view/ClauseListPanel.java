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

import stroom.shapeshifter.client.presenter.GuardClause;

import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextArea;

import java.util.ArrayList;
import java.util.List;

/**
 * A condition as clause rows joined by <i>and</i>, with <i>+ clause</i>, or as its wire form
 * when the rows cannot express it — the one condition editor, shared by the guard, {@code if}
 * and {@code when} (design 43 §4.2).
 */
public class ClauseListPanel extends Composite implements GuardClauseRow.Listener {

    private final FlowPanel rows = new FlowPanel();
    private final Label none = new Label();
    private final Label add = new Label("+ clause");
    private final FlowPanel jsonPanel = new FlowPanel();
    private final TextArea json = new TextArea();
    private final Label error = new Label();
    private final List<GuardClauseRow> rowWidgets = new ArrayList<>();

    private List<String> names = List.of();
    private boolean enabled = true;
    private String committedJson = "";
    private Listener listener;

    public ClauseListPanel() {
        final FlowPanel panel = new FlowPanel();
        none.setStyleName("ss-none");
        add.setStyleName("ss-addbtn");
        add.addClickHandler((ClickEvent e) -> {
            if (enabled && listener != null) {
                listener.onClauseAdd();
            }
        });
        final Label lint = new Label("This condition is more than clauses joined by and — an or, a not, a"
                                     + " comparison of two references — so it is edited as its wire form.");
        lint.setStyleName("ss-wb-lint");
        json.setStyleName("ss-wb-pat");
        json.setVisibleLines(6);
        json.addBlurHandler((BlurEvent e) -> {
            final String text = json.getText();
            if (!text.equals(committedJson) && listener != null) {
                committedJson = text;
                listener.onJson(text);
            }
        });
        jsonPanel.add(lint);
        jsonPanel.add(json);
        jsonPanel.setVisible(false);
        error.setStyleName("ss-wb-err");
        error.setVisible(false);
        panel.add(none);
        panel.add(rows);
        panel.add(add);
        panel.add(jsonPanel);
        panel.add(error);
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
        json.setEnabled(enabled);
    }

    public void setNames(final List<String> names) {
        this.names = names;
    }

    /** Show the condition as rows; the wire form goes. */
    public void setClauses(final List<GuardClause> list) {
        rows.clear();
        rowWidgets.clear();
        for (int i = 0; i < list.size(); i++) {
            final GuardClauseRow row = new GuardClauseRow(i, list.get(i), names, this, enabled);
            rowWidgets.add(row);
            rows.add(row);
        }
        none.setVisible(list.isEmpty());
        rows.setVisible(true);
        add.setVisible(true);
        jsonPanel.setVisible(false);
    }

    /** The rows as they stand; empty when the wire form is showing. */
    public List<GuardClause> getClauses() {
        final List<GuardClause> list = new ArrayList<>();
        for (final GuardClauseRow row : rowWidgets) {
            list.add(row.getClause());
        }
        return list;
    }

    public boolean isWireForm() {
        return jsonPanel.isVisible();
    }

    /** Show the condition as its wire form; the rows go. */
    public void setJson(final String text) {
        rows.clear();
        rowWidgets.clear();
        none.setVisible(false);
        rows.setVisible(false);
        add.setVisible(false);
        jsonPanel.setVisible(true);
        committedJson = text;
        json.setText(text);
    }

    public String getJson() {
        return json.getText();
    }

    public void setError(final String text) {
        error.setText(text == null
                ? ""
                : text);
        error.setVisible(text != null);
    }

    @Override
    public void onClauseChange(final int index, final GuardClause clause) {
        if (listener != null) {
            listener.onClauseChange(index, clause);
        }
    }

    @Override
    public void onClauseRemove(final int index) {
        if (listener != null) {
            listener.onClauseRemove(index);
        }
    }

    public interface Listener {

        void onClauseChange(int index, GuardClause clause);

        void onClauseRemove(int index);

        void onClauseAdd();

        void onJson(String json);
    }
}
