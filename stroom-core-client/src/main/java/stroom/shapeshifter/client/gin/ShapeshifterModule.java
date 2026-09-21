/*
 * Copyright 2016 Crown Copyright
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
import stroom.shapeshifter.client.ShapeshifterPlugin;
import stroom.shapeshifter.client.presenter.BodyPresenter;
import stroom.shapeshifter.client.presenter.BodyPresenter.BodyView;
import stroom.shapeshifter.client.presenter.BreadcrumbPresenter;
import stroom.shapeshifter.client.presenter.BreadcrumbPresenter.BreadcrumbView;
import stroom.shapeshifter.client.presenter.CaptureEditPresenter;
import stroom.shapeshifter.client.presenter.CaptureEditPresenter.CaptureEditView;
import stroom.shapeshifter.client.presenter.CapturesPresenter;
import stroom.shapeshifter.client.presenter.ConditionEditPresenter;
import stroom.shapeshifter.client.presenter.ConditionEditPresenter.ConditionEditView;
import stroom.shapeshifter.client.presenter.ContentPanePresenter;
import stroom.shapeshifter.client.presenter.ContentPanePresenter.ContentPaneView;
import stroom.shapeshifter.client.presenter.DeclarationEditPresenter;
import stroom.shapeshifter.client.presenter.DeclarationEditPresenter.DeclarationEditView;
import stroom.shapeshifter.client.presenter.DeclarationsPresenter;
import stroom.shapeshifter.client.presenter.DelimiterPresenter;
import stroom.shapeshifter.client.presenter.DelimiterPresenter.DelimiterView;
import stroom.shapeshifter.client.presenter.GuardAndLimitsPresenter;
import stroom.shapeshifter.client.presenter.GuardAndLimitsPresenter.GuardAndLimitsView;
import stroom.shapeshifter.client.presenter.InstructionEditPresenter;
import stroom.shapeshifter.client.presenter.InstructionEditPresenter.InstructionEditView;
import stroom.shapeshifter.client.presenter.MatchEditorPresenter;
import stroom.shapeshifter.client.presenter.MatchEditorPresenter.MatchEditorView;
import stroom.shapeshifter.client.presenter.MessagesPresenter;
import stroom.shapeshifter.client.presenter.ModeEditorPresenter;
import stroom.shapeshifter.client.presenter.ModeNamePresenter;
import stroom.shapeshifter.client.presenter.ModeNamePresenter.ModeNameView;
import stroom.shapeshifter.client.presenter.OutputPanePresenter;
import stroom.shapeshifter.client.presenter.OutputPanePresenter.OutputPaneView;
import stroom.shapeshifter.client.presenter.PartEditPresenter;
import stroom.shapeshifter.client.presenter.PartEditPresenter.PartEditView;
import stroom.shapeshifter.client.presenter.PartsPresenter;
import stroom.shapeshifter.client.presenter.PartsPresenter.PartsView;
import stroom.shapeshifter.client.presenter.PatternNodeEditPresenter;
import stroom.shapeshifter.client.presenter.PatternNodeEditPresenter.PatternNodeEditView;
import stroom.shapeshifter.client.presenter.PatternTreePresenter;
import stroom.shapeshifter.client.presenter.PatternTreePresenter.PatternTreeView;
import stroom.shapeshifter.client.presenter.PatternWorkbenchPresenter;
import stroom.shapeshifter.client.presenter.PatternWorkbenchPresenter.PatternWorkbenchView;
import stroom.shapeshifter.client.presenter.RegexPresenter;
import stroom.shapeshifter.client.presenter.RegexPresenter.RegexView;
import stroom.shapeshifter.client.presenter.SamplePresenter;
import stroom.shapeshifter.client.presenter.SamplePresenter.SampleView;
import stroom.shapeshifter.client.presenter.ShapeshifterDesignPresenter;
import stroom.shapeshifter.client.presenter.ShapeshifterDesignPresenter.ShapeshifterDesignView;
import stroom.shapeshifter.client.presenter.ShapeshifterPresenter;
import stroom.shapeshifter.client.presenter.SourceConfigPresenter;
import stroom.shapeshifter.client.presenter.SourceConfigPresenter.SourceConfigView;
import stroom.shapeshifter.client.presenter.TemplateEditPresenter;
import stroom.shapeshifter.client.presenter.TemplateEditPresenter.TemplateEditView;
import stroom.shapeshifter.client.presenter.TemplatePanelPresenter;
import stroom.shapeshifter.client.presenter.TemplatePanelPresenter.TemplatePanelView;
import stroom.shapeshifter.client.presenter.TemplateStripPresenter;
import stroom.shapeshifter.client.presenter.TemplateStripPresenter.TemplateStripView;
import stroom.shapeshifter.client.presenter.VariablesPanePresenter;
import stroom.shapeshifter.client.presenter.VariablesPanePresenter.VariablesPaneView;
import stroom.shapeshifter.client.view.BodyViewImpl;
import stroom.shapeshifter.client.view.BreadcrumbViewImpl;
import stroom.shapeshifter.client.view.CaptureEditViewImpl;
import stroom.shapeshifter.client.view.ConditionEditViewImpl;
import stroom.shapeshifter.client.view.ContentPaneViewImpl;
import stroom.shapeshifter.client.view.DeclarationEditViewImpl;
import stroom.shapeshifter.client.view.DelimiterViewImpl;
import stroom.shapeshifter.client.view.GuardAndLimitsViewImpl;
import stroom.shapeshifter.client.view.InstructionEditViewImpl;
import stroom.shapeshifter.client.view.MatchEditorViewImpl;
import stroom.shapeshifter.client.view.ModeNameViewImpl;
import stroom.shapeshifter.client.view.OutputPaneViewImpl;
import stroom.shapeshifter.client.view.PartEditViewImpl;
import stroom.shapeshifter.client.view.PartsViewImpl;
import stroom.shapeshifter.client.view.PatternNodeEditViewImpl;
import stroom.shapeshifter.client.view.PatternTreeViewImpl;
import stroom.shapeshifter.client.view.PatternWorkbenchViewImpl;
import stroom.shapeshifter.client.view.RegexViewImpl;
import stroom.shapeshifter.client.view.SampleViewImpl;
import stroom.shapeshifter.client.view.ShapeshifterDesignViewImpl;
import stroom.shapeshifter.client.view.SourceConfigViewImpl;
import stroom.shapeshifter.client.view.TemplateEditViewImpl;
import stroom.shapeshifter.client.view.TemplatePanelViewImpl;
import stroom.shapeshifter.client.view.TemplateStripViewImpl;
import stroom.shapeshifter.client.view.VariablesPaneViewImpl;

public class ShapeshifterModule extends PluginModule {

    @Override
    protected void configure() {
        bindPlugin(ShapeshifterPlugin.class);
        bind(ShapeshifterPresenter.class);

        // The Design tab's presenter tree (design 43 §4). Grids use the shared PagerView; the
        // forms have views of their own.
        bindPresenterWidget(ShapeshifterDesignPresenter.class,
                ShapeshifterDesignView.class,
                ShapeshifterDesignViewImpl.class);
        bindPresenterWidget(TemplatePanelPresenter.class,
                TemplatePanelView.class,
                TemplatePanelViewImpl.class);
        bindPresenterWidget(BreadcrumbPresenter.class,
                BreadcrumbView.class,
                BreadcrumbViewImpl.class);
        bindPresenterWidget(ContentPanePresenter.class,
                ContentPaneView.class,
                ContentPaneViewImpl.class);
        bindPresenterWidget(VariablesPanePresenter.class,
                VariablesPaneView.class,
                VariablesPaneViewImpl.class);
        bindPresenterWidget(OutputPanePresenter.class,
                OutputPaneView.class,
                OutputPaneViewImpl.class);
        bindPresenterWidget(PatternWorkbenchPresenter.class,
                PatternWorkbenchView.class,
                PatternWorkbenchViewImpl.class);
        bindPresenterWidget(TemplateEditPresenter.class,
                TemplateEditView.class,
                TemplateEditViewImpl.class);
        bindPresenterWidget(SourceConfigPresenter.class,
                SourceConfigView.class,
                SourceConfigViewImpl.class);
        bindPresenterWidget(TemplateStripPresenter.class,
                TemplateStripView.class,
                TemplateStripViewImpl.class);
        bindPresenterWidget(MatchEditorPresenter.class,
                MatchEditorView.class,
                MatchEditorViewImpl.class);
        bindPresenterWidget(GuardAndLimitsPresenter.class,
                GuardAndLimitsView.class,
                GuardAndLimitsViewImpl.class);
        bindPresenterWidget(RegexPresenter.class,
                RegexView.class,
                RegexViewImpl.class);
        bindPresenterWidget(SamplePresenter.class,
                SampleView.class,
                SampleViewImpl.class);
        bindPresenterWidget(DelimiterPresenter.class,
                DelimiterView.class,
                DelimiterViewImpl.class);
        bindPresenterWidget(PartsPresenter.class,
                PartsView.class,
                PartsViewImpl.class);
        bindPresenterWidget(PartEditPresenter.class,
                PartEditView.class,
                PartEditViewImpl.class);
        bindPresenterWidget(PatternTreePresenter.class,
                PatternTreeView.class,
                PatternTreeViewImpl.class);
        bindPresenterWidget(PatternNodeEditPresenter.class,
                PatternNodeEditView.class,
                PatternNodeEditViewImpl.class);
        bind(DeclarationsPresenter.class);
        bindPresenterWidget(DeclarationEditPresenter.class,
                DeclarationEditView.class,
                DeclarationEditViewImpl.class);
        bind(CapturesPresenter.class);
        bindPresenterWidget(CaptureEditPresenter.class,
                CaptureEditView.class,
                CaptureEditViewImpl.class);
        bind(MessagesPresenter.class);
        bindPresenterWidget(BodyPresenter.class,
                BodyView.class,
                BodyViewImpl.class);
        bindPresenterWidget(InstructionEditPresenter.class,
                InstructionEditView.class,
                InstructionEditViewImpl.class);
        bindPresenterWidget(ConditionEditPresenter.class,
                ConditionEditView.class,
                ConditionEditViewImpl.class);
        bind(ModeEditorPresenter.class);
        bindPresenterWidget(ModeNamePresenter.class,
                ModeNameView.class,
                ModeNameViewImpl.class);
    }
}
