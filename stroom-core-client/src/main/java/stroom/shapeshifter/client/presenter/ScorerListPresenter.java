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

import stroom.cell.tickbox.shared.TickBoxState;
import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.svg.client.Preset;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MultiSelectionModel;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.List;

/**
 * The scorer set of design §8.4 as a grid: one row per scorer the document applies, with its weight in the
 * total, the score below which an attempt fails, and whether it is a gate.
 */
public class ScorerListPresenter extends MyPresenterWidget<PagerView> implements Focus {

    private final MyDataGrid<ScorerSetting> dataGrid;
    private final MultiSelectionModelImpl<ScorerSetting> selectionModel;

    @Inject
    public ScorerListPresenter(final EventBus eventBus, final PagerView view) {
        super(eventBus, view);
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Scorers");
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        initTableColumns();
    }

    @Override
    public void focus() {
        dataGrid.setFocus(true);
    }

    private void initTableColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((ScorerSetting row) -> text(row.getType().getDisplayValue()))
                        .build(),
                DataGridUtil.headingBuilder("Scorer")
                        .withToolTip("What is measured on every attempt.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((ScorerSetting row) -> text(Double.toString(row.getWeight())))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("Weight")
                        .withToolTip("How much this score contributes to the weighted total.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((ScorerSetting row) -> text(Double.toString(row.getThreshold())))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("Threshold")
                        .withToolTip("The score, from 0 to 1, below which the attempt fails this scorer.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addColumn(
                DataGridUtil.readOnlyTickBoxColumnBuilder((ScorerSetting row) ->
                        TickBoxState.fromBoolean(row.isGate())).build(),
                DataGridUtil.headingBuilder("Gate")
                        .withToolTip("A gate must reach its threshold on its own, whatever the weighted total.")
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((ScorerSetting row) -> text(row.getParameters() == null
                        ? ""
                        : row.getParameters().toString())).build(),
                DataGridUtil.headingBuilder("Parameters")
                        .withToolTip("The scorer's own settings; edit the row to change them.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(value);
    }

    public void setData(final List<ScorerSetting> scorers) {
        dataGrid.setRowData(0, scorers);
        dataGrid.setRowCount(scorers.size());
    }

    public MultiSelectionModel<ScorerSetting> getSelectionModel() {
        return selectionModel;
    }

    public ButtonView add(final Preset preset) {
        return getView().addButton(preset);
    }
}
