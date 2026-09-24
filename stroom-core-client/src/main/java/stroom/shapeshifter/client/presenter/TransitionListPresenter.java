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
import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
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

/**
 * Where one step goes, outcome by outcome (A37, design 01 §10.2): the edges out of it.
 * <p>
 * Each is taken at most once, and a step with none re-asks itself on a shortfall until its candidates
 * are gone — which is the default and needs no row here. What the rows are for is escalation: the plan
 * that goes to a target question when the direct one came back short.
 */
public class TransitionListPresenter extends MyPresenterWidget<PagerView> {

    private final MyDataGrid<Transition> dataGrid;
    private final MultiSelectionModelImpl<Transition> selectionModel;
    private final Provider<TransitionPresenter> editPresenterProvider;
    private final List<Transition> transitions = new ArrayList<>();
    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView deleteButton;
    /// The steps a transition may go to: the plan's own, as they stand while this step is being edited.
    private List<String> steps = List.of();
    private boolean readOnly;

    @Inject
    public TransitionListPresenter(final EventBus eventBus,
                                   final PagerView view,
                                   final Provider<TransitionPresenter> editPresenterProvider) {
        super(eventBus, view);
        this.editPresenterProvider = editPresenterProvider;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Transitions");
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((Transition row) -> SafeHtmlUtils.fromString(
                        NullSafe.getOrElse(row.getOn(), StepOutcome::getDisplayValue,
                                Transition.SPENT))).build(),
                DataGridUtil.headingBuilder("On")
                        .withToolTip("The outcome this fires on, or 'spent' when the step's candidates "
                                     + "are gone.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((Transition row) -> SafeHtmlUtils.fromString(
                        row.getGoTo() == null
                                ? Transition.ABANDON
                                : row.getGoTo())).build(),
                DataGridUtil.headingBuilder("Go to")
                        .withToolTip("The step it goes to, the end of the plan, or giving the attempt up.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        addButton = view.addButton(SvgPresets.ADD.title("Add transition"));
        editButton = view.addButton(SvgPresets.EDIT.title("Edit selected transition"));
        deleteButton = view.addButton(SvgPresets.DELETE.title("Remove selected transition"));
        updateButtons();
    }

    @Override
    protected void onBind() {
        registerHandler(addButton.addClickHandler(event -> {
            if (!readOnly) {
                show(new Transition(StepOutcome.PASSED, null), added -> {
                    transitions.add(added);
                    update();
                });
            }
        }));
        registerHandler(editButton.addClickHandler(event -> edit()));
        registerHandler(deleteButton.addClickHandler(event -> {
            final Transition selected = selectionModel.getSelected();
            if (!readOnly && selected != null) {
                transitions.remove(selected);
                selectionModel.clear();
                update();
            }
        }));
        registerHandler(selectionModel.addSelectionHandler(event -> {
            if (event.getSelectionType().isDoubleSelect()) {
                edit();
            }
            updateButtons();
        }));
        super.onBind();
    }

    private void edit() {
        final Transition existing = selectionModel.getSelected();
        if (!readOnly && existing != null) {
            show(existing, edited -> {
                transitions.set(transitions.indexOf(existing), edited);
                update();
            });
        }
    }

    /// Each outcome may be gone to once: a step with two transitions on one outcome would take whichever
    /// the reader happened to find first, which is not a plan but a coin toss. Said here rather than
    /// left to the store's refusal on save, so that a person is told where they typed it.
    private void show(final Transition transition, final Consumer<Transition> onOk) {
        final TransitionPresenter presenter = editPresenterProvider.get();
        presenter.read(transition, steps);
        ShowPopupEvent.builder(presenter)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(500, 250))
                .caption("Transition")
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final Transition written = presenter.write();
                    final boolean clash = transitions.stream()
                            .filter(other -> other != transition)
                            .anyMatch(other -> other.getOn() == written.getOn());
                    if (clash) {
                        AlertEvent.fireError(this, "This step already goes somewhere on '"
                                                   + NullSafe.getOrElse(written.getOn(),
                                StepOutcome::getDisplayValue, Transition.SPENT)
                                                   + "'.", e::reset);
                    } else {
                        onOk.accept(written);
                        e.hide();
                    }
                })
                .fire();
    }

    /**
     * @param steps The ids of the plan's steps, which is what a transition may go to.
     */
    public void read(final List<Transition> transitions, final List<String> steps, final boolean readOnly) {
        this.readOnly = readOnly;
        this.steps = List.copyOf(steps);
        this.transitions.clear();
        this.transitions.addAll(transitions);
        selectionModel.clear();
        update();
    }

    public List<Transition> write() {
        return new ArrayList<>(transitions);
    }

    private void update() {
        dataGrid.setRowData(0, transitions);
        dataGrid.setRowCount(transitions.size());
        updateButtons();
    }

    private void updateButtons() {
        final boolean selected = selectionModel.getSelected() != null;
        addButton.setEnabled(!readOnly);
        editButton.setEnabled(!readOnly && selected);
        deleteButton.setEnabled(!readOnly && selected);
    }
}
