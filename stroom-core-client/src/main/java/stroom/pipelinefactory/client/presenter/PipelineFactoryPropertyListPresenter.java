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

import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.pipelinefactory.shared.PipelineFactoryProperty;
import stroom.svg.client.Preset;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MultiSelectionModel;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.List;

/// The values a Pipeline Factory writes onto the elements of each pipeline it makes (A113), as a table.
public class PipelineFactoryPropertyListPresenter extends MyPresenterWidget<PagerView> {

    private final MyDataGrid<PipelineFactoryProperty> dataGrid;
    private final MultiSelectionModelImpl<PipelineFactoryProperty> selectionModel;

    @Inject
    public PipelineFactoryPropertyListPresenter(final EventBus eventBus, final PagerView view) {
        super(eventBus, view);
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Property values");
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PipelineFactoryProperty row) -> text(row.getElementId())).build(),
                DataGridUtil.headingBuilder("Element")
                        .withToolTip("The id of the element in the template's chain.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((PipelineFactoryProperty row) -> text(row.getName())).build(),
                DataGridUtil.headingBuilder("Property")
                        .withToolTip("The property on that element, which must take text.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addAutoResizableColumn(
                DataGridUtil.htmlColumnBuilder((PipelineFactoryProperty row) -> text(row.getValue())).build(),
                DataGridUtil.headingBuilder("Value")
                        .withToolTip("The value, with ${feed}, ${type} or ${pipeline} replaced by the combination's.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
    }

    /// @param properties What to show.
    public void setData(final List<PipelineFactoryProperty> properties) {
        dataGrid.setRowData(0, properties);
        dataGrid.setRowCount(properties.size());
    }

    /// @return What is selected.
    public MultiSelectionModel<PipelineFactoryProperty> getSelectionModel() {
        return selectionModel;
    }

    /// @param preset The button.
    /// @return The button, added to the table's toolbar.
    public ButtonView add(final Preset preset) {
        return getView().addButton(preset);
    }

    private static SafeHtml text(final String text) {
        return SafeHtmlUtils.fromString(text == null
                ? ""
                : text);
    }
}
