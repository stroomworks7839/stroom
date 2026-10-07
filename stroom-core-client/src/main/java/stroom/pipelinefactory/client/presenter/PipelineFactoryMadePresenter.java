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

package stroom.pipelinefactory.client.presenter;

import stroom.alert.client.event.ConfirmEvent;
import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.entity.client.presenter.DocPresenter;
import stroom.pipelinefactory.shared.PipelineFactoryCombination;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.pipelinefactory.shared.PipelineFactoryResource;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ButtonView;
import stroom.widget.customdatebox.client.ClientDateUtil;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// What a Pipeline Factory has met and made (design 06 §3.5): one row per combination, with what became
/// of its pipeline. *Forget* lets a combination's next stream make its pipeline afresh; the pipeline
/// already made, if any, is left alone.
public class PipelineFactoryMadePresenter extends DocPresenter<PagerView, PipelineFactoryDoc> {

    private static final PipelineFactoryResource RESOURCE = GWT.create(PipelineFactoryResource.class);

    private final RestFactory restFactory;
    private final MyDataGrid<PipelineFactoryCombination> dataGrid;
    private final MultiSelectionModelImpl<PipelineFactoryCombination> selectionModel;
    private final ButtonView openButton;
    private final ButtonView forgetButton;
    private final ButtonView refreshButton;
    private String factoryUuid;

    @Inject
    public PipelineFactoryMadePresenter(final EventBus eventBus,
                                        final PagerView view,
                                        final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Combinations met");
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        view.setDataWidget(dataGrid);
        addColumns();
        openButton = view.addButton(SvgPresets.OPEN.title("Open the pipeline made for the selected combination"));
        forgetButton = view.addButton(SvgPresets.DELETE.title(
                "Forget the selected combination, so that its next stream makes its pipeline afresh"));
        refreshButton = view.addButton(SvgPresets.REFRESH_BLUE.title("Read again what the factory has met"));
        updateButtons();
    }

    @Override
    protected void onBind() {
        registerHandler(openButton.addClickHandler(event -> open()));
        registerHandler(forgetButton.addClickHandler(event -> forget()));
        registerHandler(refreshButton.addClickHandler(event -> refresh()));
        registerHandler(selectionModel.addSelectionHandler(event -> {
            updateButtons();
            if (event.getSelectionType().isDoubleSelect()) {
                open();
            }
        }));
        super.onBind();
    }

    @Override
    protected void onRead(final DocRef docRef, final PipelineFactoryDoc doc, final boolean readOnly) {
        factoryUuid = docRef.getUuid();
        refresh();
    }

    @Override
    protected PipelineFactoryDoc onWrite(final PipelineFactoryDoc doc) {
        // What the factory has met is runtime state, not part of the document.
        return doc;
    }

    private void refresh() {
        if (factoryUuid == null) {
            return;
        }
        restFactory.create(RESOURCE)
                .method(resource -> resource.combinations(factoryUuid))
                .onSuccess(this::setData)
                .taskMonitorFactory(this)
                .exec();
    }

    private void setData(final List<PipelineFactoryCombination> combinations) {
        // Newest first: what a person comes here to see is what the factory has just met.
        final List<PipelineFactoryCombination> rows = new ArrayList<>(combinations);
        Collections.reverse(rows);
        selectionModel.clear();
        dataGrid.setRowData(0, rows);
        dataGrid.setRowCount(rows.size());
        updateButtons();
    }

    private void open() {
        final PipelineFactoryCombination selected = selectionModel.getSelected();
        if (selected != null && selected.isPipelineExists()) {
            OpenDocumentEvent.fire(this, selected.getPipeline(), true);
        }
    }

    private void forget() {
        final PipelineFactoryCombination selected = selectionModel.getSelected();
        if (selected == null || isReadOnly()) {
            return;
        }
        final String also = selected.isPipelineExists()
                ? " The pipeline already made for it is left as it is, so its next stream will make a second one."
                : "";
        final String question = "Forget " + describe(selected) + "? Its next stream makes its pipeline afresh."
                                + also;
        ConfirmEvent.fire(this, question, ok -> {
            if (ok) {
                restFactory.create(RESOURCE)
                        .method(resource -> resource.forget(factoryUuid, selected.getValues()))
                        .onSuccess(forgotten -> refresh())
                        .taskMonitorFactory(this)
                        .exec();
            }
        });
    }

    private void updateButtons() {
        final PipelineFactoryCombination selected = selectionModel.getSelected();
        openButton.setEnabled(selected != null && selected.isPipelineExists());
        forgetButton.setEnabled(selected != null && !isReadOnly());
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PipelineFactoryCombination row) -> text(describe(row))).build(),
                DataGridUtil.headingBuilder("Combination")
                        .withToolTip("The key values the factory met.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PipelineFactoryCombination row) -> text(state(row))).build(),
                DataGridUtil.headingBuilder("State")
                        .withToolTip("Whether its pipeline was made, and whether it is still there.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PipelineFactoryCombination row) -> text(row.getPipeline() == null
                        ? ""
                        : row.getPipeline().getName())).build(),
                DataGridUtil.headingBuilder("Pipeline")
                        .withToolTip("The pipeline made for it.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PipelineFactoryCombination row) ->
                        text(Integer.toString(row.getTries()))).rightAligned().build(),
                DataGridUtil.headingBuilder("Tries")
                        .withToolTip("How many times the factory has tried to make its pipeline.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PipelineFactoryCombination row) ->
                        text(ClientDateUtil.toISOString(row.getCreateTimeMs()))).build(),
                DataGridUtil.headingBuilder("First met").build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PipelineFactoryCombination row) ->
                        text(ClientDateUtil.toISOString(row.getUpdateTimeMs()))).build(),
                DataGridUtil.headingBuilder("Last changed").build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addAutoResizableColumn(
                DataGridUtil.htmlColumnBuilder((PipelineFactoryCombination row) -> text(row.getMessage())).build(),
                DataGridUtil.headingBuilder("Why it failed")
                        .withToolTip("Why the last try to make its pipeline failed. It is tried again later.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
    }

    private static String describe(final PipelineFactoryCombination row) {
        return String.join(" / ", row.getValues().values());
    }

    private static String state(final PipelineFactoryCombination row) {
        if (PipelineFactoryCombination.MADE.equals(row.getState())) {
            return row.isPipelineExists()
                    ? "Made"
                    : "Made, then deleted";
        } else if (PipelineFactoryCombination.FAILED.equals(row.getState())) {
            return "Failed";
        }
        return "Being made";
    }

    private static SafeHtml text(final String text) {
        return SafeHtmlUtils.fromString(text == null
                ? ""
                : text);
    }
}
