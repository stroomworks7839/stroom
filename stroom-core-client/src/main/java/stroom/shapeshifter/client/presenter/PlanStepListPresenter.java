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

package stroom.shapeshifter.client.presenter;

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.PlanStep;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepGuard;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.Transition;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ButtonView;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The learning plan as a list of steps (A37, design 01 §10.2, §12 item 24), in place of the text box.
 * <p>
 * The plan is a graph over typed outcomes, and its text grammar is exact: a person learning it by having
 * a save rejected is a person learning it the hard way. Here the order is the list's, every closed list
 * is a picker, and a transition may only go to a step that is there — so most of what the grammar can
 * get wrong cannot be written at all.
 * <p>
 * The order matters: a pass goes to the next step unless a transition says otherwise, so where a step
 * sits is part of what the plan means. Rows move with the same buttons as the routing table's, and for
 * the same reason.
 * <p>
 * The text grammar has not gone. It is what the harness and import/export carry, and it is still shown —
 * read-only — beside the list, so that a person can read the whole plan at once and paste it somewhere.
 */
public class PlanStepListPresenter extends MyPresenterWidget<PagerView> {

    private final MyDataGrid<PlanStep> dataGrid;
    private final MultiSelectionModelImpl<PlanStep> selectionModel;
    private final Provider<PlanStepPresenter> editPresenterProvider;
    private final List<PlanStep> steps = new ArrayList<>();
    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView copyButton;
    private final ButtonView deleteButton;
    private final ButtonView moveUpButton;
    private final ButtonView moveDownButton;
    /// Told when the plan changes, so that the tab can redraw the text the list is a picture of.
    private Runnable onChange;
    private boolean readOnly;

    @Inject
    public PlanStepListPresenter(final EventBus eventBus,
                                 final PagerView view,
                                 final Provider<PlanStepPresenter> editPresenterProvider) {
        super(eventBus, view);
        this.editPresenterProvider = editPresenterProvider;
        // The table is a control in a form, so it is drawn as one.
        view.asWidget().addStyleName("form-control-background form-control-border");
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Plan");
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        initTableColumns();
        addButton = view.addButton(SvgPresets.ADD.title("Add step"));
        editButton = view.addButton(SvgPresets.EDIT.title("Edit selected step"));
        copyButton = view.addButton(SvgPresets.COPY.title("Copy selected step"));
        deleteButton = view.addButton(SvgPresets.DELETE.title("Remove selected step"));
        moveUpButton = view.addButton(SvgPresets.UP.title("Move selected step up"));
        moveDownButton = view.addButton(SvgPresets.DOWN.title("Move selected step down"));
        updateButtons();
    }

    @Override
    protected void onBind() {
        registerHandler(addButton.addClickHandler(event -> {
            if (!readOnly) {
                show(PlanStep.of(QuestionKind.CONFIGURE), added -> {
                    // After the selected step, as the routing table adds a rule: where a step sits is
                    // part of what the plan means, so a person adding one while looking at another
                    // means it to go there. With nothing selected it goes at the end.
                    final int after = at(selectionModel.getSelected());
                    if (after < 0) {
                        steps.add(added);
                    } else {
                        steps.add(after + 1, added);
                    }
                    changed();
                });
            }
        }));
        registerHandler(editButton.addClickHandler(event -> edit()));
        registerHandler(copyButton.addClickHandler(event -> {
            final PlanStep selected = selectionModel.getSelected();
            if (!readOnly && at(selected) >= 0) {
                // Without its id: two steps of one name is a plan whose transitions cannot say which
                // they mean, and the copy is the one that should be renamed.
                steps.add(at(selected) + 1, new PlanStep(null, selected.getKind(),
                        selected.getRole(), selected.getWhen(), selected.getCandidates(),
                        selected.getKinds(), selected.getChecks(), selected.getTransitions()));
                changed();
            }
        }));
        registerHandler(deleteButton.addClickHandler(event -> remove()));
        registerHandler(moveUpButton.addClickHandler(event -> move(-1)));
        registerHandler(moveDownButton.addClickHandler(event -> move(1)));
        registerHandler(selectionModel.addSelectionHandler(event -> {
            if (event.getSelectionType().isDoubleSelect()) {
                edit();
            }
            updateButtons();
        }));
        super.onBind();
    }

    /// A step nothing else points at goes without ceremony; one a transition names is confirmed with
    /// what would be left pointing at nothing, because the store refuses a plan whose `goto` names no
    /// step and the refusal arrives a long way from the delete that caused it.
    private void remove() {
        final PlanStep selected = selectionModel.getSelected();
        if (readOnly || at(selected) < 0) {
            return;
        }
        final String id = selected.effectiveId();
        final List<String> pointing = steps.stream()
                .filter(step -> step != selected)
                .filter(step -> step.getTransitions().stream()
                        .anyMatch(transition -> id.equals(transition.getGoTo())))
                .map(PlanStep::effectiveId)
                .toList();
        if (pointing.isEmpty()) {
            steps.remove(at(selected));
            selectionModel.clear();
            changed();
            return;
        }
        ConfirmEvent.fire(this,
                "Remove step '" + id + "'? " + String.join(", ", pointing)
                + " goes to it, and a plan whose transition names no step is refused on save.",
                ok -> {
                    if (ok) {
                        steps.remove(at(selected));
                        selectionModel.clear();
                        changed();
                    }
                });
    }

    private void move(final int by) {
        final PlanStep selected = selectionModel.getSelected();
        if (readOnly || at(selected) < 0) {
            return;
        }
        final int from = at(selected);
        final int to = from + by;
        if (to >= 0 && to < steps.size()) {
            steps.remove(from);
            steps.add(to, selected);
            changed();
            selectionModel.setSelected(selected);
        }
    }

    private void edit() {
        final PlanStep existing = selectionModel.getSelected();
        if (!readOnly && at(existing) >= 0) {
            show(existing, edited -> {
                // Looked up again on the way back, not captured: the dialog is not modal to the list
                // behind it, and a row that has moved or gone while it was open must not be written
                // over by its old position.
                final int index = at(existing);
                if (index >= 0) {
                    steps.set(index, edited);
                    changed();
                }
            });
        }
    }

    /// The form, and the two faults it cannot stop a person writing: an id that is not a word, which the
    /// step's own constructor refuses, and an id another step already has, which nothing refuses until a
    /// transition tries to name one of them.
    private void show(final PlanStep step, final Consumer<PlanStep> onOk) {
        final PlanStepPresenter presenter = editPresenterProvider.get();
        presenter.read(step, ids(), readOnly);
        ShowPopupEvent.builder(presenter)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(700, 720))
                .caption("Plan step")
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final PlanStep written;
                    try {
                        written = presenter.write();
                    } catch (final RuntimeException fault) {
                        AlertEvent.fireError(this, fault.getMessage(), e::reset);
                        return;
                    }
                    final boolean taken = steps.stream()
                            .filter(other -> other != step)
                            .anyMatch(other -> other.effectiveId().equals(written.effectiveId()));
                    if (taken) {
                        AlertEvent.fireError(this, "Another step is already called '"
                                                   + written.effectiveId() + "'. Two of one name leaves a "
                                                   + "transition unable to say which it means.", e::reset);
                    } else {
                        onOk.accept(written);
                        e.hide();
                    }
                })
                .fire();
    }

    /**
     * @param onChange Told when the plan changes, so that the read-only text beside it can be redrawn.
     */
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    public void read(final List<PlanStep> steps, final boolean readOnly) {
        this.readOnly = readOnly;
        this.steps.clear();
        this.steps.addAll(steps);
        selectionModel.clear();
        update();
    }

    public List<PlanStep> write() {
        return new ArrayList<>(steps);
    }

    private void changed() {
        update();
        if (onChange != null) {
            onChange.run();
        }
    }

    /// Where a step sits, **by identity** and not by equality.
    ///
    /// A step is a value: two of them with the same kind, guard, limits, checks and transitions and no
    /// id of their own are `equals`, and copying a step makes exactly that — the copy drops the id, so a
    /// step that never had one is copied into its own twin. `List.indexOf` would then answer with the
    /// first of the pair whichever was selected, and editing, moving or removing the second would do it
    /// to the first.
    ///
    /// The grid's rows are the very objects in this list, so identity is the right question and the
    /// only one that distinguishes them.
    private int at(final PlanStep step) {
        if (step == null) {
            return -1;
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) == step) {
                return i;
            }
        }
        return -1;
    }

    /// Every step's name, which is what a transition may go to.
    private List<String> ids() {
        return steps.stream().map(PlanStep::effectiveId).toList();
    }

    private void update() {
        dataGrid.setRowData(0, steps);
        dataGrid.setRowCount(steps.size());
        updateButtons();
    }

    private void updateButtons() {
        final PlanStep selected = selectionModel.getSelected();
        final boolean one = selected != null;
        final int index = one
                ? at(selected)
                : -1;
        addButton.setEnabled(!readOnly);
        editButton.setEnabled(!readOnly && one);
        copyButton.setEnabled(!readOnly && one);
        deleteButton.setEnabled(!readOnly && one);
        moveUpButton.setEnabled(!readOnly && index > 0);
        moveDownButton.setEnabled(!readOnly && index >= 0 && index < steps.size() - 1);
    }

    private void initTableColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PlanStep step) ->
                        SafeHtmlUtils.fromString(Integer.toString(at(step) + 1)))
                        .rightAligned().build(),
                DataGridUtil.headingBuilder("#")
                        .withToolTip("Where the step sits. A pass goes to the next one unless a "
                                     + "transition says otherwise, so the order is part of the plan.")
                        .rightAligned().build(),
                40);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PlanStep step) ->
                        SafeHtmlUtils.fromString(step.effectiveId())).build(),
                DataGridUtil.headingBuilder("Id")
                        .withToolTip("The step's name for a transition to go to.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PlanStep step) -> SafeHtmlUtils.fromString(
                        step.getKind().getDisplayValue()
                        + NullSafe.getOrElse(step.getRole(),
                                role -> " (" + role.getDisplayValue() + ")", ""))).build(),
                DataGridUtil.headingBuilder("Asks")
                        .withToolTip("What this step asks, and for a Configure step which elements.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PlanStep step) -> SafeHtmlUtils.fromString(
                        NullSafe.getOrElse(step.getWhen(), StepGuard::getDisplayValue, ""))).build(),
                DataGridUtil.headingBuilder("When")
                        .withToolTip("The input this step runs for.")
                        .build(),
                80);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PlanStep step) -> SafeHtmlUtils.fromString(
                        limits(step))).build(),
                DataGridUtil.headingBuilder("Limits")
                        .withToolTip("How many answers to ask for, and how many kinds of record to show. "
                                     + "Blank is the kind's own default.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PlanStep step) -> SafeHtmlUtils.fromString(
                        step.getChecks().stream().map(Check::getDisplayValue)
                                .collect(Collectors.joining(", ")))).build(),
                DataGridUtil.headingBuilder("Checks")
                        .withToolTip("What judges this step's answers. Blank leaves the kind's own.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PlanStep step) -> SafeHtmlUtils.fromString(
                        step.getTransitions().stream().map(PlanStepListPresenter::describe)
                                .collect(Collectors.joining(", ")))).build(),
                DataGridUtil.headingBuilder("Goes to")
                        .withToolTip("Where the step goes on an outcome. Blank re-asks itself until its "
                                     + "candidates are gone.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
    }

    private static String limits(final PlanStep step) {
        final String candidates = NullSafe.getOrElse(step.getCandidates(),
                value -> value + " candidate" + (value == 1
                        ? ""
                        : "s"), "");
        final String kinds = NullSafe.getOrElse(step.getKinds(), value -> value + " kinds", "");
        return candidates.isEmpty() || kinds.isEmpty()
                ? candidates + kinds
                : candidates + ", " + kinds;
    }

    private static String describe(final Transition transition) {
        return NullSafe.getOrElse(transition.getOn(), StepOutcome::getDisplayValue, Transition.SPENT)
               + " → " + (transition.getGoTo() == null
                ? Transition.ABANDON
                : transition.getGoTo());
    }
}
