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

import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.shapeshifter.client.presenter.ShapeshifterAiPromotionPresenter.ShapeshifterAiPromotionView;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

/**
 * The Promotion tab: whether a promotion goes live or waits for a person (A25), and the gate of design
 * §7.4 — the floor a candidate must reach on the held-out sample (A15), how the sample is taken (A14),
 * and the regression set the candidate must not regress on (A18).
 */
public class ShapeshifterAiPromotionPresenter
        extends DocPresenter<ShapeshifterAiPromotionView, ShapeshifterAiDoc>
        implements ShapeshifterAiSettingsUiHandlers {

    @Inject
    public ShapeshifterAiPromotionPresenter(final EventBus eventBus,
                                               final ShapeshifterAiPromotionView view) {
        super(eventBus, view);
        view.setUiHandlers(this);
    }

    @Override
    protected void onRead(final DocRef docRef, final ShapeshifterAiDoc doc, final boolean readOnly) {
        final ShapeshifterAiPromotionView view = getView();
        view.setPromotionMode(doc.getPromotionMode());
        view.setPromotionFloor(doc.getPromotionFloor());
        view.setHeldOutFraction(doc.getHeldOutFraction());
        view.setMinRecordsPerShape(doc.getMinRecordsPerShape());
        view.setRegressionCap(doc.getRegressionCap());
        view.setRegressionRetentionDays(doc.getRegressionRetentionDays());
    }

    @Override
    protected ShapeshifterAiDoc onWrite(final ShapeshifterAiDoc doc) {
        final ShapeshifterAiPromotionView view = getView();
        return doc
                .copy()
                .promotionMode(view.getPromotionMode())
                .promotionFloor(view.getPromotionFloor())
                .heldOutFraction(view.getHeldOutFraction())
                .minRecordsPerShape(view.getMinRecordsPerShape())
                .regressionCap(view.getRegressionCap())
                .regressionRetentionDays(view.getRegressionRetentionDays())
                .build();
    }


    // --------------------------------------------------------------------------------


    public interface ShapeshifterAiPromotionView
            extends View, ReadOnlyChangeHandler, HasUiHandlers<ShapeshifterAiSettingsUiHandlers> {

        PromotionMode getPromotionMode();

        void setPromotionMode(PromotionMode promotionMode);

        /**
         * A fraction in [0, 1]; the widget shows it as a whole percentage.
         */
        double getPromotionFloor();

        void setPromotionFloor(double promotionFloor);

        /**
         * A fraction in [0, 1]; the widget shows it as a whole percentage.
         */
        double getHeldOutFraction();

        void setHeldOutFraction(double heldOutFraction);

        int getMinRecordsPerShape();

        void setMinRecordsPerShape(int minRecordsPerShape);

        int getRegressionCap();

        void setRegressionCap(int regressionCap);

        /**
         * Null when the feed's own retention applies; the widget shows that as zero.
         */
        Integer getRegressionRetentionDays();

        void setRegressionRetentionDays(Integer regressionRetentionDays);
    }
}
