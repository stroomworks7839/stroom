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

    // The three stages of the process, in the order a stream meets them (design 05 §10; A91): the gate
    // that routes a shape to its rule, the scores that judge what is served and what is learned, and
    // the flow that learns.
    private static final TabData ROUTE = new TabDataImpl("Route");
    private static final TabData SCORE = new TabDataImpl("Score");
    private static final TabData LEARN = new TabDataImpl("Learn");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    @Inject
    public ShapeshifterAiPresenter(
            final EventBus eventBus,
            final LinkTabPanelView view,
            final Provider<ShapeshifterAiLearnPresenter> learnPresenterProvider,
            final Provider<ShapeshifterAiScorePresenter> scorePresenterProvider,
            final Provider<ShapeshifterAiRoutePresenter> routePresenterProvider,
            final Provider<MarkdownEditPresenter> markdownEditPresenterProvider,
            final DocumentUserPermissionsTabProvider<ShapeshifterAiDoc> documentUserPermissionsTabProvider) {
        super(eventBus, view);

        addTab(ROUTE, new DocTabProvider<>(routePresenterProvider::get));
        addTab(SCORE, new DocTabProvider<>(scorePresenterProvider::get));
        addTab(LEARN, new DocTabProvider<>(learnPresenterProvider::get));
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
        selectTab(ROUTE);
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
