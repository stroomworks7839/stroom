package stroom.analytics.client.presenter;

import stroom.alert.client.event.AlertEvent;
import stroom.analytics.shared.ExecutionSchedule;
import stroom.analytics.shared.ExecutionScheduleResource;
import stroom.analytics.shared.ScheduleBounds;
import stroom.dispatch.client.RestFactory;
import stroom.document.client.event.DirtyEvent;
import stroom.document.client.event.DirtyEvent.DirtyHandler;
import stroom.document.client.event.HasDirtyHandlers;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.job.shared.ScheduleReferenceTime;
import stroom.job.shared.ScheduleRestriction;
import stroom.node.client.NodeManager;
import stroom.schedule.client.SchedulePopup;
import stroom.widget.datepicker.client.DateTimePopup;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.popup.client.presenter.Size;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.function.Consumer;

public class ScheduledProcessEditPresenter
        extends MyPresenterWidget<ScheduledProcessEditView>
        implements ProcessingStatusUiHandlers, HasDirtyHandlers {

    private static final ExecutionScheduleResource EXECUTION_SCHEDULE_RESOURCE =
            GWT.create(ExecutionScheduleResource.class);

    private final DocSelectionBoxPresenter errorFeedPresenter;
    private ExecutionSchedule executionSchedule;

    @Inject
    public ScheduledProcessEditPresenter(final EventBus eventBus,
                                         final ScheduledProcessEditView view,
                                         final DocSelectionBoxPresenter errorFeedPresenter,
                                         final NodeManager nodeManager,
                                         final Provider<SchedulePopup> schedulePresenterProvider,
                                         final Provider<DateTimePopup> dateTimePopupProvider,
                                         final RestFactory restFactory) {
        super(eventBus, view);
        view.setUiHandlers(this);
        view.getStartTime().setPopupProvider(dateTimePopupProvider);
        view.getEndTime().setPopupProvider(dateTimePopupProvider);
        this.errorFeedPresenter = errorFeedPresenter;

        view.getScheduleBox().setSchedulePresenterProvider(schedulePresenterProvider);
        view.getScheduleBox().setScheduleRestriction(new ScheduleRestriction(false, false, true));
        view.getScheduleBox().setScheduleReferenceTimeConsumer(scheduleReferenceTimeConsumer -> restFactory
                .create(EXECUTION_SCHEDULE_RESOURCE)
                .method(res -> res.fetchTracker(executionSchedule))
                .onSuccess(tracker -> {
                    Long lastExecuted = null;
                    if (tracker != null) {
                        lastExecuted = tracker.getLastEffectiveExecutionTimeMs();
                    }
                    Long referenceTime = lastExecuted;
                    if (referenceTime == null) {
                        referenceTime = getView().getStartTime().getValue();
                    }
                    if (referenceTime == null) {
                        referenceTime = System.currentTimeMillis();
                    }

                    scheduleReferenceTimeConsumer.accept(new ScheduleReferenceTime(referenceTime,
                            lastExecuted));
                })
                .exec());

        nodeManager.listAllNodes(
                list -> {
                    if (list != null && list.size() > 0) {
                        getView().setNodes(list);
                    }
                },
                throwable -> AlertEvent
                        .fireError(this,
                                "Error",
                                throwable.getMessage(),
                                null));
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(errorFeedPresenter.addDataSelectionHandler(e -> onDirty()));
        registerHandler(getView().getScheduleBox().addValueChangeHandler(e -> onDirty()));
    }

    public void show(final ExecutionSchedule executionSchedule,
                     final Consumer<ExecutionSchedule> consumer) {
        read(executionSchedule);

        final Size width = Size.builder().max(1000).resizable(true).build();
        final Size height = Size.builder().build();
        final PopupSize popupSize = PopupSize.builder().width(width).height(height).build();

        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(popupSize)
                .caption(executionSchedule.getId() == null
                        ? "Create Schedule"
                        : "Edit Schedule")
                .onShow(e -> getView().focus())
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        write(written -> {
                            consumer.accept(written);
                            e.hide();
                        });
                    } else {
                        e.hide();
                    }
                })
                .fire();
    }

    public void read(final ExecutionSchedule executionSchedule) {
        this.executionSchedule = executionSchedule;
        getView().setName(executionSchedule.getName());
        getView().setEnabled(executionSchedule.isEnabled());
        getView().setNode(executionSchedule.getNodeName());
        setScheduleBounds(executionSchedule.getScheduleBounds());
        getView().getScheduleBox().setValue(executionSchedule.getSchedule());
    }

    public void write(final Consumer<ExecutionSchedule> consumer) {
        getView().getScheduleBox().validate(scheduledTimes -> {
            if (scheduledTimes.isError()) {
                AlertEvent.fireWarn(this, scheduledTimes.getError(), null);
            } else {
                if (!getView().getStartTime().isValid()) {
                    AlertEvent.fireWarn(this, "Invalid start time", null);
                } else if (!getView().getEndTime().isValid()) {
                    AlertEvent.fireWarn(this, "Invalid end time", null);
                } else {
                    final ScheduleBounds scheduleBounds = new ScheduleBounds(
                            getView().getStartTime().getValue(),
                            getView().getEndTime().getValue());
                    final ExecutionSchedule schedule = executionSchedule
                            .copy()
                            .name(getView().getName())
                            .enabled(getView().isEnabled())
                            .nodeName(getView().getNode())
                            .schedule(scheduledTimes.getSchedule())
                            .contiguous(true)
                            .scheduleBounds(scheduleBounds)
                            .build();
                    consumer.accept(schedule);
                }
            }
        });
    }

    @Override
    public void onRefreshProcessingStatus() {
    }

    @Override
    public void onDirty() {
        DirtyEvent.fire(this, true);
    }

    @Override
    public HandlerRegistration addDirtyHandler(final DirtyHandler handler) {
        return addHandlerToSource(DirtyEvent.getType(), handler);
    }

    private void setScheduleBounds(final ScheduleBounds scheduleBounds) {
        if (scheduleBounds == null) {
            getView().getStartTime().setValue(null);
            getView().getEndTime().setValue(null);
        } else {
            getView().getStartTime().setValue(scheduleBounds.getStartTimeMs());
            getView().getEndTime().setValue(scheduleBounds.getEndTimeMs());
        }
    }
}
