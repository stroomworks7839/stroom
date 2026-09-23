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
import stroom.alert.client.event.PromptEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.query.client.ExpressionTreePresenter;
import stroom.shapeshifter.client.presenter.RoutingRuleListPresenter.RoutingRow;
import stroom.shapeshifter.client.presenter.ShapeshifterAiRoutingPresenter.ShapeshifterAiRoutingView;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.svg.client.SvgPresets;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ButtonView;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.MultiSelectEvent;

import com.google.gwt.core.client.GWT;
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
import java.util.function.Function;

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
    private static final ShapeshifterAiResource RESOURCE = GWT.create(ShapeshifterAiResource.class);

    private final RestFactory restFactory;
    private final List<RoutingRule> rules = new ArrayList<>();
    private String docUuid;

    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView copyButton;
    private final ButtonView deleteButton;
    private final ButtonView moveUpButton;
    private final ButtonView moveDownButton;
    private final ButtonView approveButton;
    private final ButtonView rejectButton;

    @Inject
    public ShapeshifterAiRoutingPresenter(final EventBus eventBus,
                                             final ShapeshifterAiRoutingView view,
                                             final RoutingRuleListPresenter listPresenter,
                                             final ExpressionTreePresenter expressionPresenter,
                                             final Provider<RoutingRulePresenter> editRulePresenterProvider,
                                             final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
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
        // Review mode's two decisions (A25, design 01 §11.3), where a person meets the draft: on the
        // table that holds it, beside the fragment it binds and the shape it is for.
        approveButton = listPresenter.add(SvgPresets.TICK.title("Approve the selected draft"));
        rejectButton = listPresenter.add(SvgPresets.DISABLE.title("Reject the selected draft"));
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
        registerHandler(approveButton.addClickHandler(this::onApprove));
        registerHandler(rejectButton.addClickHandler(this::onReject));
        registerHandler(listPresenter.getSelectionModel().addSelectionHandler(this::onSelection));
        super.onBind();
    }

    /**
     * The rules are rows, not part of the document (A41), so they are fetched for the document rather than
     * read from it; a document that has learned nothing has none.
     */
    @Override
    protected void onRead(final DocRef docRef, final ShapeshifterAiDoc doc, final boolean readOnly) {
        docUuid = docRef.getUuid();
        rules.clear();
        listPresenter.getSelectionModel().clear();
        update();
        restFactory
                .create(RESOURCE)
                .method(resource -> resource.rules(docRef.getUuid()))
                .onSuccess(fetched -> {
                    rules.clear();
                    rules.addAll(fetched);
                    update();
                })
                .taskMonitorFactory(this)
                .exec();
    }

    /**
     * The rules are rows and each action saved itself (A41), so the document's Save has nothing of theirs to
     * carry: it returns what it was given.
     */
    @Override
    protected ShapeshifterAiDoc onWrite(final ShapeshifterAiDoc doc) {
        return doc;
    }

    /**
     * One action, one call, answered with the table as the server now holds it: a rule promoted while this
     * tab was open shows up rather than being overwritten by what the tab last read.
     */
    private void apply(final Function<ShapeshifterAiResource, List<RoutingRule>> call, final String keepSelected) {
        if (docUuid == null) {
            return;
        }
        restFactory
                .create(RESOURCE)
                .method(call::apply)
                .onSuccess(saved -> {
                    // Cleared before the table is rebuilt, not after. A selection is a *row*, and a row
                    // is a position: once a rule has gone, the position a person selected is a different
                    // rule or none at all, and the buttons are decided from whatever it now points at.
                    listPresenter.getSelectionModel().clear();
                    rules.clear();
                    rules.addAll(saved);
                    update();
                    if (keepSelected != null) {
                        for (int i = 0; i < rules.size(); i++) {
                            if (keepSelected.equals(rules.get(i).getUuid())) {
                                select(i);
                                return;
                            }
                        }
                    }
                })
                .taskMonitorFactory(this)
                .exec();
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
        // And past the end is nothing, not a fault: a row outlives the table it was a row of, so a
        // caller asking during a rebuild asks about a position that may no longer exist.
        return index < 0 || index >= rules.size()
                ? null
                : rules.get(index);
    }

    private void onAdd(final ClickEvent event) {
        if (!isReadOnly()) {
            // A new rule goes to the top: the operator has just decided it is the most specific, and the
            // router takes the first match.
            showRule(RoutingRule.builder().build(),
                    rule -> apply(resource -> resource.addRule(docUuid, 0, rule), null));
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
        showRule(existing, rule -> {
            if (!existing.equals(rule)) {
                apply(resource -> resource.updateRule(docUuid, existing.getUuid(), rule), existing.getUuid());
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
                        // Not history: the stage counts a bound stream's records by it (A35).
                        .recordBoundary(existing.getRecordBoundary())
                        .build();
                final int index = selectedIndex() + 1;
                apply(resource -> resource.addRule(docUuid, index, copy), null);
            }
        }
    }

    private void onDelete(final ClickEvent event) {
        if (!isReadOnly()) {
            final int index = selectedIndex();
            if (index >= 0) {
                final String ruleUuid = rules.get(index).getUuid();
                ConfirmEvent.fire(this, "Are you sure you want to delete the selected rule?", ok -> {
                    if (ok) {
                        apply(resource -> resource.deleteRule(docUuid, ruleUuid), null);
                    }
                });
            }
        }
    }

    /// Approve a draft (A25): the promotion it was waiting for. Confirmed, because it puts a learned
    /// transform in front of live data and releases every stream that waited for it.
    private void onApprove(final ClickEvent event) {
        final RoutingRule draft = selectedDraft();
        if (draft != null) {
            ConfirmEvent.fire(this,
                    "Approve this draft? It binds "
                    + NullSafe.getOrElse(draft.getPipeline(), DocRef::getName, "its fragment")
                    + " for its shape, and every stream that waited for it is processed again.",
                    ok -> {
                        if (ok) {
                            apply(resource -> resource.approveRule(docUuid, draft.getUuid()),
                                    draft.getUuid());
                        }
                    });
        }
    }

    /// Reject a draft (A25): the rule goes and the shape is given up with the reason, so the model is
    /// not asked the same question again until somebody says otherwise. The reason is asked for rather
    /// than assumed: it is what the person who finds the shape given up next month has to read.
    private void onReject(final ClickEvent event) {
        final RoutingRule draft = selectedDraft();
        if (draft != null) {
            PromptEvent.fire(this, "Why is this draft being rejected? Its shape is given up with the "
                                   + "reason, and not learned again until somebody says otherwise.", "",
                    reason -> {
                        if (!NullSafe.isBlankString(reason)) {
                            apply(resource -> resource.rejectRule(docUuid, draft.getUuid(),
                                    new RejectRequest(reason)), null);
                        }
                    });
        }
    }

    /// The selected rule where it is a draft awaiting review, and null otherwise: approving is not
    /// something that can be done to a rule that is already live.
    private RoutingRule selectedDraft() {
        final RoutingRule rule = selected();
        return !isReadOnly() && rule != null && rule.isDraft()
                ? rule
                : null;
    }

    private void move(final int by) {
        if (!isReadOnly()) {
            final int index = selectedIndex();
            if (index >= 0) {
                final int target = index + by;
                if (target >= 0 && target < rules.size()) {
                    final String ruleUuid = rules.get(index).getUuid();
                    apply(resource -> resource.moveRule(docUuid, ruleUuid, target), ruleUuid);
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
        final boolean draft = selectedDraft() != null;
        approveButton.setEnabled(draft);
        rejectButton.setEnabled(draft);
    }


    // --------------------------------------------------------------------------------


    public interface ShapeshifterAiRoutingView
            extends View, HasUiHandlers<ShapeshifterAiSettingsUiHandlers> {

        void setTableView(View view);

        void setExpressionView(View view);
    }
}
