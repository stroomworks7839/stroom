package stroom.shapeshifter.client.view;

import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.item.client.SelectionBox;
import stroom.shapeshifter.client.presenter.ShapeshifterAiRoutePresenter.ShapeshifterAiRouteView;
import stroom.shapeshifter.client.presenter.ShapeshifterAiSettingsUiHandlers;
import stroom.shapeshifter.shared.LearningMode;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

public class ShapeshifterAiRouteViewImpl
        extends ViewWithUiHandlers<ShapeshifterAiSettingsUiHandlers>
        implements ShapeshifterAiRouteView, ReadOnlyChangeHandler {

    private final Widget widget;

    @UiField
    SelectionBox<LearningMode> learningMode;
    @UiField
    SimplePanel learningKey;
    @UiField
    ValueSpinner maxDeferralAgeMs;
    @UiField
    SimplePanel table;
    @UiField
    SimplePanel expression;

    @Inject
    public ShapeshifterAiRouteViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        learningMode.addItems(LearningMode.values());
        maxDeferralAgeMs.setMin(1);
        maxDeferralAgeMs.setMax(Long.MAX_VALUE);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public LearningMode getLearningMode() {
        return learningMode.getValue();
    }

    @Override
    public void setLearningMode(final LearningMode learningMode) {
        this.learningMode.setValue(learningMode);
    }

    @Override
    public void setLearningKeyView(final View view) {
        learningKey.setWidget(view.asWidget());
    }

    @Override
    public long getMaxDeferralAgeMs() {
        return maxDeferralAgeMs.getValue();
    }

    @Override
    public void setMaxDeferralAgeMs(final long maxDeferralAgeMs) {
        this.maxDeferralAgeMs.setValue(maxDeferralAgeMs);
    }

    @Override
    public void setTableView(final View view) {
        table.setWidget(view.asWidget());
    }

    @Override
    public void setExpressionView(final View view) {
        expression.setWidget(view.asWidget());
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        learningMode.setEnabled(!readOnly);
        maxDeferralAgeMs.setEnabled(!readOnly);
    }

    private void fireChange() {
        if (getUiHandlers() != null) {
            getUiHandlers().onChange();
        }
    }

    @UiHandler("learningMode")
    public void onLearningMode(final ValueChangeEvent<LearningMode> event) {
        fireChange();
    }

    @UiHandler("maxDeferralAgeMs")
    public void onMaxDeferralAgeMs(final ValueChangeEvent<Long> event) {
        fireChange();
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, ShapeshifterAiRouteViewImpl> {

    }
}
