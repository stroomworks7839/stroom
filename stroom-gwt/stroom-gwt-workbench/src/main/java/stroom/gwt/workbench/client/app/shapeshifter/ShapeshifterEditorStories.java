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

package stroom.gwt.workbench.client.app.shapeshifter;

import stroom.docref.DocRef;
import stroom.document.client.editor.DocumentEditorHost;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.gwt.workbench.client.app.gin.AppScreenGinjector;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.client.widgets.tree.ExplorerFixture;
import stroom.gwt.workbench.client.widgets.tree.TreeFixtures;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.shapeshifter.shared.ShapeshifterResource;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

/// The `App/Shapeshifter/ShapeshifterEditor` stories, showing Stroom's real `ShapeshifterEditor`
/// (a Shapeshifter document's editor: Design, Source, Documentation and Permissions, with Save,
/// Save As and Run) in a [DocumentEditorHost], as `ShapeshifterPlugin` opens it, with fake REST
/// replies.
///
/// `ShapeshifterPlugin` fetches the document (`GET /shapeshifter/v1/{uuid}`), checks the user may
/// edit it and reads it into the host, which the story does as the plugin does.
///
/// | Request | Answers |
/// |---|---|
/// | `GET /shapeshifter/v1/ss-strict-kv` | the document, whose data is the `strict_kv` engine fixture |
/// | `POST /shapeshifter/v1/validate` | the project reads, with no messages |
/// | `POST /shapeshifter/v1/preview` | one run over the pasted sample: three frames, two pairs out |
/// | `POST /shapeshifter/v1/print` | a pattern tree's regex, for the match editor |
/// | `GET /shapeshifter/v1/library` | the standard library the pattern tree's refs name |
/// | `GET /shapeshifter/v1/encodings` | the encodings the source and template forms offer |
/// | `POST /permission/doc/v1/fetchDocumentUserPermissions` | the Permissions tab's grid |
/// | `POST /explorer/v2/fetchExplorerNodes` | the explorer tree ([ExplorerFixture]) |
///
/// `ReadOnly` and `LockChangeWhileOpen` are the regression tests for the Design tab's read-only
/// state, which was stuck at read-only after the component migration: its controller declared the
/// field and never assigned it, so every edit path returned early. It now watches
/// `Contexts.READ_ONLY`, which the document provides.
///
/// `SourceTab` and `SourceReadOnly` are the regression tests for the Source tab, which was a bare
/// `CodeEditorController` with no `attach(...)`: `setMode` threw on its null options the first
/// time the tab was opened, and nothing followed the document's read-only state. It is now a
/// `CodeTabController`, as every other document's text tab is.
///
/// `Default` is also the regression test for the sample page's paste box, which had the same
/// missing `attach(...)`. Its controller is built in the Design tab's constructor, so the throw
/// came before anything drew: this story could not reach its first assertion.
public final class ShapeshifterEditorStories {

    /// The name of the spy recording the documents opened.
    static final String OPEN_DOC = "openDoc";

    private static final DocRef DOC_REF =
            new DocRef(ShapeshifterDoc.TYPE, "ss-strict-kv", "Key Value Lines");

    // The strict_kv engine fixture (stroom-shapeshifter-engine's test resources): a source
    // template that wraps the records, a pair template that matches key=value, and a junk_line
    // template that warns about anything else. Three templates, so the panel has rows to show
    // and the breadcrumb has somewhere to go.
    private static final String PROJECT = """
            {
             "name": "strict_kv",
             "version": 4,
             "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "utf-8"},
             "templates": [
              {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
               "body": [
                {"text": "<records>\\n"},
                {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "kv"}},
                {"text": "</records>\\n"}]},
              {"id": "00000000-0000-0000-0000-000000000002", "name": "pair", "mode": "kv",
               "match": {"regex": {"pattern": "([a-z]+)=([^\\\\n]*)\\\\n"}},
               "body": [{"text": " <pair key=\\""}, {"value-of": {"parts": [{"capture": {"group": 1}}]}},
                        {"text": "\\">"}, {"value-of": {"parts": [{"capture": {"group": 2}}]}},
                        {"text": "</pair>\\n"}]},
              {"id": "00000000-0000-0000-0000-000000000003", "name": "junk_line", "mode": "kv",
               "consume": true,
               "match": {"regex": {"pattern": "[^\\\\n]*\\\\n?"}},
               "body": [{"emit-error": {"severity": "warning",
                         "message": {"parts": [{"text": "unrecognised line: "}, {"capture": {"group": 0}}]}}}]}
             ]
            }""";

    // The strict_kv fixture's input, as pasted text: two pairs and the comment junk_line warns about
    private static final String SAMPLE = "host=web01\\n# a comment the grammar never claimed\\nlevel=warn\\n";

    // ShapeshifterResource.fetch(). The project JSON is the document's data, so the editor parses
    // the same text whichever tab asked for it
    private static final String DOC = "{\n"
                                      + "  \"type\": \"" + ShapeshifterDoc.TYPE + "\","
                                      + " \"uuid\": \"ss-strict-kv\", \"name\": \"Key Value Lines\",\n"
                                      + "  \"description\": \"# Key value lines\\n\\nOne pair per line.\",\n"
                                      + "  \"data\": " + asJsonString(PROJECT) + ",\n"
                                      + "  \"sampleText\": \"" + SAMPLE + "\",\n"
                                      + "  \"sampleKind\": \"PASTED\"\n"
                                      + "}";

    // ShapeshifterResource.validate(): the project reads, so the Design tab shows it rather than
    // the banner
    private static final String VALIDATION = """
            {"valid": true, "messages": [], "canonical": null}""";

    // ShapeshifterResource.preview(): one run over the sample. Two pairs matched and written, and
    // the comment line warned about, as the fixture's junk_line template says
    private static final String TRACE = """
            {
              "compiled": true, "sampleRead": true,
              "input": "host=web01\\n# a comment the grammar never claimed\\nlevel=warn\\n",
              "output": "<records>\\n <pair key=\\"host\\">web01</pair>\\n <pair key=\\"level\\">warn</pair>\\n</records>\\n",
              "frames": [
                {"id": 1, "parentId": 0, "templateId": "00000000-0000-0000-0000-000000000001",
                 "templateName": "source", "matchIndex": 0, "depth": 0,
                 "inputOffset": 0, "inputLength": 56, "contentOffset": 0, "contentLength": 56},
                {"id": 2, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000002",
                 "templateName": "pair", "matchIndex": 0, "depth": 1,
                 "inputOffset": 0, "inputLength": 11, "contentOffset": 0, "contentLength": 11,
                 "content": "host=web01\\n"},
                {"id": 3, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000003",
                 "templateName": "junk_line", "matchIndex": 0, "depth": 1,
                 "inputOffset": 11, "inputLength": 35, "contentOffset": 11, "contentLength": 35,
                 "content": "# a comment the grammar never claimed\\n"}
              ],
              "captures": [
                {"frameId": 2, "index": 1, "name": "key", "contentOffset": 0, "contentLength": 4},
                {"frameId": 2, "index": 2, "name": "value", "contentOffset": 5, "contentLength": 5}
              ],
              "groups": [], "outputs": [], "attempts": [], "guards": [],
              "instructions": [], "attemptsSeen": 3, "timings": [],
              "messages": [
                {"severity": "warning", "text": "unrecognised line: # a comment the grammar never claimed",
                 "frameId": 3}
              ],
              "runNanos": 412000
            }""";

    // ShapeshifterResource.patternInfo(): the pair template's regex, its two groups
    private static final String PATTERN_INFO = """
            {
              "valid": true, "error": null,
              "groups": [
                {"index": 1, "name": null, "start": 0, "end": 7},
                {"index": 2, "name": null, "start": 8, "end": 16}
              ],
              "explain": "a lower-case key, '=', then everything to the end of the line"
            }""";

    // ShapeshifterResource.library(): what a pattern tree's ref may name
    private static final String LIBRARY = """
            {"entries": [
              {"name": "ipv4", "regex": "(?:\\\\d{1,3}\\\\.){3}\\\\d{1,3}"},
              {"name": "iso8601", "regex": "\\\\d{4}-\\\\d{2}-\\\\d{2}T[\\\\d:.]+Z?"}
            ]}""";

    // ShapeshifterResource.encodings(): the canonical labels the source and template forms offer
    private static final String ENCODINGS = """
            ["utf-8", "utf-16", "iso-8859-1", "ascii"]""";

    // ShapeshifterResource.print(): a pattern tree as the regex it means
    private static final String PRINTED = """
            {"text": "([a-z]+)=([^\\\\n]*)\\\\n"}""";

    // DocPermissionResource.fetchDocumentUserPermissions(): an owner and a group that may view
    private static final String DOC_PERMISSIONS = """
            {
              "values": [
                {"userRef": {"uuid": "u-admin", "subjectId": "admin", "displayName": "admin", "group": false,
                  "enabled": true}, "permission": "OWNER"},
                {"userRef": {"uuid": "g-analysts", "subjectId": "Analysts", "displayName": "Analysts",
                  "group": true, "enabled": true}, "permission": "VIEW"}
              ],
              "pageResponse": {"offset": 0, "length": 2, "total": 2, "exact": true}
            }""";

    private static final RestFixtures FIXTURES = TreeFixtures.explorerRoutes(
                    new ExplorerFixture(ExplorerFixture.folder("System",
                            ExplorerFixture.doc("Key Value Lines", ShapeshifterDoc.TYPE))))
            .get("/shapeshifter/v1/ss-strict-kv", RestReply.json(DOC))
            .post("/shapeshifter/v1/validate", RestReply.json(VALIDATION))
            .post("/shapeshifter/v1/preview", RestReply.json(TRACE))
            .post("/shapeshifter/v1/print", RestReply.json(PRINTED))
            .post("/shapeshifter/v1/patternInfo", RestReply.json(PATTERN_INFO))
            .get("/shapeshifter/v1/library", RestReply.json(LIBRARY))
            .get("/shapeshifter/v1/encodings", RestReply.json(ENCODINGS))
            .post("/permission/doc/v1/fetchDocumentUserPermissions", RestReply.json(DOC_PERMISSIONS))
            .build();

    private ShapeshifterEditorStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Shapeshifter/ShapeshifterEditor", ShapeshifterEditorStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // The whole editor: four tabs, Design showing, and the project's three templates
                // in the panel. Reaching the first assertion at all is the regression test for the
                // sample page's paste box, built in the Design tab's constructor
                .story("Default", context -> render(context, false))
                .withPlay(play -> {
                    for (final String label : new String[]{
                            "Design", "Source", "Documentation", "Permissions"}) {
                        play.waitFor(() -> play.expect(tab(play, label)).toBeInTheDocument());
                    }
                    // Design is the default tab, so the template panel's rows are what is shown.
                    // The root template heads its group as "document", not by its own name, which
                    // is deliberate (design 44 §5m).
                    play.waitFor(() -> play.expect(play.getByText("pair", TEMPLATE_NAME))
                            .toBeInTheDocument());
                    play.expect(play.getByText("document", TEMPLATE_NAME)).toBeInTheDocument();
                    play.expect(play.getByText("junk_line", TEMPLATE_NAME)).toBeInTheDocument();
                    // Clean on load, so Save is disabled; Run is on the document's toolbar beside it
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    play.expect(runButton(play)).toBeInTheDocument();
                    expectNoProblems(play);
                })
                // Read only (no EDIT permission): the editor retitles Save and says so, and the
                // Design tab's panes offer nothing to change. Before the migration's read-only
                // state was wired to context this was the only state the Design tab had
                .story("ReadOnly", context -> render(context, true))
                .withPlay(play -> {
                    play.findByRole("button", "Save (read only)");
                    play.expect(play.getByText("Read only", ".link-tab-panel__read-only-note")).toBeVisible();
                    // Save As stays available read only, so the document can still be copied
                    play.expect(play.getByRole("button", "Save As")).not().toHaveAttribute("aria-disabled");
                    play.waitFor(() -> play.expect(play.getByText("pair")).toBeInTheDocument());
                    expectNoProblems(play);
                })
                // The document is locked and unlocked while it is open, as DocumentPlugin does when
                // its lock changes: the host tells the editor and the Design tab follows, because it
                // watches Contexts.READ_ONLY rather than being told once by read()
                .story("LockChangeWhileOpen", context -> render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByText("pair")).toBeInTheDocument());
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");

                    play.run("lock the document", () -> readAgain(true));
                    play.expect(play.findByRole("button", "Save (read only)")).toBeInTheDocument();
                    play.expect(play.getByText("Read only", ".link-tab-panel__read-only-note")).toBeVisible();
                    // The panel is still drawn: locking narrows what may be changed, it doesn't
                    // empty the tab
                    play.expect(play.getByText("pair")).toBeInTheDocument();

                    play.run("unlock the document", () -> readAgain(false));
                    play.expect(play.findByRole("button", "Save")).toBeInTheDocument();
                    play.expect(play.queryByText("Read only", ".link-tab-panel__read-only-note")).toBeNull();
                    play.expect(play.getByText("pair")).toBeInTheDocument();
                    expectNoProblems(play);
                })

                // A template picked in the panel: the strip beside it shows that template's match,
                // what the match is for, its guard and its body. Nothing had ever drawn the
                // Design tab's inner panes before these stories
                .story("TemplateSelected", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    // The strip's rows, by their keys
                    play.waitFor(() -> play.expect(play.getByText("match", STRIP_KEY))
                            .toBeInTheDocument());
                    play.expect(play.getByText("match is", STRIP_KEY)).toBeInTheDocument();
                    play.expect(play.getByText("guard", STRIP_KEY)).toBeInTheDocument();
                    // The match chip carries the pattern, and opens the workbench
                    play.expect(play.querySelector(MATCH_CHIP)).not().toBeNull();
                    // Declarations and captures sit under the template
                    play.expect(play.getByText("Declaration")).toBeInTheDocument();
                    expectNoProblems(play);
                })
                // The body of the chosen template: one card per instruction, as the project says
                .story("TemplateBody", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByText("body", STRIP_KEY))
                            .toBeInTheDocument());
                    // The pair template writes text, then two captured values, then text
                    play.expect(play.getAllByText("value-of", CARD_KIND).count())
                            .toSatisfy("a card per value-of", count -> ((Integer) count) >= 2);
                    expectNoProblems(play);
                })
                // Run, in the document's toolbar: the preview is shown as the input, the output
                // and the variables of the run
                .story("RunShowsOutput", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.click(runButton(play));
                    // The run's output, from the preview fixture
                    play.waitFor(() -> play.expect(play.getAllByText(TextMatch.regex("web01")).count())
                            .toSatisfy("the sample's first value", count -> ((Integer) count) > 0));
                    expectNoProblems(play);
                })
                // The match chip opens the pattern workbench over the navigator's rows
                .story("PatternWorkbench", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.querySelector(MATCH_CHIP)).not().toBeNull());
                    play.click(play.querySelector(MATCH_CHIP));
                    // The workbench replaces the rows, with its own toolbar and the pattern
                    play.waitFor(() -> play.expect(play.getByRole("button",
                            TextMatch.regex("close the workbench", "i"))).toBeInTheDocument());
                    expectNoProblems(play);
                })

                // Add Template opens the template dialog; OK adds it to the project, so the
                // panel gains a row and the document goes dirty
                .story("TemplateDialogOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("pair")).toBeInTheDocument());
                    play.click(play.getByRole("button", "Add template"));
                    final Play dialog = play.within(
                            screen.findByText("New Template", StroomDom.DIALOG_TITLE)
                                    .closest(StroomDom.DIALOG));
                    dialog.type(dialog.getByRole("textbox").first(), "line");
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    // The new template is in the panel (and, because adding selects it,
                    // in the strip beside it), and Save has work to do
                    play.waitFor(() -> play.expect(play.getByText("line", TEMPLATE_NAME))
                            .toBeInTheDocument());
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    expectNoProblems(play);
                })
                // Cancel leaves the project alone: no row, and the document stays clean
                .story("TemplateDialogCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("pair")).toBeInTheDocument());
                    play.click(play.getByRole("button", "Add template"));
                    final Play dialog = play.within(
                            screen.findByText("New Template", StroomDom.DIALOG_TITLE)
                                    .closest(StroomDom.DIALOG));
                    dialog.type(dialog.getByRole("textbox").first(), "line");
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("New Template",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.queryByText("line", TEMPLATE_NAME)).toBeNull();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    expectNoProblems(play);
                })

                // A capture binds a group of the match to a name the body can read. Its dialog
                // is opened from the captures pane under the chosen template
                .story("CaptureDialogOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    selectTemplate(play, "pair");
                    play.click(play.findByRole("button", "Add capture"));
                    final Play dialog = play.within(
                            screen.findByText("New Capture", StroomDom.DIALOG_TITLE)
                                    .closest(StroomDom.DIALOG));
                    dialog.type(dialog.getByRole("textbox").first(), "host");
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    // The capture is in the template's list, so the name went where it was meant to
                    play.waitFor(() -> play.expect(play.getByText("host")).toBeInTheDocument());
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    expectNoProblems(play);
                })
                // Cancel writes nothing, so the document stays clean
                .story("CaptureDialogCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    selectTemplate(play, "pair");
                    play.click(play.findByRole("button", "Add capture"));
                    final Play dialog = play.within(
                            screen.findByText("New Capture", StroomDom.DIALOG_TITLE)
                                    .closest(StroomDom.DIALOG));
                    dialog.type(dialog.getByRole("textbox").first(), "host");
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("New Capture",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.queryByText("host")).toBeNull();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    expectNoProblems(play);
                })
                // A declaration is a name the template holds, bound by captures and read by the body
                .story("DeclarationDialogOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    selectTemplate(play, "pair");
                    play.click(play.findByRole("button", "Add declaration"));
                    final Play dialog = play.within(
                            screen.findByText("New Declaration", StroomDom.DIALOG_TITLE)
                                    .closest(StroomDom.DIALOG));
                    dialog.type(dialog.getByRole("textbox").first(), "seen");
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    // The declaration is listed under the template
                    play.waitFor(() -> play.expect(play.getByText("seen")).toBeInTheDocument());
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    expectNoProblems(play);
                })
                // Cancel leaves the template's declarations alone
                .story("DeclarationDialogCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    selectTemplate(play, "pair");
                    play.click(play.findByRole("button", "Add declaration"));
                    final Play dialog = play.within(
                            screen.findByText("New Declaration", StroomDom.DIALOG_TITLE)
                                    .closest(StroomDom.DIALOG));
                    dialog.type(dialog.getByRole("textbox").first(), "seen");
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("New Declaration",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.queryByText("seen")).toBeNull();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    expectNoProblems(play);
                })
                // The modes dialog lists the project's modes and is closed, not confirmed. It is
                // also the one the audit found was given its host on a different instance
                .story("ModesDialog", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("pair")).toBeInTheDocument());
                    play.click(play.getByRole("button", TextMatch.regex("^Modes", "i")));
                    final Play dialog = play.within(
                            screen.findByText(TextMatch.regex("mode", "i"), StroomDom.DIALOG_TITLE)
                                    .closest(StroomDom.DIALOG));
                    // The project's one declared mode is listed, which needs the host
                    play.waitFor(() -> play.expect(dialog.getAllByText("kv").count())
                            .toSatisfy("the kv mode", count -> ((Integer) count) > 0));
                    play.click(screen.getByRole("button", StroomDom.button("Close")));
                    expectNoProblems(play);
                })

                // The body's "+ instruction" line offers the kinds on a menu; picking one opens
                // the instruction dialog for it
                .story("InstructionDialogOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByText("+ instruction")).toBeInTheDocument());
                    play.click(play.getByText("+ instruction"));
                    // The kinds are a menu on the page's body, not in the canvas
                    play.click(screen.findByText("text", StroomDom.MENU_ITEM_TEXT));
                    final Play dialog = play.within(
                            screen.findByText("New text", StroomDom.DIALOG_TITLE)
                                    .closest(StroomDom.DIALOG));
                    dialog.type(dialog.getByRole("textbox").first(), "hello");
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    // A card for it joins the body, and the document has work to save
                    play.waitFor(() -> play.expect(play.getAllByText("text", CARD_KIND).count())
                            .toSatisfy("a fourth text card", count -> ((Integer) count) == 4));
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    expectNoProblems(play);
                })
                // Cancel adds no card and leaves the document clean
                .story("InstructionDialogCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByText("+ instruction")).toBeInTheDocument());
                    play.click(play.getByText("+ instruction"));
                    play.click(screen.findByText("text", StroomDom.MENU_ITEM_TEXT));
                    final Play dialog = play.within(
                            screen.findByText("New text", StroomDom.DIALOG_TITLE)
                                    .closest(StroomDom.DIALOG));
                    dialog.type(dialog.getByRole("textbox").first(), "hello");
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("New text",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    // The pair template's three text cards, as the fixture has them
                    play.expect(play.getAllByText("text", CARD_KIND).count())
                            .toSatisfy("still three text cards", count -> ((Integer) count) == 3);
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    expectNoProblems(play);
                })
                // Adding a pattern part asks for its name first: the name prompt, which several
                // places share
                .story("NamePromptOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("pair", TEMPLATE_NAME))
                            .toBeInTheDocument());
                    play.click(play.getByRole("button", TextMatch.regex("^Add a pattern part")));
                    final Play dialog = play.within(
                            screen.findByText("New Pattern Part", StroomDom.DIALOG_TITLE)
                                    .closest(StroomDom.DIALOG));
                    dialog.type(dialog.getByRole("textbox").first(), "ipv4");
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    // The part is in the panel's library section
                    play.waitFor(() -> play.expect(play.getByText("ipv4", TEMPLATE_NAME))
                            .toBeInTheDocument());
                    expectNoProblems(play);
                })
                // Cancelling the prompt adds no part
                .story("NamePromptCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("pair", TEMPLATE_NAME))
                            .toBeInTheDocument());
                    play.click(play.getByRole("button", TextMatch.regex("^Add a pattern part")));
                    final Play dialog = play.within(
                            screen.findByText("New Pattern Part", StroomDom.DIALOG_TITLE)
                                    .closest(StroomDom.DIALOG));
                    dialog.type(dialog.getByRole("textbox").first(), "ipv4");
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("New Pattern Part",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.queryByText("ipv4", TEMPLATE_NAME)).toBeNull();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    expectNoProblems(play);
                })
                // The Source tab shows the same project as text. Opening it at all is the
                // regression test for its missing attach(...), which threw on the editor's null
                // options
                .story("SourceTab", context -> render(context, false))
                .withPlay(play -> {
                    openTab(play, "Source");
                    play.waitFor(() -> play.expect(play.querySelector(ACE_INPUT)).not().toBeNull());
                    // The project's text is in the editor, not just the tab's chrome
                    // Ace renders a JSON string as one token, quotes included, so the name is
                    // matched inside its token rather than as the whole of it
                    play.waitFor(() -> play.expect(
                                    play.getAllByText(TextMatch.regex("strict_kv")).count())
                            .toSatisfy("more than none", count -> ((Integer) count) > 0));
                    expectNoProblems(play);
                })
                // Read only: the Source tab's editor follows the document, which it did not until
                // it became a CodeTabController. Format is a canonicalise here, so it goes too
                .story("SourceReadOnly", context -> render(context, true))
                .withPlay(play -> {
                    openTab(play, "Source");
                    play.waitFor(() -> play.expect(play.querySelector(ACE_INPUT)).not().toBeNull());
                    play.expect("the editor is read only",
                                    () -> play.querySelector(ACE_READ_ONLY) != null)
                            .toBe(true);
                    play.expect(play.getByText("Read only", ".link-tab-panel__read-only-note")).toBeVisible();
                    expectNoProblems(play);
                });
    }

    // Stroom's Ace editor's text area
    private static final String ACE_INPUT = ".ace_text-input";

    // Ace marks a read-only editor on its root
    private static final String ACE_READ_ONLY = ".ace_editor.ace_read-only";

// The strip labels each row with its key ("match", "match is", "guard", "body")
    // A template's name in the panel's list
    private static final String TEMPLATE_NAME = ".ss-tpl-name";

    // The kind of instruction a body card holds ("text", "value-of", ...)
    private static final String CARD_KIND = ".ss-card-kind";

    private static final String STRIP_KEY = ".ss-strip-k";

    // The chip that carries a template's match, and opens the pattern workbench
    private static final String MATCH_CHIP = ".ss-match-chip";

    // Picks a template in the panel, which is what fills the strip beside it
    private static void selectTemplate(final Play play, final String name) {
        play.waitFor(() -> play.expect(play.getByText(name)).toBeInTheDocument());
        play.click(play.getByText(name));
    }

    // The host the story shows, for plays that act as ShapeshifterPlugin does
    private static DocumentEditorHost<ShapeshifterDoc> host() {
        return ScreenHarness.shownComponent();
    }

    // Reads the shown document again, as DocumentPlugin does, read only or not
    private static void readAgain(final boolean readOnly) {
        final DocumentEditorHost<ShapeshifterDoc> host = host();
        host.setReadOnly(readOnly);
        host.read(host.getDocRef(), host.getEntity());
    }

    // Run sits on the document's toolbar, beside Save, whichever tab is showing
    private static Query runButton(final Play play) {
        return play.getByRole("button", "Run the project over the sample data");
    }

    // One of the editor's tabs.
    // Stroom's link tabs have no role="tab", so they're found by their label
    private static Query tab(final Play play, final String label) {
        return play.getByText(label, StroomDom.LINK_TAB_LABEL);
    }

    // Opens one of the editor's tabs
    private static void openTab(final Play play, final String label) {
        play.waitFor(() -> play.expect(tab(play, label)).toBeInTheDocument());
        play.click(tab(play, label));
    }

    private static void expectNoProblems(final Play play) {
        play.expect(play.spy(ScreenHarness.ALERT_SPY)).not().toHaveBeenCalled();
        play.expect(play.spy(ScreenHarness.UNHANDLED_REQUEST_SPY)).not().toHaveBeenCalled();
    }

    // The project JSON goes into the document's JSON as a string, so it is quoted and escaped once
    // more. Done here rather than by hand, so the fixture above stays the engine's fixture
    private static String asJsonString(final String text) {
        final StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                json.append('\\').append(c);
            } else if (c == '\n') {
                json.append("\\n");
            } else {
                json.append(c);
            }
        }
        return json.append('"').toString();
    }

    private static Widget render(final StoryContext context, final boolean readOnly) {
        final AppScreenGinjector injector = GWT.create(AppScreenGinjector.class);
        final ScreenHarness harness = ScreenHarness.builder(context, FIXTURES)
                .injector(injector)
                .realAlerts()
                .build();
        harness.getSecurityContext().setDocumentPermission(readOnly
                ? DocumentPermission.VIEW
                : DocumentPermission.EDIT);
        harness.fn(OPEN_DOC);
        harness.getEventBus().addHandler(OpenDocumentEvent.getType(), event ->
                harness.spy(OPEN_DOC, event.getDocRef().getUuid()));
        // Opened once Stroom has started, as ShapeshifterPlugin opens a document: load it, check
        // the user may edit it, read it into the editor's host and show the tab
        harness.afterStartUp(() -> open(harness, injector));
        return harness.asWidget();
    }

    private static void open(final ScreenHarness harness, final AppScreenGinjector injector) {
        final DocumentEditorHost<ShapeshifterDoc> controller = new DocumentEditorHost<>(
                harness.getEventBus(), injector.getShapeshifterEditor(), ShapeshifterDoc.TYPE);
        final ShapeshifterResource resource = GWT.create(ShapeshifterResource.class);
        harness.getRestFactory()
                .create(resource)
                .method(res -> res.fetch(DOC_REF.getUuid()))
                .onSuccess(doc -> harness.getSecurityContext().hasDocumentPermission(
                        DOC_REF,
                        DocumentPermission.EDIT,
                        allowUpdate -> {
                            controller.setReadOnly(!allowUpdate);
                            controller.read(DOC_REF, doc);
                            harness.show(controller);
                        },
                        error -> harness.spy(ScreenHarness.ALERT_SPY, "ERROR: " + error.getMessage()),
                        controller))
                .taskMonitorFactory(controller)
                .exec();
    }
}
