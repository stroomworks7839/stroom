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
import stroom.gwt.workbench.client.app.rest.JsonValues;
import stroom.gwt.workbench.client.app.rest.RecordedRequest;
import stroom.gwt.workbench.client.app.rest.RequestMatcher;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.client.widgets.tree.ExplorerFixture;
import stroom.gwt.workbench.client.widgets.tree.TreeFixtures;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.Spy;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.shapeshifter.shared.ShapeshifterResource;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

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
    // The keystone compares what the editor built with this (design 46 §3). The engine's own
    // copy of the same configuration is guarded against drift from the other side of the module
    // boundary, by TestStoryFixtureCopy, which hashes the fixture and names this constant when
    // it trips — the two modules cannot see each other, so each guards what it owns (§3.5).
    static final String PROJECT = """
            {
             "name": "strict_kv",
             "version": 5,
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

    private static final DocRef COMPOSITE_REF =
            new DocRef(ShapeshifterDoc.TYPE, "ss-composite", "Composite Kinds");

    // A second project, for the three dialogs the strict_kv fixture cannot reach: its templates
    // match with a framed sequence (PartEdit), a pattern tree (PatternNodeEdit), and one writes
    // its body through a choose, whose branch condition is ConditionEdit's. Kept apart from
    // strict_kv so that the twenty stories built on that fixture still show the same screens.
    private static final String COMPOSITE = """
            {
             "name": "composite",
             "version": 4,
             "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "utf-8"},
             "templates": [
              {"id": "00000000-0000-0000-0000-000000000011", "name": "source", "match": "source",
               "body": [
                {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "frames"}}]},
              {"id": "00000000-0000-0000-0000-000000000012", "name": "framed", "mode": "frames",
               "match": {"parts": [{"take": 4}, {"seek": 2}]},
               "body": [{"text": "<frame/>\\n"}]},
              {"id": "00000000-0000-0000-0000-000000000013", "name": "tree", "mode": "frames",
               "match": {"pattern": {"sequence": [{"tag": "<"}, {"take_until": ">"}]}},
               "body": [{"text": "<node/>\\n"}]},
              {"id": "00000000-0000-0000-0000-000000000014", "name": "branch", "mode": "frames",
               "declarations": [{"name": "line", "type": "scalar"}],
               "match": {"regex": {"pattern": "[^\\\\n]*\\\\n"}},
               "body": [
                {"variable": {"name": "line",
                  "body": [{"value-of": {"parts": [{"capture": {"group": 0}}]}}]}},
                {"translate": {"select": [{"parts": [{"capture": {"var_id": "line", "group": 0}}]}],
                  "from": ["&", "<", "\\n"], "to": ["&amp;", "&lt;", " "], "name": "escaped"}},
                {"choose": {
                  "when": [
                   {"test": {"or": [
                     {"contains": {"select": {"parts": [{"capture": {"var_id": "line", "group": 0}}]},
                                   "substring": "x"}},
                     {"not": {"contains": {"select": {"parts": [{"capture": {"var_id": "line", "group": 0}}]},
                                           "substring": "y"}}}]},
                    "body": [{"text": "<x/>\\n"}]}],
                  "otherwise": [{"text": "<other/>\\n"}]}}]}
             ]
            }""";

    private static final String COMPOSITE_DOC = "{\n"
                                                + "  \"type\": \"" + ShapeshifterDoc.TYPE + "\","
                                                + " \"uuid\": \"ss-composite\", \"name\": \"Composite Kinds\",\n"
                                                + "  \"description\": \"# Composite kinds\",\n"
                                                + "  \"data\": " + asJsonString(COMPOSITE) + ",\n"
                                                + "  \"sampleText\": \"abcd<tag>x\\\\n\",\n"
                                                + "  \"sampleKind\": \"PASTED\"\n"
                                                + "}";

    // MetaResource.findMetaRow(): the unlocked streams the sample page lists as the other place a
    // sample can come from. One row is enough: the page is about choosing between the two kinds,
    // not about the list, which is stepping's own and has its own stories.
    private static final String META_ROWS = """
            {
              "values": [
                {"meta": {"id": 42, "feedName": "TEST_FEED", "typeName": "Raw Events",
                  "status": "UNLOCKED", "createMs": 1700000000000}}
              ],
              "pageResponse": {"offset": 0, "length": 1, "total": 1, "exact": true}
            }""";

    // Design 46's rung 1 walkthrough starts from a document the author has just created, so the
    // fixture is a document with no project in it at all. ShapeshifterEditor.parse() reads blank
    // text as ProjectText.empty(), which is Templates.ensureDocument of a nameless project — so
    // what arrives on screen is one template named "document", matching the source, with an
    // apply-templates that selects nothing (design 44 §5m). The author extends that; they do not
    // make it, which is the correction design 46 §5.3 records.
    private static final DocRef BLANK_REF =
            new DocRef(ShapeshifterDoc.TYPE, "ss-blank", "New Project");

    private static final String BLANK_DOC = "{\n"
                                            + "  \"type\": \"" + ShapeshifterDoc.TYPE + "\","
                                            + " \"uuid\": \"ss-blank\", \"name\": \"New Project\",\n"
                                            + "  \"description\": null,\n"
                                            + "  \"data\": \"\",\n"
                                            + "  \"sampleText\": null,\n"
                                            + "  \"sampleKind\": \"PASTED\"\n"
                                            + "}";

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
            .post("/meta/v1/find", RestReply.json(META_ROWS))
            .build();

    private static final RestFixtures COMPOSITE_FIXTURES = TreeFixtures.explorerRoutes(
                    new ExplorerFixture(ExplorerFixture.folder("System",
                            ExplorerFixture.doc("Composite Kinds", ShapeshifterDoc.TYPE))))
            .get("/shapeshifter/v1/ss-composite", RestReply.json(COMPOSITE_DOC))
            .post("/shapeshifter/v1/validate", RestReply.json(VALIDATION))
            .post("/shapeshifter/v1/preview", RestReply.json(TRACE))
            .post("/shapeshifter/v1/print", RestReply.json(PRINTED))
            .post("/shapeshifter/v1/patternInfo", RestReply.json(PATTERN_INFO))
            .get("/shapeshifter/v1/library", RestReply.json(LIBRARY))
            .get("/shapeshifter/v1/encodings", RestReply.json(ENCODINGS))
            .post("/permission/doc/v1/fetchDocumentUserPermissions", RestReply.json(DOC_PERMISSIONS))
            .build();

    // The same routes as FIXTURES, over a document with no project in it. There is no PUT route:
    // Save cannot be driven from a story (the AuthorRungOne story says why), so a route for it

    // The same routes as FIXTURES, over a document with no project in it. There is no PUT route:
    // Save cannot be driven from a story (the AuthorRungOne story says why), so a route for it
    // would be a fixture nothing ever reaches.
    private static final RestFixtures BLANK_FIXTURES = TreeFixtures.explorerRoutes(
                    new ExplorerFixture(ExplorerFixture.folder("System",
                            ExplorerFixture.doc("New Project", ShapeshifterDoc.TYPE))))
            .get("/shapeshifter/v1/ss-blank", RestReply.json(BLANK_DOC))
            .post("/shapeshifter/v1/validate", RestReply.json(VALIDATION))
            .post("/shapeshifter/v1/preview", RestReply.json(TRACE))
            .post("/shapeshifter/v1/print", RestReply.json(PRINTED))
            .post("/shapeshifter/v1/patternInfo", RestReply.json(PATTERN_INFO))
            .get("/shapeshifter/v1/library", RestReply.json(LIBRARY))
            .get("/shapeshifter/v1/encodings", RestReply.json(ENCODINGS))
            .post("/permission/doc/v1/fetchDocumentUserPermissions", RestReply.json(DOC_PERMISSIONS))
            .post("/meta/v1/find", RestReply.json(META_ROWS))
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
                    play.waitFor(() -> play.expect(play.getByText("pair", TEMPLATE_NAME)).toBeInTheDocument());
                    expectNoProblems(play);
                })
                // The document is locked and unlocked while it is open, as DocumentPlugin does when
                // its lock changes: the host tells the editor and the Design tab follows, because it
                // watches Contexts.READ_ONLY rather than being told once by read()
                .story("LockChangeWhileOpen", context -> render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByText("pair", TEMPLATE_NAME)).toBeInTheDocument());
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");

                    // A pane below follows too: the body offers no "+ instruction" read only
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByText("+ instruction")).toBeInTheDocument());

                    play.run("lock the document", () -> readAgain(true));
                    play.expect(play.findByRole("button", "Save (read only)")).toBeInTheDocument();
                    play.expect(play.getByText("Read only", ".link-tab-panel__read-only-note")).toBeVisible();
                    play.waitFor(() -> play.expect(play.queryByText("+ instruction")).toBeNull());
                    // The panel is still drawn: locking narrows what may be changed, it doesn't
                    // empty the tab
                    play.expect(play.getByText("pair", TEMPLATE_NAME)).toBeInTheDocument();

                    play.run("unlock the document", () -> readAgain(false));
                    play.expect(play.findByRole("button", "Save")).toBeInTheDocument();
                    play.expect(play.queryByText("Read only", ".link-tab-panel__read-only-note")).toBeNull();
                    play.waitFor(() -> play.expect(play.getByText("+ instruction")).toBeInTheDocument());
                    play.expect(play.getByText("pair", TEMPLATE_NAME)).toBeInTheDocument();
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
                // A lock change on its own, with no re-read: the panes are told through the
                // read-only context, not by being filled again (UI-GUIDE.md: a controller calls
                // setReadOnly from the context it watches)
                .story("LockChangeWithoutReread", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByText("+ instruction")).toBeInTheDocument());
                    play.run("lock the document, without reading it again", () ->
                            host().setReadOnly(true));
                    // The body pane below the document follows the lock on its own
                    play.waitFor(() -> play.expect(play.queryByText("+ instruction")).toBeNull());
                    play.run("unlock the document, without reading it again", () ->
                            host().setReadOnly(false));
                    play.waitFor(() -> play.expect(play.getByText("+ instruction")).toBeInTheDocument());
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
                    play.waitFor(() -> play.expect(play.getByText("pair", TEMPLATE_NAME)).toBeInTheDocument());
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
                    play.waitFor(() -> play.expect(play.getByText("pair", TEMPLATE_NAME)).toBeInTheDocument());
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
                    play.waitFor(() -> play.expect(play.getByText("pair", TEMPLATE_NAME)).toBeInTheDocument());
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
                // ---- the composite fixture's three dialogs ----
                // The framed sequence's workbench: its parts are a list with a toolbar, and Edit
                // opens PartEdit on the selected part
                .story("PartDialogOk", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    openWorkbench(play, "framed");
                    final Play screen = play.screen();
                    // Edit is disabled until a part is chosen: a click anywhere in the row chooses it
                    play.waitFor(() -> play.expect(play.querySelector(PART_KIND)).not().toBeNull());
                    play.click(play.querySelector(PART_KIND));
                    play.click(play.getByRole("button", TextMatch.containing("Edit the selected part")));
                    final Play dialog = screen.within(screen.findByText("Edit Part", StroomDom.DIALOG_TITLE)
                            .closest(StroomDom.DIALOG));
                    play.click(dialog.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText("Edit Part", StroomDom.DIALOG_TITLE))
                            .toBeNull());
                    expectNoProblems(play);
                })
                // Cancel leaves the sequence as it was, so the document stays clean
                .story("PartDialogCancel", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    openWorkbench(play, "framed");
                    final Play screen = play.screen();
                    // Edit is disabled until a part is chosen: a click anywhere in the row chooses it
                    play.waitFor(() -> play.expect(play.querySelector(PART_KIND)).not().toBeNull());
                    play.click(play.querySelector(PART_KIND));
                    play.click(play.getByRole("button", TextMatch.containing("Edit the selected part")));
                    final Play dialog = screen.within(screen.findByText("Edit Part", StroomDom.DIALOG_TITLE)
                            .closest(StroomDom.DIALOG));
                    play.click(dialog.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Edit Part", StroomDom.DIALOG_TITLE))
                            .toBeNull());
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    expectNoProblems(play);
                })
                // The pattern tree's workbench: Edit opens PatternNodeEdit on the selected node
                .story("PatternNodeDialogOk", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    openWorkbench(play, "tree");
                    final Play screen = play.screen();
                    play.click(play.getByRole("button", TextMatch.containing("Edit the selected node")));
                    final Play dialog = screen.within(screen.findByText("Edit Node", StroomDom.DIALOG_TITLE)
                            .closest(StroomDom.DIALOG));
                    play.click(dialog.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText("Edit Node", StroomDom.DIALOG_TITLE))
                            .toBeNull());
                    expectNoProblems(play);
                })
                // Cancel leaves the tree as it was
                .story("PatternNodeDialogCancel", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    openWorkbench(play, "tree");
                    final Play screen = play.screen();
                    play.click(play.getByRole("button", TextMatch.containing("Edit the selected node")));
                    final Play dialog = screen.within(screen.findByText("Edit Node", StroomDom.DIALOG_TITLE)
                            .closest(StroomDom.DIALOG));
                    play.click(dialog.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Edit Node", StroomDom.DIALOG_TITLE))
                            .toBeNull());
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    expectNoProblems(play);
                })
                // A choose's branch head carries the pencil that opens ConditionEdit on its test
                .story("ConditionDialogOk", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    // The pencil on the branch head is a label with a title, not a button
                    play.waitFor(() -> play.expect(play.getByTitle(
                            TextMatch.containing("Edit this branch's condition"))).toBeInTheDocument());
                    play.click(play.getByTitle(TextMatch.containing("Edit this branch's condition")));
                    final Play dialog = screen.within(screen.findByText("Edit when", StroomDom.DIALOG_TITLE)
                            .closest(StroomDom.DIALOG));
                    play.click(dialog.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText("Edit when", StroomDom.DIALOG_TITLE))
                            .toBeNull());
                    expectNoProblems(play);
                })
                // An or whose parts include a negation: rows, not the wire form. It is the shape
                // apache_httpd uses thirty times, and until the rows learnt or and not it opened
                // as JSON — along with 157 of the corpus's 476 conditions
                .story("ConditionOrAndNot", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByTitle(
                            TextMatch.containing("Edit this branch's condition"))).toBeInTheDocument());
                    play.click(play.getByTitle(TextMatch.containing("Edit this branch's condition")));
                    final Play dialog = screen.within(screen.findByText("Edit when", StroomDom.DIALOG_TITLE)
                            .closest(StroomDom.DIALOG));
                    // Rows: two of them, joined by "any", the second negated
                    play.waitFor(() -> play.expect(dialog.getByText("holds when")).toBeVisible());
                    play.expect(dialog.getAllByText("contains", ".clause-row__operator *").count())
                            .toSatisfy("a row per part", count -> ((Integer) count) == 2);
                    play.expect(dialog.getByText("any", ".clause-list__join *")).toBeVisible();
                    play.expect(dialog.getByText("not", ".clause-row__negated *")).toBeVisible();
                    // And not the wire form, which is what this condition used to open as. The
                    // lint stays in the DOM and is hidden, so this asks whether it is visible
                    // rather than whether it is there.
                    play.expect(dialog.getByText(TextMatch.containing("edited as its wire form")))
                            .not().toBeVisible();
                    // The word between the rows is the join: changing the picker changes it, so
                    // the rows never contradict the choice above them
                    play.expect(dialog.getByText("or", ".edit-form__number")).toBeVisible();
                    pickFromBox(play, screen, dialog.getByLabelText("Holds when"), "all");
                    play.waitFor(() -> play.expect(dialog.getByText("and", ".edit-form__number"))
                            .toBeVisible());
                    play.click(dialog.getByRole("button", StroomDom.button("Cancel")));
                    expectNoProblems(play);
                })
                // The one transform with a form of its own (design 46 §8). Its replacements are
                // two parallel lists in the model and one line each on screen, and the corpus's
                // own values include a newline — which a text area cannot hold, so it is spelt
                .story("TranslateDialog", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("translate", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(play.getByText("translate", CARD_KIND));
                    final Play dialog = dialogMatching(play, screen, "translate");
                    // A form, not the wire form it used to open as
                    play.waitFor(() -> play.expect(dialog.getByLabelText("Select")).toBeVisible());
                    play.expect(dialog.getByLabelText("Name")).toHaveValue("escaped");
                    // One line per replacement, the newline written as \n because the field
                    // cannot hold the character itself
                    play.expect(dialog.getByLabelText(TextMatch.containing("Replacements")))
                            .toHaveValue("& = &amp;\n< = &lt;\n\\n =  ");
                    play.click(dialog.getByRole("button", StroomDom.button("Cancel")));
                    expectNoProblems(play);
                })
                // Cancel leaves the condition as it was
                .story("ConditionDialogCancel", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    // The pencil on the branch head is a label with a title, not a button
                    play.waitFor(() -> play.expect(play.getByTitle(
                            TextMatch.containing("Edit this branch's condition"))).toBeInTheDocument());
                    play.click(play.getByTitle(TextMatch.containing("Edit this branch's condition")));
                    final Play dialog = screen.within(screen.findByText("Edit when", StroomDom.DIALOG_TITLE)
                            .closest(StroomDom.DIALOG));
                    play.click(dialog.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Edit when", StroomDom.DIALOG_TITLE))
                            .toBeNull());
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
                })

                // The sample page, which the Data row opens in place of the navigator's rows
                // (design 44 §5a). It had no story at all, which is how a SimpleLayoutPanel sat in
                // it unseen after the rest of the tab had been found and fixed
                .story("SamplePage", context -> render(context, false))
                .withPlay(play -> {
                    openSamplePage(play);
                    // The page's own heading, and what it says about how this kind is kept
                    play.expect(play.getByText("Sample data from")).toBeVisible();
                    play.expect(play.getByText(TextMatch.containing("saved with the project")))
                            .toBeVisible();
                    // The kept text is in the paste box, which can be typed into. Asserted here
                    // as well as in the locked story, so that neither passes by matching an
                    // editor that is always one or the other
                    play.expect(play.querySelector(ACE_INPUT)).not().toBeNull();
                    play.expect(play.querySelector(ACE_INPUT)).not().toHaveAttribute("aria-readonly");
                    play.expect(play.getByLabelText("Sample data from")).not().toBeDisabled();
                    // The page replaces the navigator's rows, so no template strip is beside it
                    play.expect(play.querySelector(MATCH_CHIP)).toBeNull();
                    expectNoProblems(play);
                })
                // The same page with the document locked. Nothing here watched the read-only
                // context before this story: the picker and the paste box stayed editable on a
                // locked document, the one pane of the tab the earlier read-only work missed
                .story("SamplePageReadOnly", context -> render(context, true))
                .withPlay(play -> {
                    openSamplePage(play);
                    play.expect(play.getByText("Sample data from")).toBeVisible();
                    // AceEditorPane marks a read-only editor on the text input, which is what the
                    // keyboard reaches; the picker's own control is disabled outright
                    play.expect(play.querySelector(ACE_INPUT)).toHaveAttribute("aria-readonly", "true");
                    play.expect(play.getByLabelText("Sample data from")).toBeDisabled();
                    expectNoProblems(play);
                })

                // Design 46 rung 1: a document created a moment ago, authored towards the
                // strict_kv fixture, saved, and the saved project compared with what it should
                // be. The keystone of design 46 §3 — a story's preview and validate replies are
                // canned, so what a story can prove is that the UI *can build* a configuration,
                // never that the configuration runs. The save request's body is the only thing
                // on screen that came from the editor rather than from a fixture.
                .story("AuthorRungOne", ShapeshifterEditorStories::renderBlank)
                .withPlay(play -> {
                    final Play screen = play.screen();

                    // §5.1 step 1, with §5.3's correction: the root template is already there,
                    // named "document" in the panel whatever the template itself is called
                    play.waitFor(() -> play.expect(play.getByText("document", TEMPLATE_NAME))
                            .toBeInTheDocument());
                    // Nothing has been edited, so there is nothing to save
                    play.expect(play.getByRole("button", "Save"))
                            .toHaveAttribute("aria-disabled", "true");

                    // §5.1 steps 2 and 3: the project's own settings
                    play.click(play.getByText("Settings", TEMPLATE_NAME));
                    play.waitFor(() -> play.expect(play.getByText("Source — the document"))
                            .toBeVisible());
                    play.clear(play.getByLabelText("Project name"));
                    play.type(play.getByLabelText("Project name"), "strict_kv");
                    // A Stroom SelectionBox, not a native select: click the box, then the item in
                    // the popup it opens. The popup is a GWT PopupPanel on the document body, so
                    // it is reached through play.screen() rather than the story's own root.
                    pickFromBox(play, screen, play.getByLabelText("Encoding"), "utf-8");

                    // That is an edit, so Save has work to do
                    play.waitFor(() -> play.expect(play.getByRole("button", "Save"))
                            .not().toHaveAttribute("aria-disabled", "true"));

                    // §5.3 step 8: the samples call the root template "source", and the editor
                    // calls it "document". It cannot be removed or moved, but it can be renamed
                    play.click(play.getByText("document", TEMPLATE_NAME));
                    play.click(play.getByRole("button", "Edit template"));
                    final Play renaming = dialogMatching(play, screen, "template");
                    play.clear(renaming.getByLabelText("Name"));
                    renaming.type(renaming.getByLabelText("Name"), "source");
                    play.click(renaming.getByRole("button", StroomDom.button("OK")));
                    // The strip carries the new name; the panel's row does not, and that is a
                    // finding rather than a wait that needs fixing (design 46 §5.9): the document
                    // row is labelled with the fixed word "document"
                    // (TemplatePanelController.refresh), so the author sees no change there.
                    play.waitFor(() -> play.expect(play.getByText("source", STRIP_NAME))
                            .toBeInTheDocument());

                    // §5.4 steps 10 and 12: the pair template, in a mode made as it is needed
                    play.click(play.getByRole("button", "Add template"));
                    final Play adding = dialogMatching(play, screen, "New Template");
                    adding.type(adding.getByLabelText("Name"), "pair");
                    play.click(adding.getByRole("button", "New..."));
                    final Play naming = dialogMatching(play, screen, "mode");
                    naming.type(naming.getByRole("textbox").first(), "kv");
                    play.click(naming.getByRole("button", StroomDom.button("OK")));
                    play.click(adding.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.getByText("pair", TEMPLATE_NAME))
                            .toBeInTheDocument());

                    // §5.4's finding, and the answer to it: between making a template in a new
                    // mode and dispatching into that mode, the project is in a state where the
                    // template matches nothing and the preview is empty. The author has done
                    // nothing wrong — they are halfway through a two-part edit — so the panel
                    // says so on the mode rather than leaving them to wonder.
                    play.waitFor(() -> play.expect(play.getByText("nothing dispatches into this",
                            ".template-panel__mode-note")).toBeVisible());

                    // §5.4 step 11: the pattern, in the workbench the match chip opens. A new
                    // template's match is already an empty regex (Templates.create), so the kind
                    // needs no choosing — only the pattern needs typing
                    play.click(play.querySelector(MATCH_CHIP));
                    play.waitFor(() -> play.expect(play.getByLabelText("Pattern"))
                            .toBeInTheDocument());
                    // "[[" types a literal "[": play.type is userEvent.type, where "[" opens a
                    // key descriptor, so an unescaped "([a-z]+)" arrives as "(+)" with the
                    // character class silently eaten (design 46 §5.9)
                    play.type(play.getByLabelText("Pattern"), "([[a-z]+)=([[^\\n]*)\\n");
                    play.click(play.getByRole("button", TextMatch.regex("close the workbench", "i")));

                    // §5.5 steps 13 to 15: the envelope, and the dispatch into the mode. The
                    // apply-templates card is already there — it came with the document template
                    // — so what the author adds is the text either side of it, and the mode on it
                    // By "document", not by the name just given it: the panel's row for the
                    // document template is labelled with that fixed word, so the rename of two
                    // stages ago is invisible here (§5.9's finding, met for the second time)
                    selectTemplate(play, "document");
                    play.waitFor(() -> play.expect(play.getByText("+ instruction"))
                            .toBeInTheDocument());
                    addInstruction(play, screen, "text", "<records>\n");
                    // It lands at the end, so the body is [apply-templates, text]: the second
                    // card's Move up puts the envelope's opening before the dispatch
                    play.click(play.getAllByTitle("Move up").nth(1));
                    addInstruction(play, screen, "text", "</records>\n");

                    // The dispatch itself: the card the document template arrived with, told
                    // which mode to dispatch into
                    play.click(play.getByText("apply-templates", CARD_KIND));
                    final Play dispatching = dialogMatching(play, screen, "apply-templates");
                    pickFromBox(play, screen, dispatching.getByLabelText("Mode"), "kv");
                    play.click(dispatching.getByRole("button", StroomDom.button("OK")));
                    // And the note goes: the two-part edit is finished, and nothing is wrong any
                    // more. A note that stayed would be worse than no note at all.
                    play.waitFor(() -> play.expect(play.queryByText("nothing dispatches into this",
                            ".template-panel__mode-note")).toBeNull());

                    // §5.6 steps 17 to 19: the pair's body, five cards for one line of output
                    selectTemplate(play, "pair");
                    addInstruction(play, screen, "text", " <pair key=\"");
                    addInstruction(play, screen, "value-of", "$1");
                    addInstruction(play, screen, "text", "\">");
                    addInstruction(play, screen, "value-of", "$2");
                    addInstruction(play, screen, "text", "</pair>\n");

                    // §5.7 steps 21 to 25: the catch-all, below pair, consuming and warning
                    play.click(play.getByRole("button", "Add template"));
                    final Play junk = dialogMatching(play, screen, "New Template");
                    junk.type(junk.getByLabelText("Name"), "junk_line");
                    pickFromBox(play, screen, junk.getByLabelText("Mode"), "kv");
                    play.click(junk.getByRole("button", StroomDom.button("OK")));
                    play.click(play.querySelector(MATCH_CHIP));
                    play.waitFor(() -> play.expect(play.getByLabelText("Pattern"))
                            .toBeInTheDocument());
                    play.type(play.getByLabelText("Pattern"), "[[^\\n]*\\n?");
                    play.click(play.getByRole("button", TextMatch.regex("close the workbench", "i")));
                    // The role that makes it an eater rather than a record. The config calls the
                    // flag "consume"; the picker says "advance only", because MatchRole tried
                    // "skip" and "eater" and found both misleading — nothing is discarded and
                    // nothing stops. The step table's word is the model's, so this is where the
                    // two vocabularies meet.
                    pickFromBox(play, screen, play.getByLabelText("What the match is for"),
                            "advance only");

                    // §5.7 step 25: the warning, which is the authored replacement for DS3's
                    // engine-generated skip reports (design 11 §4). Its message is a reference
                    // expression of a literal and the whole match, written as the field's own
                    // help describes: quoted text for a literal, $0 for the group, joined by
                    // being written one after another.
                    selectTemplate(play, "junk_line");
                    play.click(play.getByText("+ instruction"));
                    play.click(screen.findByText("emit-error", StroomDom.MENU_ITEM_TEXT));
                    final Play warning = dialogMatching(play, screen, "emit-error");
                    // Lowercase, as the picker lists them and as the wire spells them
                    // (JsonFields.label); the default is "error", so this is a real choice
                    pickFromBox(play, screen, warning.getByLabelText("Severity"), "warning");
                    warning.type(warning.getByLabelText("Message"),
                            "\"unrecognised line: \" $0");
                    play.click(warning.getByRole("button", StroomDom.button("OK")));

                    // §5.8 step 27, the keystone (design 46 §3), read from the validate request
                    // rather than from the save: clicking Save sends nothing here, because the
                    // harness mounts the editor host without the document plugin that performs a
                    // save, and no document-editor story in the workbench asserts one. What the
                    // editor would save is what validate carries, which is the project itself.
                    //
                    // Compared by containment, not equality: the editor writes defaults the
                    // fixture leaves out (match_limits, max_depth), and containment tolerates
                    // those while still requiring every array to be the same length — so a
                    // missing template or a body card in the wrong place fails.
                    final Spy requests = play.spy(ScreenHarness.REQUEST_SPY);
                    play.waitFor(() -> play.expect(requests).toHaveBeenCalledWith(
                            RequestMatcher.post(VALIDATE_PATH).toSpyMatcher()));
                    play.waitFor(() -> play.expect("the project the editor built",
                                    () -> JsonValues.jsonContains(
                                            projectSent(requests), withoutIds(PROJECT)))
                            .toEqual(true));
                    expectNoProblems(play);
                });
    }

    // Stroom's Ace editor's text area
    private static final String ACE_INPUT = ".ace_text-input";

    // ShapeshifterResource.validate(): sent on every edit, carrying the project the editor is
    // holding. This, not the save, is where a story can read what the UI built — see the
    // AuthorRungOne story for why.
    private static final String VALIDATE_PATH = "/shapeshifter/v1/validate";

    // Ace marks a read-only editor on its root
    private static final String ACE_READ_ONLY = ".ace_editor.ace_read-only";

    // A template's name in the panel's list, and the Data row's, which is a row too
    private static final String TEMPLATE_NAME = ".template-row__name";

    // The kind of instruction a body card holds ("text", "value-of", ...)
    private static final String CARD_KIND = ".body-card__kind";

    // The strip labels each row with its key ("match", "match is", "guard", "body")
    private static final String STRIP_KEY = ".key-label";

    // An item in the list a SelectionBox opens (selection-item-cell.css). The list is a GWT
    // PopupPanel on the document body, so it is only reachable through play.screen().
    private static final String SELECTION_ITEM = ".selection-item-cell__text";

    // The selected template's name, in the strip's head. The panel's own row for the document
    // template reads "document" whatever the template is called, so this is where a rename shows.
    private static final String STRIP_NAME = ".template-strip__name";

    // The chip that carries a template's match, and opens the pattern workbench
    private static final String MATCH_CHIP = ".template-strip__match-chip";
    // A part's kind in the framed sequence's list; clicking it selects the part
    private static final String PART_KIND = ".parts__kind";

    // Selects a template and opens its match workbench, where the parts and the pattern tree live
    private static void openWorkbench(final Play play, final String name) {
        selectTemplate(play, name);
        play.waitFor(() -> play.expect(play.querySelector(MATCH_CHIP)).not().toBeNull());
        play.click(play.querySelector(MATCH_CHIP));
        play.waitFor(() -> play.expect(play.getByRole("button",
                TextMatch.regex("close the workbench", "i"))).toBeInTheDocument());
    }

    // Opens the sample page: the Data row in the panel is a destination, not a frame, so
    // choosing it puts the page where the navigator's rows were (design 44 §5a)
    private static void openSamplePage(final Play play) {
        play.waitFor(() -> play.expect(play.getByText("Data", TEMPLATE_NAME)).toBeInTheDocument());
        play.click(play.getByText("Data", TEMPLATE_NAME));
        play.waitFor(() -> play.expect(play.getByText("Sample data from")).toBeInTheDocument());
    }

    // Picks a template in the panel, which is what fills the strip beside it
    private static void selectTemplate(final Play play, final String name) {
        play.waitFor(() -> play.expect(play.getByText(name, TEMPLATE_NAME)).toBeInTheDocument());
        play.click(play.getByText(name, TEMPLATE_NAME));
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
        return render(context, readOnly, FIXTURES, DOC_REF);
    }

    // A dialog by a word in its title, as a Play to scope queries to it: nested dialogs (the
    // template dialog and the mode prompt it opens) each need their own OK.
    private static Play dialogMatching(final Play play, final Play screen, final String title) {
        // Case-insensitively: the dialogs title themselves "Edit Template" and "New text", so a
        // caller naming the thing it is editing should not have to know which words are capital.
        return play.within(screen.findByText(TextMatch.regex(title, "i"), StroomDom.DIALOG_TITLE)
                .closest(StroomDom.DIALOG));
    }

    // A SelectionBox's value, chosen the way a person does: click the box, then the item in the
    // popup it opens. Scoped to the item cells, because by the time a mode has templates in it
    // its name is also a heading in the panel, and an unscoped search finds both.
    private static void pickFromBox(final Play play, final Play screen,
                                     final Query box, final String item) {
        play.click(box);
        play.click(screen.findByText(item, SELECTION_ITEM));
    }

    // One body instruction, added through the "+ instruction" line and its kind menu, with the
    // one field the kinds used here need (design 46 §5.5-§5.7).
    private static void addInstruction(final Play play, final Play screen,
                                       final String kind, final String value) {
        play.click(play.getByText("+ instruction"));
        // The kinds are a menu on the page's body, not in the canvas
        play.click(screen.findByText(kind, StroomDom.MENU_ITEM_TEXT));
        final Play dialog = dialogMatching(play, screen, "New " + kind);
        dialog.type(dialog.getByRole("textbox").first(), value);
        play.click(dialog.getByRole("button", StroomDom.button("OK")));
    }

    /// The editor over a document with no project in it, as a new one arrives (design 46 §5.1).
    private static Widget renderBlank(final StoryContext context) {
        return render(context, false, BLANK_FIXTURES, BLANK_REF);
    }

    // The project the editor last sent for validation, which is the project it is holding.
    // Template ids are generated, so they are taken out of the comparison rather than pinned —
    // an id is the one part of a configuration no author chooses.
    private static String projectSent(final Spy requests) {
        final List<List<Object>> calls = requests.getCalls();
        for (int i = calls.size() - 1; i >= 0; i--) {
            final RecordedRequest request = RecordedRequest.parse(
                    String.valueOf(calls.get(i).get(0)));
            if ("POST".equals(request.getMethod()) && VALIDATE_PATH.equals(request.getPath())) {
                return withoutIds(request.getBody());
            }
        }
        return null;
    }

    // Every template id replaced by a dash, so two projects differing only in generated ids
    // compare equal.
    private static String withoutIds(final String projectJson) {
        return projectJson == null
                ? null
                : projectJson.replaceAll("\"id\"\\s*:\\s*\"[^\"]*\"", "\"id\": \"-\"");
    }

    /// The same screen over the composite fixture, whose templates reach the framed sequence, the
    /// pattern tree and a choose.
    private static Widget renderComposite(final StoryContext context) {
        return render(context, false, COMPOSITE_FIXTURES, COMPOSITE_REF);
    }

    private static Widget render(final StoryContext context, final boolean readOnly,
                                 final RestFixtures fixtures, final DocRef docRef) {
        final AppScreenGinjector injector = GWT.create(AppScreenGinjector.class);
        final ScreenHarness harness = ScreenHarness.builder(context, fixtures)
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
        harness.afterStartUp(() -> open(harness, injector, docRef));
        return harness.asWidget();
    }

    private static void open(final ScreenHarness harness, final AppScreenGinjector injector,
                             final DocRef docRef) {
        final DocumentEditorHost<ShapeshifterDoc> controller = new DocumentEditorHost<>(
                harness.getEventBus(), injector.getShapeshifterEditor(), ShapeshifterDoc.TYPE);
        final ShapeshifterResource resource = GWT.create(ShapeshifterResource.class);
        harness.getRestFactory()
                .create(resource)
                .method(res -> res.fetch(docRef.getUuid()))
                .onSuccess(doc -> harness.getSecurityContext().hasDocumentPermission(
                        docRef,
                        DocumentPermission.EDIT,
                        allowUpdate -> {
                            controller.setReadOnly(!allowUpdate);
                            controller.read(docRef, doc);
                            harness.show(controller);
                        },
                        error -> harness.spy(ScreenHarness.ALERT_SPY, "ERROR: " + error.getMessage()),
                        controller))
                .taskMonitorFactory(controller)
                .exec();
    }
}
