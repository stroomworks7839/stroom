package stroom.shapeshifter.client.view;

import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.item.client.SelectionBox;
import stroom.shapeshifter.client.presenter.ShapeshifterAiScorePresenter.ShapeshifterAiScoreView;
import stroom.shapeshifter.client.presenter.ShapeshifterAiSettingsUiHandlers;
import stroom.shapeshifter.shared.PromotionMode;
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

public class ShapeshifterAiScoreViewImpl
        extends ViewWithUiHandlers<ShapeshifterAiSettingsUiHandlers>
        implements ShapeshifterAiScoreView, ReadOnlyChangeHandler {

    private static final int PERCENT = 100;

    private final Widget widget;

    @UiField
    SelectionBox<PromotionMode> promotionMode;
    @UiField
    ValueSpinner promotionFloor;
    @UiField
    ValueSpinner heldOutFraction;
    @UiField
    ValueSpinner minRecordsToLearn;
    @UiField
    ValueSpinner regressionCap;
    @UiField
    ValueSpinner regressionRetentionDays;
    @UiField
    ValueSpinner relearnThreshold;
    @UiField
    SimplePanel table;

    @Inject
    public ShapeshifterAiScoreViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        promotionMode.addItems(PromotionMode.values());
        bound(promotionFloor, 0, PERCENT);
        bound(heldOutFraction, 0, PERCENT);
        bound(minRecordsToLearn, 1, Integer.MAX_VALUE);
        bound(regressionCap, 0, Integer.MAX_VALUE);
        bound(regressionRetentionDays, 0, Integer.MAX_VALUE);
        bound(relearnThreshold, 0, PERCENT);
    }

    private static void bound(final ValueSpinner spinner, final long min, final long max) {
        spinner.setMin(min);
        spinner.setMax(max);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setTableView(final View view) {
        table.setWidget(view.asWidget());
    }

    @Override
    public PromotionMode getPromotionMode() {
        return promotionMode.getValue();
    }

    @Override
    public void setPromotionMode(final PromotionMode promotionMode) {
        this.promotionMode.setValue(promotionMode);
    }

    @Override
    public double getPromotionFloor() {
        return promotionFloor.getIntValue() / (double) PERCENT;
    }

    @Override
    public void setPromotionFloor(final double promotionFloor) {
        this.promotionFloor.setValue(Math.round(promotionFloor * PERCENT));
    }

    @Override
    public double getHeldOutFraction() {
        return heldOutFraction.getIntValue() / (double) PERCENT;
    }

    @Override
    public void setHeldOutFraction(final double heldOutFraction) {
        this.heldOutFraction.setValue(Math.round(heldOutFraction * PERCENT));
    }

    @Override
    public int getMinRecordsToLearn() {
        return minRecordsToLearn.getIntValue();
    }

    @Override
    public void setMinRecordsToLearn(final int minRecordsToLearn) {
        this.minRecordsToLearn.setValue(minRecordsToLearn);
    }

    @Override
    public int getRegressionCap() {
        return regressionCap.getIntValue();
    }

    @Override
    public void setRegressionCap(final int regressionCap) {
        this.regressionCap.setValue(regressionCap);
    }

    @Override
    public Integer getRegressionRetentionDays() {
        final int days = regressionRetentionDays.getIntValue();
        return days == 0
                ? null
                : days;
    }

    @Override
    public void setRegressionRetentionDays(final Integer regressionRetentionDays) {
        this.regressionRetentionDays.setValue(regressionRetentionDays == null
                ? 0
                : regressionRetentionDays);
    }

    @Override
    public double getRelearnThreshold() {
        return relearnThreshold.getIntValue() / (double) PERCENT;
    }

    @Override
    public void setRelearnThreshold(final double relearnThreshold) {
        this.relearnThreshold.setValue(Math.round(relearnThreshold * PERCENT));
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        final boolean enabled = !readOnly;
        promotionMode.setEnabled(enabled);
        promotionFloor.setEnabled(enabled);
        heldOutFraction.setEnabled(enabled);
        minRecordsToLearn.setEnabled(enabled);
        regressionCap.setEnabled(enabled);
        regressionRetentionDays.setEnabled(enabled);
        relearnThreshold.setEnabled(enabled);
    }

    private void fireChange() {
        if (getUiHandlers() != null) {
            getUiHandlers().onChange();
        }
    }

    @UiHandler("promotionMode")
    public void onPromotionMode(final ValueChangeEvent<PromotionMode> event) {
        fireChange();
    }

    @UiHandler("promotionFloor")
    public void onPromotionFloor(final ValueChangeEvent<Long> event) {
        fireChange();
    }

    @UiHandler("heldOutFraction")
    public void onHeldOutFraction(final ValueChangeEvent<Long> event) {
        fireChange();
    }

    @UiHandler("minRecordsToLearn")
    public void onMinRecordsToLearn(final ValueChangeEvent<Long> event) {
        fireChange();
    }

    @UiHandler("regressionCap")
    public void onRegressionCap(final ValueChangeEvent<Long> event) {
        fireChange();
    }

    @UiHandler("regressionRetentionDays")
    public void onRegressionRetentionDays(final ValueChangeEvent<Long> event) {
        fireChange();
    }

    @UiHandler("relearnThreshold")
    public void onRelearnThreshold(final ValueChangeEvent<Long> event) {
        fireChange();
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, ShapeshifterAiScoreViewImpl> {

    }
}
