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
import stroom.shapeshifter.shared.RoutingRule;
import stroom.svg.client.Preset;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ButtonView;
import stroom.widget.customdatebox.client.ClientDateUtil;
import stroom.widget.util.client.MultiSelectionModel;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * The routing table as a grid, one row per rule in table order. Order is specificity (A22), so the row
 * number is the rule's only identity and is assigned here, not stored.
 */
public class RoutingRuleListPresenter extends MyPresenterWidget<PagerView> implements Focus {

    private final MyDataGrid<RoutingRow> dataGrid;
    private final MultiSelectionModelImpl<RoutingRow> selectionModel;
    private final List<RoutingRow> rows = new ArrayList<>();

    @Inject
    public RoutingRuleListPresenter(final EventBus eventBus, final PagerView view) {
        super(eventBus, view);
        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Routing Rules");
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);
        initTableColumns(eventBus);
    }

    @Override
    public void focus() {
        dataGrid.setFocus(true);
    }

    private void initTableColumns(final EventBus eventBus) {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((RoutingRow row) -> text(Integer.toString(row.number())))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("#")
                        .withToolTip("Rules are tried in this order; the first whose selector matches binds.")
                        .rightAligned()
                        .build(),
                40);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((RoutingRow row) -> text(selector(row.rule()))).build(),
                DataGridUtil.headingBuilder("Selector")
                        .withToolTip("The expression over the stream's metadata, headers and record shape.")
                        .build(),
                500);
        dataGrid.addResizableColumn(
                DataGridUtil.docRefColumnBuilder((RoutingRow row) -> row.rule().getPipeline(), eventBus).build(),
                DataGridUtil.headingBuilder("Fragment")
                        .withToolTip("The pipeline fragment the rule routes to. Open it to read or step it.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((RoutingRow row) -> text(state(row.rule()))).build(),
                DataGridUtil.headingBuilder("State")
                        .withToolTip("Bound: routes streams. Draft: awaiting approval; the router skips it. " +
                                     "Provisional: serving while held-out records accumulate. " +
                                     "Reserved: no fragment; a matching shape is given up rather than learned.")
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((RoutingRow row) -> text(score(row.rule())))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("Score")
                        .withToolTip("The held-out score the fragment was promoted on.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((RoutingRow row) ->
                        text(ClientDateUtil.toISOString(row.rule().getPromotedTimeMs()))).build(),
                DataGridUtil.headingBuilder("Promoted")
                        .withToolTip("When the supervisor promoted this fragment. Blank for a hand-written rule.")
                        .build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addColumn(
                DataGridUtil.readOnlyTickBoxColumnBuilder((RoutingRow row) ->
                        TickBoxState.fromBoolean(row.rule().isPinned())).build(),
                DataGridUtil.headingBuilder("Pinned")
                        .withToolTip("A pinned rule is never rebound by promotion.")
                        .build(),
                ColumnSizeConstants.SMALL_COL);
    }

    private static String selector(final RoutingRule rule) {
        return rule.getExpression() == null
                ? "(all streams)"
                : rule.getExpression().toString();
    }

    private static String state(final RoutingRule rule) {
        if (rule.isReserved()) {
            return "Reserved";
        } else if (rule.isDraft()) {
            return "Draft";
        } else if (rule.isProvisional()) {
            return "Provisional";
        }
        return "Bound";
    }

    private static String score(final RoutingRule rule) {
        return rule.getScore() == null
                ? ""
                : rule.getScore().toString();
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(value == null
                ? ""
                : value);
    }

    public void setData(final List<RoutingRule> rules) {
        rows.clear();
        for (int i = 0; i < rules.size(); i++) {
            rows.add(new RoutingRow(i + 1, rules.get(i)));
        }
        dataGrid.setRowData(0, rows);
        dataGrid.setRowCount(rows.size());
    }

    public MultiSelectionModel<RoutingRow> getSelectionModel() {
        return selectionModel;
    }

    public void selectRow(final int index) {
        if (index >= 0 && index < rows.size()) {
            selectionModel.setSelected(rows.get(index));
        }
    }

    public ButtonView add(final Preset preset) {
        return getView().addButton(preset);
    }


    // --------------------------------------------------------------------------------


    /**
     * A rule with its position in the table.
     */
    public static final class RoutingRow {

        private final int number;
        private final RoutingRule rule;

        private RoutingRow(final int number, final RoutingRule rule) {
            this.number = number;
            this.rule = rule;
        }

        public int number() {
            return number;
        }

        public RoutingRule rule() {
            return rule;
        }
    }
}
