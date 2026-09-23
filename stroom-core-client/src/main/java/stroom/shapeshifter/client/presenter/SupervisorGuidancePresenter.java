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

import stroom.alert.client.event.ConfirmEvent;
import stroom.alert.client.event.PromptEvent;
import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.shared.GuidanceRequest;
import stroom.shapeshifter.shared.SupervisorGuidance;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ButtonView;
import stroom.widget.customdatebox.client.ClientDateUtil;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.MultiSelectionModel;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.List;
import java.util.function.Consumer;

/**
 * Everything a supervisor has said about one shape (ruling A46), oldest first, with a way to say
 * another thing and a way to take one back.
 * <p>
 * Taking one back is why this exists rather than a count on a row. A hint that turned out to be wrong
 * is worse than no hint: it is carried into every question asked about the shape from then on, and a
 * person who cannot see what is standing cannot know that. The turns that already carried it still say
 * they did (A45) — what is withdrawn stops being carried, and does not stop having been.
 * <p>
 * Not paged. Guidance is what one person wrote about one feed; a shape with a hundred hints is a shape
 * somebody should be talking to rather than typing at.
 */
public class SupervisorGuidancePresenter extends MyPresenterWidget<PagerView> {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final RestFactory restFactory;
    private final MyDataGrid<SupervisorGuidance> dataGrid;
    private final MultiSelectionModel<SupervisorGuidance> selectionModel;
    private final ButtonView addButton;
    private final ButtonView removeButton;
    private String docUuid;
    private String shapeId;
    private Runnable onChange;

    @Inject
    public SupervisorGuidancePresenter(final EventBus eventBus,
                                       final PagerView view,
                                       final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Shapeshifter AI Guidance");
        dataGrid.setMultiLine(true);
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        initTableColumns();

        addButton = view.addButton(SvgPresets.ADD.title("Tell the learning something else about this shape"));
        removeButton = view.addButton(SvgPresets.DELETE.title("Take this back, so that it stops being carried"));
        updateButtons();
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(selectionModel.addSelectionHandler(event -> updateButtons()));
        registerHandler(addButton.addClickHandler(event -> add()));
        registerHandler(removeButton.addClickHandler(event -> remove()));
    }

    /**
     * Show what has been said about a shape.
     *
     * @param shapeId  The learning key's values, as a rule or an attempt names them.
     * @param onChange Run when something is said or taken back, so that the count on the row the dialog
     *                 was opened from is read again.
     */
    public void show(final String docUuid, final String shapeId, final Runnable onChange) {
        this.docUuid = docUuid;
        this.shapeId = shapeId;
        this.onChange = onChange;
        read(said -> {
            ShowPopupEvent.builder(this)
                    .popupType(PopupType.CLOSE_DIALOG)
                    .popupSize(PopupSize.resizable(800, 400))
                    .caption("What has been said about " + shapeId)
                    .fire();
            set(said);
        });
    }

    private void add() {
        PromptEvent.fire(this,
                "What should the learning know about this shape? It is kept against the shape, so every "
                + "question asked about it from now on carries it.", "",
                message -> {
                    // Null is Cancel; empty is nothing to record.
                    if (NullSafe.isBlankString(message)) {
                        return;
                    }
                    restFactory
                            .create(SUPERVISOR_RESOURCE)
                            .method(resource -> resource.hint(docUuid, new GuidanceRequest(shapeId, message)))
                            .onSuccess(this::changed)
                            .taskMonitorFactory(this)
                            .exec();
                });
    }

    /// Confirmed, because nothing here is versioned: a hint taken back is gone, and its words were
    /// somebody's.
    private void remove() {
        final SupervisorGuidance said = selectionModel.getSelected();
        if (said == null) {
            return;
        }
        ConfirmEvent.fire(this,
                "Take this back? It stops being carried into what is asked about this shape. The turns "
                + "that already carried it still say they did.",
                ok -> {
                    if (ok) {
                        restFactory
                                .create(SUPERVISOR_RESOURCE)
                                .method(resource -> resource.withdraw(docUuid, said.getId(), shapeId))
                                .onSuccess(this::changed)
                                .taskMonitorFactory(this)
                                .exec();
                    }
                });
    }

    private void changed(final List<SupervisorGuidance> said) {
        set(said);
        if (onChange != null) {
            // The row this was opened from shows how much has been said, and it has just changed.
            onChange.run();
        }
    }

    private void read(final Consumer<List<SupervisorGuidance>> consumer) {
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.guidance(docUuid, shapeId))
                .onSuccess(consumer::accept)
                .taskMonitorFactory(this)
                .exec();
    }

    /// The selection goes first: it holds the row object it was made from, and a rebuilt list makes new
    /// ones, so a row taken back would still be offered for taking back again.
    private void set(final List<SupervisorGuidance> said) {
        selectionModel.clear();
        dataGrid.setRowData(0, said);
        dataGrid.setRowCount(said.size(), true);
        updateButtons();
    }

    private void updateButtons() {
        removeButton.setEnabled(selectionModel.getSelected() != null);
        addButton.setEnabled(true);
    }

    private void initTableColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorGuidance said) -> text(said.getMessage())).build(),
                DataGridUtil.headingBuilder("Said")
                        .withToolTip("What was written, as it is carried into every question about this "
                                     + "shape.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorGuidance said) -> text(said.getAuthor())).build(),
                DataGridUtil.headingBuilder("By")
                        .withToolTip("Whose it is. A person reading a hint a year later needs to know.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorGuidance said) ->
                        text(ClientDateUtil.toISOString(said.getTimeMs()))).build(),
                DataGridUtil.headingBuilder("When")
                        .withToolTip("Oldest first, so that a later hint correcting an earlier one reads "
                                     + "as a correction.")
                        .build(),
                ColumnSizeConstants.DATE_COL);
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(NullSafe.string(value));
    }
}
