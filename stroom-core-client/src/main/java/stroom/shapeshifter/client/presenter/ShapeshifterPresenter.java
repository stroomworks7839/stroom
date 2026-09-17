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

package stroom.shapeshifter.client.presenter;

import stroom.docref.DocRef;
import stroom.editor.client.presenter.EditorPresenter;
import stroom.entity.client.presenter.AbstractTabProvider;
import stroom.entity.client.presenter.DocTabPresenter;
import stroom.entity.client.presenter.LinkTabPanelView;
import stroom.entity.client.presenter.MarkdownEditPresenter;
import stroom.entity.client.presenter.MarkdownTabProvider;
import stroom.security.client.presenter.DocumentUserPermissionsTabProvider;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.util.shared.NullSafe;
import stroom.widget.tab.client.presenter.TabData;
import stroom.widget.tab.client.presenter.TabDataImpl;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

import javax.inject.Provider;

/**
 * The Shapeshifter document: its project JSON in an editor, its description, its permissions.
 *
 * <p>Phase A1 of design 43: the Source tab is the whole editing surface, and the same text is
 * what a pipeline step injects when the document is edited in stepping. The Design tab — the
 * forms over the engine's own model — arrives in A2 and edits the same {@code data}.
 */
public class ShapeshifterPresenter extends DocTabPresenter<LinkTabPanelView, ShapeshifterDoc> {

    private static final TabData SOURCE = new TabDataImpl("Source");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    @Inject
    public ShapeshifterPresenter(final EventBus eventBus,
                                 final LinkTabPanelView view,
                                 final Provider<EditorPresenter> editorPresenterProvider,
                                 final Provider<MarkdownEditPresenter> markdownEditPresenterProvider,
                                 final DocumentUserPermissionsTabProvider<ShapeshifterDoc>
                                         documentUserPermissionsTabProvider) {
        super(eventBus, view);

        addTab(SOURCE, new AbstractTabProvider<ShapeshifterDoc, EditorPresenter>(eventBus) {
            @Override
            public EditorPresenter createPresenter() {
                final EditorPresenter editorPresenter = editorPresenterProvider.get();
                editorPresenter.setMode(AceEditorMode.JSON);
                editorPresenter.setReadOnly(isReadOnly());
                editorPresenter.getFormatAction().setAvailable(!isReadOnly());
                NullSafe.consume(getEntity(), ShapeshifterDoc::getData, editorPresenter::setText);
                registerHandler(editorPresenter.addValueChangeHandler(event -> onChange()));
                registerHandler(editorPresenter.addFormatHandler(event -> onChange()));
                return editorPresenter;
            }

            @Override
            public void onRead(final EditorPresenter presenter,
                               final DocRef docRef,
                               final ShapeshifterDoc document,
                               final boolean readOnly) {
                presenter.setText(document.getData());
                presenter.setReadOnly(readOnly);
                presenter.getFormatAction().setAvailable(!readOnly);
            }

            @Override
            public ShapeshifterDoc onWrite(final EditorPresenter presenter, final ShapeshifterDoc document) {
                return document.copy().data(presenter.getText()).build();
            }
        });
        addTab(DOCUMENTATION, new MarkdownTabProvider<ShapeshifterDoc>(eventBus, markdownEditPresenterProvider) {
            @Override
            public void onRead(final MarkdownEditPresenter presenter,
                               final DocRef docRef,
                               final ShapeshifterDoc document,
                               final boolean readOnly) {
                presenter.setText(document.getDescription());
                presenter.setReadOnly(readOnly);
            }

            @Override
            public ShapeshifterDoc onWrite(final MarkdownEditPresenter presenter,
                                           final ShapeshifterDoc document) {
                return document.copy().description(presenter.getText()).build();
            }
        });
        addTab(PERMISSIONS, documentUserPermissionsTabProvider);
        selectTab(SOURCE);
    }

    @Override
    public String getType() {
        return ShapeshifterDoc.TYPE;
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
