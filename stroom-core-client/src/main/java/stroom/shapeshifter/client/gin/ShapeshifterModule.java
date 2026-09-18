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
import stroom.shapeshifter.client.presenter.CaptureEditPresenter;
import stroom.shapeshifter.client.presenter.CaptureEditPresenter.CaptureEditView;
import stroom.shapeshifter.client.presenter.CapturesPresenter;
import stroom.shapeshifter.client.presenter.DeclarationEditPresenter;
import stroom.shapeshifter.client.presenter.DeclarationEditPresenter.DeclarationEditView;
import stroom.shapeshifter.client.presenter.DeclarationsPresenter;
import stroom.shapeshifter.client.presenter.GuardAndLimitsPresenter;
import stroom.shapeshifter.client.presenter.GuardAndLimitsPresenter.GuardAndLimitsView;
import stroom.shapeshifter.client.presenter.MatchEditorPresenter;
import stroom.shapeshifter.client.presenter.MatchStructurePresenter;
import stroom.shapeshifter.client.presenter.MatchStructurePresenter.MatchStructureView;
import stroom.shapeshifter.client.presenter.MessagesPresenter;
import stroom.shapeshifter.client.presenter.PatternNodeEditPresenter;
import stroom.shapeshifter.client.presenter.PatternNodeEditPresenter.PatternNodeEditView;
import stroom.shapeshifter.client.presenter.PatternTreePresenter;
import stroom.shapeshifter.client.presenter.PatternTreePresenter.PatternTreeView;
import stroom.shapeshifter.client.presenter.PatternWorkbenchPresenter;
import stroom.shapeshifter.client.presenter.PatternWorkbenchPresenter.PatternWorkbenchView;
import stroom.shapeshifter.client.presenter.RegexTabPresenter;
import stroom.shapeshifter.client.presenter.RegexTabPresenter.RegexTabView;
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
import stroom.shapeshifter.client.presenter.TracePanePresenter;
import stroom.shapeshifter.client.presenter.TracePanePresenter.TracePaneView;
import stroom.shapeshifter.client.view.CaptureEditViewImpl;
import stroom.shapeshifter.client.view.DeclarationEditViewImpl;
import stroom.shapeshifter.client.view.GuardAndLimitsViewImpl;
import stroom.shapeshifter.client.view.MatchStructureViewImpl;
import stroom.shapeshifter.client.view.PatternNodeEditViewImpl;
import stroom.shapeshifter.client.view.PatternTreeViewImpl;
import stroom.shapeshifter.client.view.PatternWorkbenchViewImpl;
import stroom.shapeshifter.client.view.RegexTabViewImpl;
import stroom.shapeshifter.client.view.ShapeshifterDesignViewImpl;
import stroom.shapeshifter.client.view.SourceConfigViewImpl;
import stroom.shapeshifter.client.view.TemplateEditViewImpl;
import stroom.shapeshifter.client.view.TemplatePanelViewImpl;
import stroom.shapeshifter.client.view.TemplateStripViewImpl;
import stroom.shapeshifter.client.view.TracePaneViewImpl;

public class ShapeshifterModule extends PluginModule {

    @Override
    protected void configure() {
        bindPlugin(ShapeshifterPlugin.class);
        bind(ShapeshifterPresenter.class);

        // The Design tab's presenter tree (design 43 §4). Grids and the tabbed match editor use
        // the shared PagerView and LinkTabPanelView; the forms have views of their own.
        bindPresenterWidget(ShapeshifterDesignPresenter.class,
                ShapeshifterDesignView.class,
                ShapeshifterDesignViewImpl.class);
        bindPresenterWidget(TemplatePanelPresenter.class,
                TemplatePanelView.class,
                TemplatePanelViewImpl.class);
        bindPresenterWidget(TracePanePresenter.class,
                TracePaneView.class,
                TracePaneViewImpl.class);
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
        bind(MatchEditorPresenter.class);
        bindPresenterWidget(GuardAndLimitsPresenter.class,
                GuardAndLimitsView.class,
                GuardAndLimitsViewImpl.class);
        bindPresenterWidget(RegexTabPresenter.class,
                RegexTabView.class,
                RegexTabViewImpl.class);
        bindPresenterWidget(MatchStructurePresenter.class,
                MatchStructureView.class,
                MatchStructureViewImpl.class);
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
    }
}
