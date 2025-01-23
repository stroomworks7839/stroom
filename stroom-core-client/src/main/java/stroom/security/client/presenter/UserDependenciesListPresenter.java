package stroom.security.client.presenter;

import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.client.presenter.DocRefCell;
import stroom.data.client.presenter.DocRefCell.DocRefProvider;
import stroom.data.client.presenter.RestDataProvider;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.query.api.v2.ExpressionOperator;
import stroom.security.shared.FindUserDependenciesCriteria;
import stroom.security.shared.QuickFilterExpressionParser;
import stroom.security.shared.UserFields;
import stroom.security.shared.UserResource;
import stroom.ui.config.client.UiConfigCache;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.GwtNullSafe;
import stroom.util.shared.ResultPage;
import stroom.util.shared.UserDependency;
import stroom.util.shared.UserRef;
import stroom.widget.dropdowntree.client.view.QuickFilterPageView;
import stroom.widget.dropdowntree.client.view.QuickFilterTooltipUtil;
import stroom.widget.dropdowntree.client.view.QuickFilterUiHandlers;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.view.client.Range;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.Objects;
import java.util.function.Consumer;

public class UserDependenciesListPresenter
        extends MyPresenterWidget<QuickFilterPageView>
        implements QuickFilterUiHandlers {

    private static final UserResource USER_RESOURCE = GWT.create(UserResource.class);

    private final MyDataGrid<UserDependency> dataGrid;
    private final MultiSelectionModelImpl<UserDependency> selectionModel;
    private final RestFactory restFactory;
    private final FindUserDependenciesCriteria.Builder criteriaBuilder = FindUserDependenciesCriteria.builder();
    private final PagerView pagerView;

    private UserRef userRef;
    private RestDataProvider<UserDependency, ResultPage<UserDependency>> dataProvider;
    private ResultPage<UserDependency> currentData = null;
    private Consumer<ResultPage<UserDependency>> resultPageConsumer;
    private String filter;

    @Inject
    public UserDependenciesListPresenter(final EventBus eventBus,
                                         final PagerView pagerView,
                                         final RestFactory restFactory,
                                         final QuickFilterPageView dependenciesListView,
                                         final UiConfigCache uiConfigCache) {
        super(eventBus, dependenciesListView);
        this.restFactory = restFactory;
        this.pagerView = pagerView;

        dataGrid = new MyDataGrid<>();
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        pagerView.setDataWidget(dataGrid);

        // Not easy to determine if we are dealing in users or groups at this point so just
        // call it Quick Filter
        uiConfigCache.get(
                uiConfig -> {
                    if (uiConfig != null) {
                        dependenciesListView.registerPopupTextProvider(() -> QuickFilterTooltipUtil.createTooltip(
                                "Quick Filter",
                                FindUserDependenciesCriteria.FILTER_FIELD_DEFINITIONS,
                                uiConfig.getHelpUrlQuickFilter()));
                    }
                },
                this);

        dependenciesListView.setDataView(pagerView);
        dependenciesListView.setUiHandlers(this);
    }

    @Override
    public void onFilterChange(final String text) {
        filter = GwtNullSafe.trim(text);
        if (filter.isEmpty()) {
            filter = null;
        }
        refresh();
    }

    public void setUserRef(final UserRef userRef) {
        this.userRef = Objects.requireNonNull(userRef);
        this.criteriaBuilder.userRef(userRef);
        refresh();
    }

    public void refresh() {
        if (dataProvider == null) {
            initDataProvider();
        } else {
            GWT.log(this.getClass().getSimpleName() + " - refresh");
            dataProvider.refresh();
        }
    }

    private void initDataProvider() {
        GWT.log(this.getClass().getSimpleName() + " - initDataProvider");
        setupColumns();
        this.dataProvider = new RestDataProvider<UserDependency, ResultPage<UserDependency>>(getEventBus()) {
            @Override
            protected void exec(final Range range,
                                final Consumer<ResultPage<UserDependency>> dataConsumer,
                                final RestErrorHandler errorHandler) {

                if (userRef != null) {
                    // TODO fix fields
                    ExpressionOperator expression = QuickFilterExpressionParser
                            .parse(filter, UserFields.DEFAULT_FIELDS, UserFields.ALL_FIELDS_MAP);

                    restFactory
                            .create(USER_RESOURCE)
                            .method(res -> res.findDependencies(criteriaBuilder.build()))
                            .onSuccess(userResultPage -> {
//                            GWT.log(name + " - onSuccess, size: " + userResultPage.size()
//                                    + ", expr: " + criteriaBuilder.getExpression());
                                dataConsumer.accept(userResultPage);
                            })
                            .onFailure(errorHandler)
                            .taskMonitorFactory(pagerView)
                            .exec();
                }
            }

            @Override
            protected void changeData(final ResultPage<UserDependency> data) {
                currentData = data;
                super.changeData(data);
                if (resultPageConsumer != null) {
                    resultPageConsumer.accept(data);
                }
                if (!data.isEmpty()) {
                    selectionModel.setSelected(data.getFirst());
                } else {
                    selectionModel.clear();
                }
            }
        };
        dataProvider.addDataDisplay(dataGrid);
    }

    private void setupColumns() {
        DataGridUtil.addColumnSortHandler(dataGrid, criteriaBuilder, this::refresh);

        // Doc type icon col
//        dataGrid.addColumn(
//                DataGridUtil.svgPresetColumnBuilder(
//                                false,
//                                (UserDependency row) -> {
//                                    final String documentType = GwtNullSafe.get(
//                                            row,
//                                            UserDependency::getDocRef,
//                                            DocRef::getType);
//                                    if (documentType != null) {
//                                        return GwtNullSafe.get(documentTypes.getDocumentType(documentType),
//                                                DocumentType::getIcon,
//                                                svg -> new Preset(svg, documentType, true));
//                                    } else {
//                                        return null;
//                                    }
//                                })
//                        .build(),
//                "</br>",
//                ColumnSizeConstants.ICON_COL);

        // Doc name col
        final DocRefCell.Builder<UserDependency> cellBuilder =
                new DocRefCell.Builder<UserDependency>()
                        .eventBus(getEventBus())
                        .showIcon(true);

        final Column<UserDependency, DocRefProvider<UserDependency>> docNameCol = DataGridUtil.docRefColumnBuilder(
                        (UserDependency row) ->
                                GwtNullSafe.get(
                                        row,
                                        row2 -> new DocRefProvider<>(row2, UserDependency::getDocRef)),
                        cellBuilder)
                .withSorting(FindUserDependenciesCriteria.FIELD_DOC_NAME)
                .build();
        dataGrid.addResizableColumn(
                docNameCol,
                DataGridUtil.headingBuilder("Document Name")
                        .withToolTip("The document that depends on this user.")
                        .build(),
                ColumnSizeConstants.BIG_COL);

        // Details col
        dataGrid.addAutoResizableColumn(
                DataGridUtil.textColumnBuilder(UserDependency::getDetails)
                        .withSorting(FindUserDependenciesCriteria.FIELD_DETAILS)
                        .build(),
                DataGridUtil.headingBuilder("Details")
                        .withToolTip("The details of the dependency.")
                        .build(),
                700);

        DataGridUtil.addEndColumn(dataGrid);
        dataGrid.getColumnSortList().push(docNameCol);
    }
}
