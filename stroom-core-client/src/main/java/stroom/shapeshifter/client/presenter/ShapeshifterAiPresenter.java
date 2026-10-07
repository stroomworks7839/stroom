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

import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocTabPresenter;
import stroom.entity.client.presenter.DocTabProvider;
import stroom.entity.client.presenter.LinkTabPanelView;
import stroom.entity.client.presenter.MarkdownEditPresenter;
import stroom.entity.client.presenter.MarkdownTabProvider;
import stroom.security.client.presenter.DocumentUserPermissionsTabProvider;
import stroom.shapeshifter.client.view.ProcessHeader;
import stroom.shapeshifter.client.view.ProcessHeader.Stage;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.util.shared.PageRequest;
import stroom.widget.tab.client.presenter.TabData;
import stroom.widget.tab.client.presenter.TabDataImpl;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.PresenterWidget;

import java.util.List;
import javax.inject.Provider;

public class ShapeshifterAiPresenter extends DocTabPresenter<LinkTabPanelView, ShapeshifterAiDoc> {

    // The two stages of the process, in the order a stream meets them (design 06, A110): the scores that
    // judge what is served and what is learned, and the flow that learns. Which streams reach the
    // document is its pipelines' filters' to say.
    private static final TabData SCORE = new TabDataImpl("Score");
    private static final TabData LEARN = new TabDataImpl("Learn");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");
    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    /// The process across the top of the document (A98), with this document's figures from the
    /// Supervisor. Re-added on every tab change, since the tab panel rebuilds its toolbar then.
    private final ProcessHeader header = new ProcessHeader();
    private final RestFactory restFactory;
    private int asked;
    /// What the two reads have answered so far: streams waiting, sessions parked.
    private final Long[] figures = new Long[2];

    @Inject
    public ShapeshifterAiPresenter(
            final EventBus eventBus,
            final LinkTabPanelView view,
            final Provider<ShapeshifterAiLearnPresenter> learnPresenterProvider,
            final Provider<ShapeshifterAiScorePresenter> scorePresenterProvider,
            final Provider<MarkdownEditPresenter> markdownEditPresenterProvider,
            final DocumentUserPermissionsTabProvider<ShapeshifterAiDoc> documentUserPermissionsTabProvider,
            final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
        header.setOnStage(stage -> {
            if (stage == Stage.SCORE) {
                selectTab(SCORE);
            } else {
                selectTab(LEARN);
            }
        });

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
        selectTab(SCORE);
    }

    @Override
    public String getType() {
        return ShapeshifterAiDoc.TYPE;
    }

    /// The tab panel has just rebuilt its toolbar for the tab chosen: the process goes back beneath it,
    /// with the chosen stage highlighted, or none where the tab is not a stage.
    @Override
    protected void afterSelectTab(final PresenterWidget<?> content) {
        final TabData selected = getSelectedTab();
        header.setStage(SCORE.equals(selected)
                ? Stage.SCORE
                : LEARN.equals(selected)
                        ? Stage.LEARN
                        : null);
        getView().addToolbar(header);
    }

    /// The figures beside the stages are this document's alone: the streams waiting on a session of its,
    /// and the sessions of its parked for a person. Read with the document, and guarded
    /// against a slower earlier read arriving after a later one.
    @Override
    protected void onRead(final DocRef docRef, final ShapeshifterAiDoc document, final boolean readOnly) {
        super.onRead(docRef, document, readOnly);
        // Cleared as a set: a figure from the last read must not stand beside a new one until its own
        // reply arrives.
        figures[0] = null;
        figures[1] = null;
        header.setFigures(null, null);
        final int request = ++asked;
        final String uuid = docRef.getUuid();
        restFactory.create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.backlog(uuid, new PageRequest(0, 1)))
                .onSuccess(page -> {
                    if (request == asked) {
                        figures[0] = total(page.getPageResponse().getTotal(), page.size());
                        header.setFigures(figures[0], figures[1]);
                    }
                })
                .onFailure(error -> {
                })
                .taskMonitorFactory(this)
                .exec();
        restFactory.create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.find(new AttemptCriteria(new PageRequest(0, 1), null, uuid, null, null,
                        List.of(AttemptStatus.PARKED))))
                .onSuccess(page -> {
                    if (request == asked) {
                        figures[1] = total(page.getPageResponse().getTotal(), page.size());
                        header.setFigures(figures[0], figures[1]);
                    }
                })
                .onFailure(error -> {
                })
                .taskMonitorFactory(this)
                .exec();
    }

    private static Long total(final Long total, final int size) {
        return total != null
                ? total
                : (long) size;
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
