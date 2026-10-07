/*
 * Copyright 2026 Crown Copyright
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

package stroom.pipelinefactory.client.presenter;

import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocTabPresenter;
import stroom.entity.client.presenter.DocTabProvider;
import stroom.entity.client.presenter.LinkTabPanelView;
import stroom.entity.client.presenter.MarkdownEditPresenter;
import stroom.entity.client.presenter.MarkdownTabProvider;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.security.client.presenter.DocumentUserPermissionsTabProvider;
import stroom.widget.tab.client.presenter.TabData;
import stroom.widget.tab.client.presenter.TabDataImpl;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import javax.inject.Provider;

/// A Pipeline Factory document (design 06 §3.5): what it makes and where, the filter it gives each pipeline,
/// and what it has made so far.
public class PipelineFactoryPresenter extends DocTabPresenter<LinkTabPanelView, PipelineFactoryDoc> {

    private static final TabData SETTINGS = new TabDataImpl("Settings");
    private static final TabData FILTER = new TabDataImpl("Filter");
    private static final TabData MADE = new TabDataImpl("Made");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    @Inject
    public PipelineFactoryPresenter(
            final EventBus eventBus,
            final LinkTabPanelView view,
            final Provider<PipelineFactorySettingsPresenter> settingsPresenterProvider,
            final Provider<PipelineFactoryFilterPresenter> filterPresenterProvider,
            final Provider<PipelineFactoryMadePresenter> madePresenterProvider,
            final Provider<MarkdownEditPresenter> markdownEditPresenterProvider,
            final DocumentUserPermissionsTabProvider<PipelineFactoryDoc> documentUserPermissionsTabProvider) {
        super(eventBus, view);

        addTab(SETTINGS, new DocTabProvider<>(settingsPresenterProvider::get));
        addTab(FILTER, new DocTabProvider<>(filterPresenterProvider::get));
        addTab(MADE, new DocTabProvider<>(madePresenterProvider::get));
        addTab(DOCUMENTATION, new MarkdownTabProvider<>(eventBus, markdownEditPresenterProvider) {
            @Override
            public void onRead(final MarkdownEditPresenter presenter,
                               final DocRef docRef,
                               final PipelineFactoryDoc document,
                               final boolean readOnly) {
                presenter.setText(document.getDescription());
                presenter.setReadOnly(readOnly);
            }

            @Override
            public PipelineFactoryDoc onWrite(final MarkdownEditPresenter presenter,
                                              final PipelineFactoryDoc document) {
                return document.copy().description(presenter.getText()).build();
            }
        });
        addTab(PERMISSIONS, documentUserPermissionsTabProvider);
        selectTab(SETTINGS);
    }

    @Override
    public String getType() {
        return PipelineFactoryDoc.TYPE;
    }

    @Override
    protected TabData getDocumentationTab() {
        return DOCUMENTATION;
    }

    @Override
    protected TabData getPermissionsTab() {
        return PERMISSIONS;
    }
}
