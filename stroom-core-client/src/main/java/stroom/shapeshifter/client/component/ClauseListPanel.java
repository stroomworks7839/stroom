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

import stroom.shapeshifter.client.model.GuardClause;
import stroom.shapeshifter.client.model.GuardClause.Join;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextArea;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;

import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.user.client.ui.FlowPanel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/// A condition as clause rows joined by *and* or by *or*, each row optionally negated, with
/// *+ clause* — or as its wire form when the rows cannot express it: the one condition editor,
/// shared by the guard, `if` and `when` (design 43 §4.2).
///
/// <p>The join is one choice above the rows rather than a word between each pair, because a
/// condition in the corpus is an *and* or an *or* and never a mixture (design 46 §7.2). Mixing
/// them per row would offer a precedence the model does not have.
public class ClauseListPanel extends Component {

    private final FlowPanel rows = new FlowPanel();
    private final FlowPanel joinPanel = new FlowPanel();
    private final SelectionBox<Join> join = new SelectionBox<>();
    private final Label none = new Label();
    private final Label add = new Label("+ clause");
    private final FlowPanel jsonPanel = new FlowPanel();
    private final TextArea json = new TextArea();
    private final Label error = new Label();
    private final List<GuardClauseRow> rowWidgets = new ArrayList<>();

    private List<String> names = List.of();
    private boolean enabled = true;
    private String committedJson = "";
    private BiConsumer<Integer, GuardClause> onChange = (at, clause) -> {
    };
    private IntConsumer onRemove = at -> {
    };
    private Runnable onAdd = () -> {
    };
    private Consumer<String> onJson = json -> {
    };
    private Consumer<Join> onJoin = j -> {
    };

    public ClauseListPanel() {
        final FlowPanel panel = new FlowPanel();
        none.setStyleName("placeholder");
        add.setStyleName("add-button");
        add.addClickHandler((ClickEvent e) -> {
            if (enabled) {
                onAdd.run();
            }
        });
        final Label joinLabel = new Label("holds when");
        joinLabel.setStyleName("clause-list__join-label");
        join.setAriaLabel("Holds when");
        join.setDisplayValueFunction(Join::label);
        join.addItems(Join.values());
        join.setValue(Join.ALL, false);
        join.addValueChangeHandler(event -> {
            // The word between the rows was baked in when they were built, so the rows are
            // rebuilt before anyone is told: otherwise the picker reads "any" while the rows
            // still say "and", and the guard the author sees is not the one being written.
            setClauses(getClauses(), event.getValue());
            onJoin.accept(event.getValue());
        });
        final Label joinSuffix = new Label("of these hold");
        joinSuffix.setStyleName("clause-list__join-label");
        joinPanel.setStyleName("clause-list__join");
        joinPanel.add(joinLabel);
        joinPanel.add(join);
        joinPanel.add(joinSuffix);
        joinPanel.setVisible(false);
        final Label lint = new Label("This condition is more than rows joined one way — a comparison of two"
                                     + " references, or a nesting deeper than a negated row — so it is"
                                     + " edited as its wire form.");
        lint.setStyleName("workbench-form__lint");
        json.setStyleName("workbench-form__pattern");
        json.setVisibleLines(6);
        json.addBlurHandler((BlurEvent e) -> {
            final String text = json.getText();
            if (!text.equals(committedJson)) {
                committedJson = text;
                onJson.accept(text);
            }
        });
        jsonPanel.add(lint);
        jsonPanel.add(json);
        jsonPanel.setVisible(false);
        error.setStyleName("workbench-form__error");
        error.setVisible(false);
        panel.add(none);
        panel.add(joinPanel);
        panel.add(rows);
        panel.add(add);
        panel.add(jsonPanel);
        panel.add(error);
        initWidget(panel);
    }

    /// Told when a clause changes, with its index and the clause it now holds.
    ///
    /// @param onChange The handler.
    public void setOnChange(final BiConsumer<Integer, GuardClause> onChange) {
        this.onChange = onChange;
    }

    /// Told when a clause is removed, with its index.
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

    /// Told when the condition is edited as its wire form, with the JSON written.
    ///
    /// @param onJson The handler.
    public void setOnJson(final Consumer<String> onJson) {
        this.onJson = onJson;
    }

    /// What the rows say when there are none.
    public void setEmptyText(final String text) {
        none.setText(text);
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        this.enabled = !readOnly;
        add.setStyleDependentName("disabled", !enabled);
        join.setEnabled(enabled);
        json.setEnabled(enabled);
    }

    public void setNames(final List<String> names) {
        this.names = names;
    }

    /// Show the condition as rows; the wire form goes.
    public void setClauses(final List<GuardClause> list, final Join how) {
        join.setValue(how == null
                ? Join.ALL
                : how, false);
        join.setEnabled(enabled);
        rows.clear();
        rowWidgets.clear();
        for (int i = 0; i < list.size(); i++) {
            final GuardClauseRow row = new GuardClauseRow(i, list.get(i), join.getValue(), names,
                    enabled);
            row.setOnChange((at, clause) -> onChange.accept(at, clause));
            row.setOnRemove(at -> onRemove.accept(at));
            rowWidgets.add(row);
            rows.add(row);
        }
        none.setVisible(list.isEmpty());
        // One row needs no joining word, and offering the choice would imply it changes something
        joinPanel.setVisible(list.size() > 1);
        rows.setVisible(true);
        add.setVisible(true);
        jsonPanel.setVisible(false);
    }

    /// Told when the join changes.
    ///
    /// @param onJoin The handler.
    public void setOnJoin(final Consumer<Join> onJoin) {
        this.onJoin = onJoin;
    }

    /// @return How the rows are joined.
    public Join getJoin() {
        return join.getValue() == null
                ? Join.ALL
                : join.getValue();
    }

    /// The rows as they stand; empty when the wire form is showing.
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

    /// Show the condition as its wire form; the rows go.
    public void setJson(final String text) {
        rows.clear();
        rowWidgets.clear();
        none.setVisible(false);
        rows.setVisible(false);
        joinPanel.setVisible(false);
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

}
