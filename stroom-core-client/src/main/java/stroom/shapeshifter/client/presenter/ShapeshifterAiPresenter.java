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
import stroom.entity.client.presenter.DocTabPresenter;
import stroom.entity.client.presenter.DocTabProvider;
import stroom.entity.client.presenter.LinkTabPanelView;
import stroom.entity.client.presenter.MarkdownEditPresenter;
import stroom.entity.client.presenter.MarkdownTabProvider;
import stroom.security.client.presenter.DocumentUserPermissionsTabProvider;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.widget.tab.client.presenter.TabData;
import stroom.widget.tab.client.presenter.TabDataImpl;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import javax.inject.Provider;

public class ShapeshifterAiPresenter extends DocTabPresenter<LinkTabPanelView, ShapeshifterAiDoc> {

    private static final TabData SETTINGS = new TabDataImpl("Settings");
    private static final TabData LEARNING = new TabDataImpl("Learning");
    private static final TabData PROMOTION = new TabDataImpl("Promotion");
    private static final TabData SCORING = new TabDataImpl("Scoring");
    private static final TabData ROUTING = new TabDataImpl("Routing");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    @Inject
    public ShapeshifterAiPresenter(
            final EventBus eventBus,
            final LinkTabPanelView view,
            final Provider<ShapeshifterAiSettingsPresenter> settingsPresenterProvider,
            final Provider<ShapeshifterAiLearningPresenter> learningPresenterProvider,
            final Provider<ShapeshifterAiScoringPresenter> scoringPresenterProvider,
            final Provider<ShapeshifterAiPromotionPresenter> promotionPresenterProvider,
            final Provider<ShapeshifterAiRoutingPresenter> routingPresenterProvider,
            final Provider<MarkdownEditPresenter> markdownEditPresenterProvider,
            final DocumentUserPermissionsTabProvider<ShapeshifterAiDoc> documentUserPermissionsTabProvider) {
        super(eventBus, view);

        addTab(SETTINGS, new DocTabProvider<>(settingsPresenterProvider::get));
        addTab(LEARNING, new DocTabProvider<>(learningPresenterProvider::get));
        addTab(SCORING, new DocTabProvider<>(scoringPresenterProvider::get));
        addTab(PROMOTION, new DocTabProvider<>(promotionPresenterProvider::get));
        addTab(ROUTING, new DocTabProvider<>(routingPresenterProvider::get));
        addTab(DOCUMENTATION, new MarkdownTabProvider<>(eventBus, markdownEditPresenterProvider) {
            @Override
            public void onRead(final MarkdownEditPresenter presenter,
                               final DocRef docRef,
                               final ShapeshifterAiDoc document,
                               final boolean readOnly) {
                presenter.setText(document.getDescription());
                presenter.setReadOnly(readOnly);
            }

            @Override
            public ShapeshifterAiDoc onWrite(final MarkdownEditPresenter presenter,
                                                final ShapeshifterAiDoc document) {
                return document.copy().description(presenter.getText()).build();
            }
        });
        addTab(PERMISSIONS, documentUserPermissionsTabProvider);
        selectTab(SETTINGS);
    }

    @Override
    public String getType() {
        return ShapeshifterAiDoc.TYPE;
    }

    @Override
    protected TabData getPermissionsTab() {
        return PERMISSIONS;
    }

    @Override
    protected TabData getDocumentationTab() {
        return DOCUMENTATION;
    }
}
