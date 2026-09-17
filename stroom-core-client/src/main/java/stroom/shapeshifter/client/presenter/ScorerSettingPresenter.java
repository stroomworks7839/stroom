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

import stroom.shapeshifter.client.presenter.ScorerSettingPresenter.ScorerSettingView;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ErrorLoadParameters;
import stroom.shapeshifter.shared.EventClassificationParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.util.shared.Severity;

import com.google.gwt.event.logical.shared.HasValueChangeHandlers;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;
import java.util.Optional;

/**
 * The edit dialog for one scorer setting: type, weight, threshold, gate, and the panel of parameters
 * that belongs to the type (§8.4). Choosing a type shows its panel with that type's defaults, or nothing
 * for a scorer that takes none.
 */
public class ScorerSettingPresenter extends MyPresenterWidget<ScorerSettingView> {

    private final XPathAssertionListPresenter assertionsPresenter;

    @Inject
    public ScorerSettingPresenter(final EventBus eventBus,
                                  final ScorerSettingView view,
                                  final XPathAssertionListPresenter assertionsPresenter) {
        super(eventBus, view);
        this.assertionsPresenter = assertionsPresenter;
        view.setAssertionsView(assertionsPresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        // The popup binds this presenter when it shows it; the assertion list is not in a slot, so it
        // is bound here or its buttons do nothing.
        assertionsPresenter.bind();
        registerHandler(getView().getTypeBox().addValueChangeHandler(event -> {
            getView().showParameters(event.getValue());
            readParameters(ScorerParameters.defaultsFor(event.getValue()));
        }));
    }

    @Override
    protected void onUnbind() {
        assertionsPresenter.unbind();
        super.onUnbind();
    }

    public void read(final ScorerSetting setting) {
        getView().setType(setting.getType());
        getView().setWeight(setting.getWeight());
        getView().setThreshold(setting.getThreshold());
        getView().setGate(setting.isGate());
        getView().showParameters(setting.getType());
        readParameters(setting.getParameters());
    }

    private void readParameters(final ScorerParameters parameters) {
        // GWT compiles instanceof patterns but not pattern switches.
        if (parameters instanceof final YieldParameters yield) {
            getView().setExpectedRatio(yield.getExpectedRatio());
            getView().setBasis(yield.getBasis());
        } else if (parameters instanceof final SchemaConformanceParameters schema) {
            getView().setSchemaGroup(schema.getSchemaGroup());
        } else if (parameters instanceof final ExtractionQualityParameters quality) {
            getView().setAllowUnknownEventDetail(quality.isAllowUnknownEventDetail());
            getView().setRequiredFields(quality.getRequiredFields());
        } else if (parameters instanceof final BusinessRulesParameters rules) {
            assertionsPresenter.read(rules.getAssertions(), false);
            getView().setIncludeTransformMessages(rules.isIncludeTransformMessages());
        } else if (parameters instanceof final ErrorLoadParameters errors) {
            getView().setMinimumSeverity(errors.getMinimumSeverity());
        } else if (parameters instanceof final EventClassificationParameters classes) {
            getView().setRecognisedTypes(classes.getRecognisedTypes());
        }
    }

    private ScorerParameters writeParameters(final ScorerType type) {
        return switch (type) {
            case COMPILE, INPUT_COVERAGE -> null;
            case YIELD -> new YieldParameters(getView().getExpectedRatio(), getView().getBasis());
            case SCHEMA_CONFORMANCE -> new SchemaConformanceParameters(getView().getSchemaGroup());
            case EXTRACTION_QUALITY -> new ExtractionQualityParameters(
                    getView().isAllowUnknownEventDetail(), getView().getRequiredFields());
            case BUSINESS_RULES -> new BusinessRulesParameters(
                    assertionsPresenter.write(), getView().isIncludeTransformMessages());
            case ERROR_LOAD -> new ErrorLoadParameters(getView().getMinimumSeverity());
            case EVENT_CLASSIFICATION -> new EventClassificationParameters(getView().getRecognisedTypes());
        };
    }

    /**
     * @return Why the dialog cannot be accepted as it stands, or empty if it can.
     */
    public Optional<String> validate() {
        final ScorerType type = getView().getType();
        if (type == null) {
            return Optional.of("Choose a scorer.");
        }
        final Double weight = getView().getWeight();
        if (weight == null || weight < 0) {
            return Optional.of("The weight must be a number of zero or more.");
        }
        final Double threshold = getView().getThreshold();
        if (threshold == null || threshold < 0 || threshold > 1) {
            return Optional.of("The threshold must be a number from 0 to 1.");
        }
        if (type == ScorerType.YIELD) {
            final Double ratio = getView().getExpectedRatio();
            if (ratio == null || ratio <= 0) {
                return Optional.of("The expected ratio must be a number above zero.");
            }
        }
        return Optional.empty();
    }

    public ScorerSetting write() {
        final ScorerType type = getView().getType();
        return new ScorerSetting(
                type,
                getView().getWeight(),
                getView().getThreshold(),
                getView().isGate(),
                writeParameters(type));
    }


    // --------------------------------------------------------------------------------


    public interface ScorerSettingView extends View {

        HasValueChangeHandlers<ScorerType> getTypeBox();

        ScorerType getType();

        void setType(ScorerType type);

        /**
         * @return Null when the box does not hold a number.
         */
        Double getWeight();

        void setWeight(double weight);

        /**
         * @return Null when the box does not hold a number.
         */
        Double getThreshold();

        void setThreshold(double threshold);

        boolean isGate();

        void setGate(boolean gate);

        /**
         * Show the parameter panel for the type; an empty panel for a type that takes none.
         */
        void showParameters(ScorerType type);

        Double getExpectedRatio();

        void setExpectedRatio(double expectedRatio);

        YieldBasis getBasis();

        void setBasis(YieldBasis basis);

        String getSchemaGroup();

        void setSchemaGroup(String schemaGroup);

        boolean isAllowUnknownEventDetail();

        void setAllowUnknownEventDetail(boolean allowUnknownEventDetail);

        /**
         * One XPath per line in the widget; never null, blank lines dropped.
         */
        List<String> getRequiredFields();

        void setRequiredFields(List<String> requiredFields);

        void setAssertionsView(View view);

        boolean isIncludeTransformMessages();

        void setIncludeTransformMessages(boolean includeTransformMessages);

        Severity getMinimumSeverity();

        void setMinimumSeverity(Severity minimumSeverity);

        /**
         * One type id per line in the widget; never null, blank lines dropped.
         */
        List<String> getRecognisedTypes();

        void setRecognisedTypes(List<String> recognisedTypes);
    }
}
