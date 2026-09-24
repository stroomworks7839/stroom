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

package stroom.shapeshifter.client.gin;

import stroom.core.client.gin.PluginModule;
import stroom.monitoring.client.ShapeshifterAiSupervisorPlugin;
import stroom.shapeshifter.client.ShapeshifterAiPlugin;
import stroom.shapeshifter.client.presenter.PlanStepPresenter;
import stroom.shapeshifter.client.presenter.PlanStepPresenter.PlanStepView;
import stroom.shapeshifter.client.presenter.RoutingRulePresenter;
import stroom.shapeshifter.client.presenter.RoutingRulePresenter.RoutingRuleView;
import stroom.shapeshifter.client.presenter.ScorerSettingPresenter;
import stroom.shapeshifter.client.presenter.ScorerSettingPresenter.ScorerSettingView;
import stroom.shapeshifter.client.presenter.ShapeshifterAiLearningPresenter;
import stroom.shapeshifter.client.presenter.ShapeshifterAiLearningPresenter.ShapeshifterAiLearningView;
import stroom.shapeshifter.client.presenter.ShapeshifterAiPresenter;
import stroom.shapeshifter.client.presenter.ShapeshifterAiPromotionPresenter;
import stroom.shapeshifter.client.presenter.ShapeshifterAiPromotionPresenter.ShapeshifterAiPromotionView;
import stroom.shapeshifter.client.presenter.ShapeshifterAiRoutingPresenter;
import stroom.shapeshifter.client.presenter.ShapeshifterAiRoutingPresenter.ShapeshifterAiRoutingView;
import stroom.shapeshifter.client.presenter.ShapeshifterAiScoringPresenter;
import stroom.shapeshifter.client.presenter.ShapeshifterAiScoringPresenter.ShapeshifterAiScoringView;
import stroom.shapeshifter.client.presenter.ShapeshifterAiSettingsPresenter;
import stroom.shapeshifter.client.presenter.ShapeshifterAiSettingsPresenter.ShapeshifterAiSettingsView;
import stroom.shapeshifter.client.presenter.ShapeshifterAiStepPresenter;
import stroom.shapeshifter.client.presenter.ShapeshifterAiStepPresenter.ShapeshifterAiStepView;
import stroom.shapeshifter.client.presenter.SupervisorLedgerPresenter;
import stroom.shapeshifter.client.presenter.SupervisorListPresenter;
import stroom.shapeshifter.client.presenter.SupervisorPresenter;
import stroom.shapeshifter.client.presenter.SupervisorPresenter.SupervisorView;
import stroom.shapeshifter.client.presenter.SupervisorTurnsPresenter;
import stroom.shapeshifter.client.presenter.TransitionPresenter;
import stroom.shapeshifter.client.presenter.TransitionPresenter.TransitionView;
import stroom.shapeshifter.client.presenter.XPathAssertionPresenter;
import stroom.shapeshifter.client.presenter.XPathAssertionPresenter.XPathAssertionView;
import stroom.shapeshifter.client.view.PlanStepViewImpl;
import stroom.shapeshifter.client.view.RoutingRuleViewImpl;
import stroom.shapeshifter.client.view.ScorerSettingViewImpl;
import stroom.shapeshifter.client.view.ShapeshifterAiLearningViewImpl;
import stroom.shapeshifter.client.view.ShapeshifterAiPromotionViewImpl;
import stroom.shapeshifter.client.view.ShapeshifterAiRoutingViewImpl;
import stroom.shapeshifter.client.view.ShapeshifterAiScoringViewImpl;
import stroom.shapeshifter.client.view.ShapeshifterAiSettingsViewImpl;
import stroom.shapeshifter.client.view.ShapeshifterAiStepViewImpl;
import stroom.shapeshifter.client.view.SupervisorViewImpl;
import stroom.shapeshifter.client.view.TransitionViewImpl;
import stroom.shapeshifter.client.view.XPathAssertionViewImpl;

public class ShapeshifterAiModule extends PluginModule {

    @Override
    protected void configure() {
        bindPlugin(ShapeshifterAiPlugin.class);
        bind(ShapeshifterAiPresenter.class);
        // The Supervisor of A28 is a screen of its own, and its plugin puts it in the monitoring menu.
        bindPlugin(ShapeshifterAiSupervisorPlugin.class);
        bindPresenterWidget(SupervisorPresenter.class,
                SupervisorView.class,
                SupervisorViewImpl.class);
        bind(SupervisorListPresenter.class);
        bind(SupervisorTurnsPresenter.class);
        // The ledger beside the attempts: what is waiting for a shape to settle (A28 §11.6).
        bind(SupervisorLedgerPresenter.class);
        // The stage pane the stepper shows in place of a supervisor's code pane (A30).
        bindPresenterWidget(ShapeshifterAiStepPresenter.class,
                ShapeshifterAiStepView.class,
                ShapeshifterAiStepViewImpl.class);
        bindPresenterWidget(ShapeshifterAiSettingsPresenter.class,
                ShapeshifterAiSettingsView.class,
                ShapeshifterAiSettingsViewImpl.class);
        bindPresenterWidget(ShapeshifterAiLearningPresenter.class,
                ShapeshifterAiLearningView.class,
                ShapeshifterAiLearningViewImpl.class);
        bindPresenterWidget(ShapeshifterAiPromotionPresenter.class,
                ShapeshifterAiPromotionView.class,
                ShapeshifterAiPromotionViewImpl.class);
        bindPresenterWidget(ShapeshifterAiRoutingPresenter.class,
                ShapeshifterAiRoutingView.class,
                ShapeshifterAiRoutingViewImpl.class);
        bindPresenterWidget(RoutingRulePresenter.class,
                RoutingRuleView.class,
                RoutingRuleViewImpl.class);
        bindPresenterWidget(ShapeshifterAiScoringPresenter.class,
                ShapeshifterAiScoringView.class,
                ShapeshifterAiScoringViewImpl.class);
        bindPresenterWidget(ScorerSettingPresenter.class,
                ScorerSettingView.class,
                ScorerSettingViewImpl.class);
        bindPresenterWidget(XPathAssertionPresenter.class,
                XPathAssertionView.class,
                XPathAssertionViewImpl.class);
        // The plan editor of §12 item 24: a step as a form, and a transition as a form inside it.
        bindPresenterWidget(PlanStepPresenter.class,
                PlanStepView.class,
                PlanStepViewImpl.class);
        bindPresenterWidget(TransitionPresenter.class,
                TransitionView.class,
                TransitionViewImpl.class);
    }
}
