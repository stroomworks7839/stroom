package stroom.query.client.presenter;

import stroom.datasource.api.v2.FindFieldInfoCriteria;
import stroom.dispatch.client.RestFactory;
import stroom.docref.StringMatch;
import stroom.item.client.NavigationModel;
import stroom.item.client.SelectionListModel;
import stroom.query.shared.QueryHelpRequest;
import stroom.query.shared.QueryHelpRow;
import stroom.query.shared.QueryHelpType;
import stroom.query.shared.QueryResource;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.Timer;
import com.google.gwt.view.client.AbstractDataProvider;
import com.google.gwt.view.client.AsyncDataProvider;
import com.google.gwt.view.client.HasData;
import com.google.gwt.view.client.Range;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Stack;
import java.util.stream.Collectors;
import javax.inject.Inject;

public class DynamicQueryHelpSelectionListModel implements SelectionListModel<QueryHelpRow, QueryHelpSelectionItem> {

    private static final QueryResource QUERY_RESOURCE = GWT.create(QueryResource.class);

    private final RestFactory restFactory;
    private final AsyncDataProvider<QueryHelpSelectionItem> dataProvider;
    private final NavigationModel<QueryHelpSelectionItem> navigationModel = new NavigationModel<>();
    private StringMatch filter;

    private Timer requestTimer;
    private String currentQuery;

    @Inject
    public DynamicQueryHelpSelectionListModel(final RestFactory restFactory) {
        this.restFactory = restFactory;

        dataProvider = new AsyncDataProvider<QueryHelpSelectionItem>() {
            @Override
            protected void onRangeChanged(final HasData<QueryHelpSelectionItem> display) {
                refresh(display);
            }
        };
    }

    private void refresh(final HasData<QueryHelpSelectionItem> display) {
        final Range range = display.getVisibleRange();
        final PageRequest pageRequest = new PageRequest(range.getStart(), range.getLength());
        final String parentId;
        final Stack<QueryHelpSelectionItem> openItems = navigationModel.getPath();
        if (!openItems.empty()) {
            parentId = unwrap(openItems.peek()).getId() + ".";
        } else {
            parentId = "";
        }

        final CriteriaFieldSort sort = new CriteriaFieldSort(
                FindFieldInfoCriteria.SORT_BY_NAME,
                false,
                true);
        final QueryHelpRequest request = new QueryHelpRequest(
                pageRequest,
                Collections.singletonList(sort),
                currentQuery,
                null,
                parentId,
                filter);
        restFactory.builder()
                .forResultPageOf(QueryHelpRow.class)
                .onSuccess(response -> {
                    final ResultPage<QueryHelpRow> resultPage;
                    if (response.getValues().size() > 0) {
                        resultPage = response;
                    } else {
                        final List<QueryHelpRow> rows = Collections
                                .singletonList(QueryHelpRow
                                        .builder()
                                        .type(QueryHelpType.TITLE)
                                        .id(parentId + "none")
                                        .title("[ none ]")
                                        .build());
                        resultPage = new ResultPage<>(rows);
                    }
                    final List<QueryHelpSelectionItem> items = resultPage
                            .getValues()
                            .stream()
                            .map(this::wrap)
                            .collect(Collectors.toList());
                    display.setRowData((int) response.getPageResponse().getOffset(), items);
                    display.setRowCount(response.getPageResponse().getTotal().intValue(),
                            response.getPageResponse().isExact());
                })
                .call(QUERY_RESOURCE)
                .fetchQueryHelpItems(request);
    }

    public void setCurrentQuery(final String currentQuery) {
        // Debounce requests so we don't spam the backend
        if (requestTimer != null) {
            requestTimer.cancel();
        }

        requestTimer = new Timer() {
            @Override
            public void run() {
                if (!Objects.equals(DynamicQueryHelpSelectionListModel.this.currentQuery, currentQuery)) {
                    DynamicQueryHelpSelectionListModel.this.currentQuery = currentQuery;
                    refresh();
                }
            }
        };
        requestTimer.schedule(400);
    }

    @Override
    public AbstractDataProvider<QueryHelpSelectionItem> getDataProvider() {
        return dataProvider;
    }

    @Override
    public NavigationModel<QueryHelpSelectionItem> getNavigationModel() {
        return navigationModel;
    }

    @Override
    public void setFilter(final String filter) {
        if (filter == null || filter.length() == 0) {
            this.filter = StringMatch.any();
            refresh();
        } else {
            this.filter = StringMatch.contains(filter);
            refresh();
        }
    }

    @Override
    public void refresh() {
        for (final HasData<QueryHelpSelectionItem> display : dataProvider.getDataDisplays()) {
            refresh(display);
        }
    }

    @Override
    public boolean displayPath() {
        return true;
    }

    @Override
    public boolean displayPager() {
        return true;
    }

    @Override
    public QueryHelpSelectionItem wrap(final QueryHelpRow item) {
        return new QueryHelpSelectionItem(item);
    }

    @Override
    public QueryHelpRow unwrap(final QueryHelpSelectionItem selectionItem) {
        if (selectionItem == null) {
            return null;
        }
        return selectionItem.getQueryHelpRow();
    }
}
