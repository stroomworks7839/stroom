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

package stroom.shapeshifter.client.view;

import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.item.client.SelectionBox;
import stroom.shapeshifter.client.presenter.ShapeshifterAiPromotionPresenter.ShapeshifterAiPromotionView;
import stroom.shapeshifter.client.presenter.ShapeshifterAiSettingsUiHandlers;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

public class ShapeshifterAiPromotionViewImpl
        extends ViewWithUiHandlers<ShapeshifterAiSettingsUiHandlers>
        implements ShapeshifterAiPromotionView, ReadOnlyChangeHandler {

    private static final int PERCENT = 100;

    private final Widget widget;

    @UiField
    SelectionBox<PromotionMode> promotionMode;
    @UiField
    ValueSpinner promotionFloor;
    @UiField
    ValueSpinner heldOutFraction;
    @UiField
    ValueSpinner minRecordsPerShape;
    @UiField
    ValueSpinner regressionCap;
    @UiField
    ValueSpinner regressionRetentionDays;

    @Inject
    public ShapeshifterAiPromotionViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        promotionMode.addItems(PromotionMode.values());
        bound(promotionFloor, 0, PERCENT);
        bound(heldOutFraction, 0, PERCENT);
        bound(minRecordsPerShape, 1, Integer.MAX_VALUE);
        bound(regressionCap, 0, Integer.MAX_VALUE);
        bound(regressionRetentionDays, 0, Integer.MAX_VALUE);
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
    public PromotionMode getPromotionMode() {
        return promotionMode.getValue();
    }

    @Override
    public void setPromotionMode(final PromotionMode promotionMode) {
        this.promotionMode.setValue(promotionMode);
    }

    @Override
    public double getPromotionFloor() {
        return fromPercent(promotionFloor);
    }

    @Override
    public void setPromotionFloor(final double promotionFloor) {
        toPercent(this.promotionFloor, promotionFloor);
    }

    @Override
    public double getHeldOutFraction() {
        return fromPercent(heldOutFraction);
    }

    @Override
    public void setHeldOutFraction(final double heldOutFraction) {
        toPercent(this.heldOutFraction, heldOutFraction);
    }

    @Override
    public int getMinRecordsPerShape() {
        return minRecordsPerShape.getIntValue();
    }

    @Override
    public void setMinRecordsPerShape(final int minRecordsPerShape) {
        this.minRecordsPerShape.setValue(minRecordsPerShape);
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
        // The spinner cannot show "unset"; zero stands for the feed's own retention.
        this.regressionRetentionDays.setValue(regressionRetentionDays == null
                ? 0
                : regressionRetentionDays);
    }

    /**
     * The fractions are shown as whole percentages because there is no double-valued spinner. A value
     * that is not a whole percentage — only possible by editing the JSON by hand — is rounded on read
     * and so shows the doc as dirty as soon as it is opened.
     */
    private static double fromPercent(final ValueSpinner spinner) {
        return spinner.getIntValue() / (double) PERCENT;
    }

    private static void toPercent(final ValueSpinner spinner, final double fraction) {
        spinner.setValue((int) Math.round(fraction * PERCENT));
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        final boolean enabled = !readOnly;
        promotionMode.setEnabled(enabled);
        promotionFloor.setEnabled(enabled);
        heldOutFraction.setEnabled(enabled);
        minRecordsPerShape.setEnabled(enabled);
        regressionCap.setEnabled(enabled);
        regressionRetentionDays.setEnabled(enabled);
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

    @UiHandler("minRecordsPerShape")
    public void onMinRecordsPerShape(final ValueChangeEvent<Long> event) {
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


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, ShapeshifterAiPromotionViewImpl> {

    }
}
