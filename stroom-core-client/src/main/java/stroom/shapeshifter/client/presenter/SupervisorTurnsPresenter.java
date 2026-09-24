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

import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.dispatch.client.RestFactory;
import stroom.editor.client.presenter.EditorPresenter;
import stroom.shapeshifter.shared.AmendTurnRequest;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ButtonView;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.MultiSelectionModel;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One attempt's conversation, turn by turn (A28): what was asked, what was answered and by whom, and what
 * the answer scored. A turn with no answer is the question the attempt stopped at, which is what a
 * person may answer instead.
 */
public class SupervisorTurnsPresenter extends MyPresenterWidget<PagerView> {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final MyDataGrid<SupervisorTurn> dataGrid;
    private final MultiSelectionModel<SupervisorTurn> selectionModel;
    private final RestFactory restFactory;
    private final Provider<EditorPresenter> editorProvider;
    private final ButtonView answerButton;
    private final List<SupervisorTurn> turns = new ArrayList<>();
    /// Which attempt the grid is showing: a person clicking down a list asks faster than the server
    /// answers, and the answer to a row they have left must not land in the grid under the row they are
    /// on now.
    private int asked;
    /// Which attempt's conversation is on show, so that an answer is written against the right one.
    private SupervisorAttempt attempt;
    /// What to do when an answer has been written: the attempt has changed and the list above it with it.
    private Consumer<SupervisorAttempt> onAmended;

    @Inject
    public SupervisorTurnsPresenter(final EventBus eventBus,
                                    final PagerView view,
                                    final RestFactory restFactory,
                                    final Provider<EditorPresenter> editorProvider) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.editorProvider = editorProvider;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Turns");
        dataGrid.setMultiLine(true);
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        initTableColumns();
        dataGrid.setRowData(0, turns);
        dataGrid.setRowCount(turns.size(), true);
        // A28's two acts on a turn, which are one act: *answer instead* for the question an attempt
        // stopped at, and *edit and re-run from here* for one already answered. The same button, because
        // the difference is whether there is an answer there — and a person editing turn 7 of a finished
        // attempt is doing exactly what a person answering turn 7 of a parked one is.
        answerButton = view.addButton(SvgPresets.EDIT.title(
                "Answer this turn instead, or edit its answer and run the attempt again from here"));
        updateButtons();
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(selectionModel.addSelectionHandler(event -> updateButtons()));
        registerHandler(answerButton.addClickHandler(event -> answer()));
    }

    /// Called when an answer has been written and the attempt run again from there.
    public void setOnAmended(final Consumer<SupervisorAttempt> onAmended) {
        this.onAmended = onAmended;
    }

    /// Answer a turn in place of whoever answered it (A28).
    ///
    /// An editor and not a prompt box, because an answer is often a configuration — a Data Splitter
    /// document, a stylesheet — and a single-line box cannot hold one, let alone show a person what they
    /// are editing.
    ///
    /// Nothing is run here. The answer is written and the attempt is left waiting for the worker to
    /// carry on from what it has now been told, because a person's request must not wait on a model. The
    /// turns after the one answered are discarded: what they were is a consequence of an answer that has
    /// changed, and re-walking derives them again (A45).
    private void answer() {
        final SupervisorTurn turn = selectionModel.getSelected();
        if (turn == null || attempt == null) {
            return;
        }
        // Which attempt this answer is for, taken now. The popup does not stop a person clicking
        // another attempt in the list behind it, and reading the field when OK is pressed would write a
        // hand-written answer into whatever they had moved on to — reopening that attempt and taking
        // its shape back. A reader meeting state that was not built for it, in a window.
        final long attemptId = attempt.getId();
        final EditorPresenter editor = editorProvider.get();
        editor.setMode(AceEditorMode.XML);
        editor.setText(NullSafe.string(turn.getAnswer()));
        editor.getLineNumbersOption().setOn();
        ShowPopupEvent.builder(editor)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(900, 600))
                .caption((NullSafe.isBlankString(turn.getAnswer())
                        ? "Answer turn "
                        : "Edit turn ") + turn.getNumber() + ": " + NullSafe.string(turn.getQuestion()))
                .onHideRequest(event -> {
                    if (event.isOk()) {
                        amend(attemptId, turn, editor.getText());
                    }
                    event.hide();
                })
                .fire();
    }

    private void amend(final long attemptId, final SupervisorTurn turn, final String answer) {
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.amend(attemptId, turn.getNumber(),
                        new AmendTurnRequest(answer)))
                .onSuccess(amended -> {
                    if (onAmended != null) {
                        // The attempt is open again and has taken its shape back, so the list above is
                        // showing a status that is no longer true. Told first, because refreshing the
                        // list clears the selection and reading the transcript under a cleared
                        // selection would blank the answer just written.
                        onAmended.accept(amended);
                    }
                    read(amended);
                })
                .taskMonitorFactory(getView())
                .exec();
    }

    private void updateButtons() {
        answerButton.setEnabled(attempt != null && selectionModel.getSelected() != null);
    }

    /**
     * Read the conversation of one attempt. The list gives the row without its turns — a page of transcripts
     * is a page nobody reads — so the detail is fetched when a row is chosen.
     */
    public void read(final SupervisorAttempt attempt) {
        final int request = ++asked;
        this.attempt = attempt;
        turns.clear();
        show();
        updateButtons();
        if (attempt != null) {
            restFactory
                    .create(SUPERVISOR_RESOURCE)
                    .method(resource -> resource.fetch(attempt.getId()))
                    .onSuccess(detail -> {
                        if (request == asked) {
                            turns.addAll(NullSafe.list(detail.getTurns()));
                            show();
                            updateButtons();
                        }
                    })
                    .onFailure(error -> {
                        // The grid says nothing rather than going on showing the turns of whatever was
                        // selected before.
                        if (request == asked) {
                            show();
                        }
                    })
                    .taskMonitorFactory(getView())
                    .exec();
        }
    }

    private void show() {
        // The selection goes first: it holds the row object it was made from, and a rebuilt list makes
        // new ones, so a turn answered would still be offered as the turn to answer.
        selectionModel.clear();
        dataGrid.setRowData(0, turns);
        dataGrid.setRowCount(turns.size(), true);
    }

    private void initTableColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) -> text(Integer.toString(turn.getNumber())))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("#")
                        .withToolTip("The turn's place in the conversation.")
                        .rightAligned()
                        .build(),
                40);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) -> text(turn.getQuestion())).build(),
                DataGridUtil.headingBuilder("Question")
                        .withToolTip("What was asked: the kind of question and what it was about. Not the "
                                     + "rendered prompt, which carries the stream's own text.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) -> text(turn.getAnswer())).build(),
                DataGridUtil.headingBuilder("Answer")
                        .withToolTip("What was answered. Empty for the question the attempt stopped at, "
                                     + "which nobody has answered yet.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) -> text(turn.getAnsweredBy())).build(),
                DataGridUtil.headingBuilder("Answered by")
                        .withToolTip("The model the document names, or the person who answered instead.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) ->
                        text(NullSafe.get(turn.getOutcome(), outcome -> outcome.getDisplayValue()))).build(),
                DataGridUtil.headingBuilder("Outcome")
                        .withToolTip("What the answer scored when it was run, where it has been run.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(value == null
                ? ""
                : value);
    }
}
