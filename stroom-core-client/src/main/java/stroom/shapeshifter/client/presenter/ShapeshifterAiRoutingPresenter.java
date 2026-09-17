/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.client.presenter;

import stroom.alert.client.event.ConfirmEvent;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.query.client.ExpressionTreePresenter;
import stroom.shapeshifter.client.presenter.RoutingRuleListPresenter.RoutingRow;
import stroom.shapeshifter.client.presenter.ShapeshifterAiRoutingPresenter.ShapeshifterAiRoutingView;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.svg.client.SvgPresets;
import stroom.widget.button.client.ButtonView;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.MultiSelectEvent;

import com.google.gwt.dom.client.Style.BorderStyle;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Routing tab: the document's routing table (design §3, §7.3 rule 2) as an ordered list, with the
 * selected rule's selector drawn as a tree below it, and a dialog to add or edit a rule. Order is
 * specificity (A22), so moving a rule is editing the table.
 */
public class ShapeshifterAiRoutingPresenter
        extends DocPresenter<ShapeshifterAiRoutingView, ShapeshifterAiDoc>
        implements ShapeshifterAiSettingsUiHandlers {

    private final RoutingRuleListPresenter listPresenter;
    private final ExpressionTreePresenter expressionPresenter;
    private final Provider<RoutingRulePresenter> editRulePresenterProvider;
    private final List<RoutingRule> rules = new ArrayList<>();

    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView copyButton;
    private final ButtonView deleteButton;
    private final ButtonView moveUpButton;
    private final ButtonView moveDownButton;

    @Inject
    public ShapeshifterAiRoutingPresenter(final EventBus eventBus,
                                             final ShapeshifterAiRoutingView view,
                                             final RoutingRuleListPresenter listPresenter,
                                             final ExpressionTreePresenter expressionPresenter,
                                             final Provider<RoutingRulePresenter> editRulePresenterProvider) {
        super(eventBus, view);
        this.listPresenter = listPresenter;
        this.expressionPresenter = expressionPresenter;
        this.editRulePresenterProvider = editRulePresenterProvider;
        view.setUiHandlers(this);
        view.setTableView(listPresenter.getView());
        view.setExpressionView(expressionPresenter.getView());

        // The tree below the table is a display of the selected selector, not an editor.
        expressionPresenter.setSelectionModel(null);

        addButton = listPresenter.add(SvgPresets.ADD.title("Add new rule"));
        editButton = listPresenter.add(SvgPresets.EDIT.title("Edit selected rule"));
        copyButton = listPresenter.add(SvgPresets.COPY.title("Copy selected rule"));
        deleteButton = listPresenter.add(SvgPresets.DELETE.title("Delete selected rule"));
        moveUpButton = listPresenter.add(SvgPresets.UP.title("Move selected rule up"));
        moveDownButton = listPresenter.add(SvgPresets.DOWN.title("Move selected rule down"));
        listPresenter.getView().asWidget().getElement().getStyle().setBorderStyle(BorderStyle.NONE);
        updateButtons();
    }

    @Override
    protected void onBind() {
        registerHandler(addButton.addClickHandler(this::onAdd));
        registerHandler(editButton.addClickHandler(this::onEdit));
        registerHandler(copyButton.addClickHandler(this::onCopy));
        registerHandler(deleteButton.addClickHandler(this::onDelete));
        registerHandler(moveUpButton.addClickHandler(event -> move(-1)));
        registerHandler(moveDownButton.addClickHandler(event -> move(1)));
        registerHandler(listPresenter.getSelectionModel().addSelectionHandler(this::onSelection));
        super.onBind();
    }

    @Override
    protected void onRead(final DocRef docRef, final ShapeshifterAiDoc doc, final boolean readOnly) {
        rules.clear();
        rules.addAll(doc.getRoutingTable());
        listPresenter.getSelectionModel().clear();
        update();
    }

    @Override
    protected ShapeshifterAiDoc onWrite(final ShapeshifterAiDoc doc) {
        return doc.copy().routingTable(new ArrayList<>(rules)).build();
    }

    /**
     * The selected rule's position, or -1. Rules are compared by value, and a copied rule equals its
     * original until one is edited, so the row's position is the only safe identity.
     */
    private int selectedIndex() {
        final RoutingRow row = listPresenter.getSelectionModel().getSelected();
        return row == null
                ? -1
                : row.number() - 1;
    }

    private RoutingRule selected() {
        final int index = selectedIndex();
        return index < 0
                ? null
                : rules.get(index);
    }

    private void onAdd(final ClickEvent event) {
        if (!isReadOnly()) {
            // A new rule goes to the top: the operator has just decided it is the most specific.
            showRule(RoutingRule.builder().build(), rule -> {
                rules.add(0, rule);
                update();
                select(0);
                onChange();
            });
        }
    }

    private void onEdit(final ClickEvent event) {
        if (!isReadOnly()) {
            final RoutingRule existing = selected();
            if (existing != null) {
                edit(existing);
            }
        }
    }

    private void edit(final RoutingRule existing) {
        final int index = selectedIndex();
        showRule(existing, rule -> {
            rules.set(index, rule);
            update();
            select(index);
            if (!existing.equals(rule)) {
                onChange();
            }
        });
    }

    private void onCopy(final ClickEvent event) {
        if (!isReadOnly()) {
            final RoutingRule existing = selected();
            if (existing != null) {
                // The copy is a hand-written rule with the same selector and fragment; the history of
                // the original's promotion is the original's.
                final RoutingRule copy = RoutingRule.builder()
                        .expression(existing.getExpression())
                        .pipeline(existing.getPipeline())
                        .pinned(existing.isPinned())
                        .build();
                final int index = selectedIndex() + 1;
                rules.add(index, copy);
                update();
                select(index);
                onChange();
            }
        }
    }

    private void onDelete(final ClickEvent event) {
        if (!isReadOnly()) {
            final int index = selectedIndex();
            if (index >= 0) {
                ConfirmEvent.fire(this, "Are you sure you want to delete the selected rule?", ok -> {
                    if (ok) {
                        rules.remove(index);
                        listPresenter.getSelectionModel().clear();
                        update();
                        onChange();
                    }
                });
            }
        }
    }

    private void move(final int by) {
        if (!isReadOnly()) {
            final int index = selectedIndex();
            if (index >= 0) {
                final int target = index + by;
                if (target >= 0 && target < rules.size()) {
                    final RoutingRule existing = rules.remove(index);
                    rules.add(target, existing);
                    update();
                    select(target);
                    onChange();
                }
            }
        }
    }

    private void onSelection(final MultiSelectEvent event) {
        final RoutingRule rule = selected();
        expressionPresenter.read(rule == null
                ? null
                : rule.getExpression());
        if (rule != null && event.getSelectionType().isDoubleSelect() && !isReadOnly()) {
            edit(rule);
        }
        updateButtons();
    }

    private void showRule(final RoutingRule rule, final Consumer<RoutingRule> onOk) {
        final RoutingRulePresenter rulePresenter = editRulePresenterProvider.get();
        rulePresenter.read(rule);
        ShowPopupEvent.builder(rulePresenter)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(1_000, 600))
                .caption("Routing Rule")
                .onShow(e -> listPresenter.focus())
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        onOk.accept(rulePresenter.write());
                    }
                    e.hide();
                })
                .fire();
    }

    private void select(final int index) {
        listPresenter.getSelectionModel().clear();
        // Rows are rebuilt on every update, so the row is found by position rather than identity.
        listPresenter.selectRow(index);
    }

    private void update() {
        listPresenter.setData(rules);
        updateButtons();
    }

    private void updateButtons() {
        final boolean editable = !isReadOnly();
        final int index = selectedIndex();
        final boolean selected = index >= 0;
        addButton.setEnabled(editable);
        editButton.setEnabled(editable && selected);
        copyButton.setEnabled(editable && selected);
        deleteButton.setEnabled(editable && selected);
        moveUpButton.setEnabled(editable && index > 0);
        moveDownButton.setEnabled(editable && index >= 0 && index < rules.size() - 1);
    }


    // --------------------------------------------------------------------------------


    public interface ShapeshifterAiRoutingView
            extends View, HasUiHandlers<ShapeshifterAiSettingsUiHandlers> {

        void setTableView(View view);

        void setExpressionView(View view);
    }
}
