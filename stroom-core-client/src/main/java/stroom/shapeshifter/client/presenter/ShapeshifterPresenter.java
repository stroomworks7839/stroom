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
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DelayedUpdate;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;
import stroom.widget.tab.client.presenter.TabData;
import stroom.widget.tab.client.presenter.TabDataImpl;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

import javax.inject.Provider;

/**
 * The Shapeshifter document: Design, Source, Documentation, Permissions (design 43 §3).
 *
 * <p>Design and Source edit one model. Both tabs write {@code data}, so this presenter owns the
 * text and the {@link Project} parsed from it, and the two tabs are views of them: the Source tab
 * is the text, re-parsed on a debounce as it is typed — a syntax error leaves the previous
 * project on the Design tab under a banner saying so; the Design tab edits the project, and
 * every edit is printed back to the text. {@code onWrite} from either is the text.
 */
public class ShapeshifterPresenter extends DocTabPresenter<LinkTabPanelView, ShapeshifterDoc> {

    private static final TabData DESIGN = new TabDataImpl("Design");
    private static final TabData SOURCE = new TabDataImpl("Source");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    private final DelayedUpdate parseSource;

    private String lastRead;
    private String text;
    private Project project;
    private String sourceError;
    private ShapeshifterDesignPresenter design;
    /**
     * Run, in the document's own toolbar beside Save: present whichever tab is showing, because
     * the project it runs is the one both Design and Source edit (design 44 §5a). Assigned by
     * {@link #createToolbar()}, which the base constructor calls before this class's own
     * initialisers run - so the field must not have one.
     */
    private ButtonView runButton;
    private EditorPresenter source;
    private boolean syncing;

    @Override
    protected ButtonPanel createToolbar() {
        final ButtonPanel toolbar = super.createToolbar();
        runButton = toolbar.addButton(SvgPresets.RUN.title("Run the project over the sample data"));
        runButton.setEnabled(false);
        registerHandler(runButton.addClickHandler(event -> {
            if (design != null) {
                // Selecting the Design tab first: Run is in the document's toolbar, so it can be
                // pressed from the Source tab, and the run is only visible on Design.
                selectTab(DESIGN);
                design.runAndShow();
            }
        }));
        return toolbar;
    }

    @Inject
    public ShapeshifterPresenter(final EventBus eventBus,
                                 final LinkTabPanelView view,
                                 final Provider<ShapeshifterDesignPresenter> designPresenterProvider,
                                 final Provider<EditorPresenter> editorPresenterProvider,
                                 final Provider<MarkdownEditPresenter> markdownEditPresenterProvider,
                                 final DocumentUserPermissionsTabProvider<ShapeshifterDoc>
                                         documentUserPermissionsTabProvider) {
        super(eventBus, view);
        parseSource = new DelayedUpdate(300, this::parseSource);

        addTab(DESIGN, new AbstractTabProvider<ShapeshifterDoc, ShapeshifterDesignPresenter>(eventBus) {
            @Override
            public ShapeshifterDesignPresenter createPresenter() {
                design = designPresenterProvider.get();
                registerHandler(design.addValueChangeHandler(event -> onDesignEdit(event.getValue())));
                design.setOnCanRunChange(runButton::setEnabled);
                return design;
            }

            @Override
            public void onRead(final ShapeshifterDesignPresenter presenter,
                               final DocRef docRef,
                               final ShapeshifterDoc document,
                               final boolean readOnly) {
                readText(document.getData());
                presenter.read(project, document.getColours(), sourceError, readOnly);
            }

            @Override
            public ShapeshifterDoc onWrite(final ShapeshifterDesignPresenter presenter,
                                           final ShapeshifterDoc document) {
                return document.copy().data(text).colours(presenter.getColours()).build();
            }
        });
        addTab(SOURCE, new AbstractTabProvider<ShapeshifterDoc, EditorPresenter>(eventBus) {
            @Override
            public EditorPresenter createPresenter() {
                source = editorPresenterProvider.get();
                source.setMode(AceEditorMode.JSON);
                source.setReadOnly(isReadOnly());
                source.getFormatAction().setAvailable(!isReadOnly());
                registerHandler(source.addValueChangeHandler(event -> onSourceEdit()));
                registerHandler(source.addFormatHandler(event -> canonicalise()));
                return source;
            }

            @Override
            public void onRead(final EditorPresenter presenter,
                               final DocRef docRef,
                               final ShapeshifterDoc document,
                               final boolean readOnly) {
                readText(document.getData());
                syncing = true;
                try {
                    presenter.setText(text);
                } finally {
                    syncing = false;
                }
                presenter.setReadOnly(readOnly);
                presenter.getFormatAction().setAvailable(!readOnly);
            }

            @Override
            public ShapeshifterDoc onWrite(final EditorPresenter presenter, final ShapeshifterDoc document) {
                return document.copy().data(text).build();
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
        selectTab(DESIGN);
    }

    /**
     * The document's text as read: parse it once, whichever tab asked. A text that parses is
     * shown and kept as the printed project (design 43 §3) - the store's JSON column hands back
     * what it was given re-spaced and with its keys reordered, and the engine's form is the one
     * a reader should see; a text that does not parse is kept as it is, so it can be fixed.
     */
    private void readText(final String data) {
        final String read = data == null
                ? ""
                : data;
        if (read.equals(lastRead) && (project != null || sourceError != null)) {
            return;
        }
        lastRead = read;
        text = read;
        parse();
        if (sourceError == null) {
            text = ProjectText.print(project);
        }
    }

    private void parse() {
        if (text.trim().isEmpty()) {
            // A new document: the Design tab starts from an empty project rather than a syntax
            // error, and readText prints it, so the text is never blank - the store keeps the
            // project in a JSON column that refuses anything but JSON.
            project = ProjectText.empty(docRef == null
                    ? null
                    : docRef.getName());
            sourceError = null;
            return;
        }
        try {
            project = ProjectText.parse(text);
            sourceError = null;
        } catch (final ConfigException e) {
            sourceError = e.getMessage();
        }
    }

    private void onSourceEdit() {
        if (syncing || source == null) {
            return;
        }
        final String edited = source.getText();
        if (edited.equals(text)) {
            return;
        }
        text = edited;
        onChange();
        parseSource.update();
    }

    private void parseSource() {
        final boolean had = sourceError == null;
        parse();
        if (design != null) {
            // A text that no longer parses keeps the previous project on the Design tab, read-only,
            // under the banner; one that parses again replaces it.
            design.read(sourceError == null
                    ? project
                    : null, null, sourceError, isReadOnly());
        }
        if (had != (sourceError == null)) {
            onChange();
        }
    }

    /**
     * The Source tab's format action is canonicalise (design 43 §3): Ace has already re-indented
     * the text; the engine's own pretty form replaces it when the text reads as a project, and
     * what Ace made of it stands when it does not. The printer is the config module's, the same
     * one the engine prints with, so this needs no round trip.
     */
    private void canonicalise() {
        onSourceEdit();
        parseSource.reset();
        parseSource();
        if (sourceError != null || source == null) {
            return;
        }
        text = ProjectText.print(project);
        syncing = true;
        try {
            source.setText(text);
        } finally {
            syncing = false;
        }
        onChange();
    }

    private void onDesignEdit(final Project edited) {
        project = edited;
        sourceError = null;
        final String printed = ProjectText.print(edited);
        final boolean textChanged = !printed.equals(text);
        text = printed;
        onChange();
        if (textChanged && source != null) {
            syncing = true;
            try {
                source.setText(text);
            } finally {
                syncing = false;
            }
        }
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
