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

package stroom.shapeshifter.client.component;

import stroom.document.client.component.CodeTabController;
import stroom.document.client.component.DocumentationTabController;
import stroom.document.client.editor.DocumentEditor;
import stroom.document.client.editor.LinkTabPanel;
import stroom.security.client.component.DocumentUserPermissionsTabController;
import stroom.shapeshifter.client.model.ProjectText;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.svg.shared.SvgImage;
import stroom.util.client.DelayedUpdate;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Contexts;
import stroom.widget.tab.client.TabData;
import stroom.widget.tab.client.TabDataImpl;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

/// A Shapeshifter document's editor: its Design, Source, Documentation and Permissions tabs (see
/// [DocumentEditor]). Design and Source edit one project - the text is parsed once, whichever tab
/// asked for it, and an edit on either is printed back to the other (design 43 section 3).
public class ShapeshifterEditor extends DocumentEditor<ShapeshifterDoc> {

    private static final TabData DESIGN = new TabDataImpl("Design");
    private static final TabData SOURCE = new TabDataImpl("Source");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    private final DelayedUpdate parseSource;
    private final ToolbarButton runButton;

    private String lastRead;
    private String text;
    private Project project;
    private String sourceError;
    private ShapeshifterDesignController design;
    private CodeTabController source;
    private boolean syncing;

    /// Makes the editor, with its tabs and its Run button.
    ///
    /// @param eventBus              The application's event bus.
    /// @param view                  Its tabs.
    /// @param designProvider        Makes the Design tab.
    /// @param sourceProvider        Makes the Source tab's text editor.
    /// @param documentationProvider Makes the Documentation tab's editor.
    /// @param permissionsProvider   Makes the Permissions tab.
    @Inject
    public ShapeshifterEditor(final EventBus eventBus,
                              final LinkTabPanel view,
                              final Provider<ShapeshifterDesignController> designProvider,
                              final Provider<CodeTabController> sourceProvider,
                              final Provider<DocumentationTabController> documentationProvider,
                              final Provider<DocumentUserPermissionsTabController> permissionsProvider) {
        super(eventBus, view);
        parseSource = new DelayedUpdate(300, this::parseSource);
        onDispose(parseSource::reset);

        // Run sits in the document's toolbar beside Save: the project it runs is the one both
        // Design and Source edit, so it is present whichever tab is showing (design 44 section 5a).
        runButton = view.getToolbar().button(SvgImage.PLAY,
                "Run the project over the sample data",
                "Run",
                this::run);
        runButton.setEnabled(false);

        addTab(DESIGN,
                () -> {
                    design = designProvider.get();
                    own(design.addChangeListener(this::onDesignEdit));
                    design.setOnCanRunChange(runButton::setEnabled);
                    return design;
                },
                (content, docRef, document) -> {
                    readText(document.getData());
                    content.read(project, document.getColours(), document.getSample(),
                            document.getSampleText(), document.getSampleKind(), sourceError);
                },
                // The sample rides an ordinary save (design 44 section 5j): choosing one does not
                // make the document dirty, so what is remembered is where the author was when
                // they last saved for a reason of their own.
                (content, document) -> document.copy()
                        .data(text)
                        .colours(content.getColours())
                        .sample(content.getSampleLocation())
                        .sampleText(content.getSampleText())
                        .sampleKind(content.getSampleKind())
                        .build());

        addTab(SOURCE,
                () -> {
                    // A code tab, as every other document's text tab is: it attaches the editor to
                    // its component and follows the document's read-only state.
                    source = sourceProvider.get();
                    source.setMode(AceEditorMode.JSON);
                    source.setOnChange(this::onSourceEdit);
                    own(source.getEditor().addFormatListener(this::canonicalise));
                    return source;
                },
                (content, docRef, document) -> {
                    readText(document.getData());
                    syncing = true;
                    try {
                        content.setText(text);
                    } finally {
                        syncing = false;
                    }
                },
                (content, document) -> document.copy().data(text).build());

        addDocumentationTab(DOCUMENTATION,
                documentationProvider,
                ShapeshifterDoc::getDescription,
                (document, description) -> document.copy().description(description).build());
        addPermissionsTab(PERMISSIONS, permissionsProvider);
        setDefaultTab(DESIGN);
    }

    // Run is in the document's toolbar, so it can be pressed from the Source tab; the run is only
    // visible on Design, so that tab is selected first.
    private void run() {
        if (design != null) {
            selectTab(DESIGN);
            design.runAndShow();
        }
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
            // Reading a document is where the editor takes a project on, and so where it makes
            // sure the project has its document template (design 44 §5m). Not in the parser: a
            // parser that added a template would stop round-tripping, and would put one back
            // into the workbench's deliberately one-template sample experiment.
            project = Templates.ensureDocument(project);
            text = ProjectText.print(project);
        }
    }

    private void parse() {
        if (text.trim().isEmpty()) {
            // A new document: the Design tab starts from an empty project rather than a syntax
            // error, and readText prints it, so the text is never blank - the store keeps the
            // project in a JSON column that refuses anything but JSON.
            project = ProjectText.empty(getDocRef() == null
                    ? null
                    : getDocRef().getName());
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
                    : null, null, null, null, null, sourceError);
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

    public String getType() {
        return ShapeshifterDoc.TYPE;
    }

    protected TabData getPermissionsTab() {
        return PERMISSIONS;
    }

    protected TabData getDocumentationTab() {
        return DOCUMENTATION;
    }
}
