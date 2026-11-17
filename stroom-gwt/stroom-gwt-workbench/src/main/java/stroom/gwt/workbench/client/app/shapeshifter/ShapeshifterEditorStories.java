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
import stroom.pipeline.shared.SourceLocation;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.client.component.ContentPane;
import stroom.shapeshifter.client.component.ContentPaneController;
import stroom.shapeshifter.client.model.Hot;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.SampleSource;
import stroom.shapeshifter.client.model.TraceModel;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.shapeshifter.shared.ShapeshifterTrace;
import stroom.shapeshifter.shared.ShapeshifterTrace.Capture;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;
import com.google.web.bindery.event.shared.SimpleEventBus;

import java.util.Arrays;
import java.util.Collections;
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
    // the comment line warned about, as the fixture's junk_line template says.
    //
    // Every number here agrees with the input above, which until 2026-11-16 it did not: the
    // source frame claimed 56 characters of a 60-character input, junk_line claimed 35 of its own
    // 38, and the third line had no frame at all though the output says it was written. A trace
    // that disagrees with itself is worse than a short one — it makes the panes look broken when
    // they are drawing exactly what they were handed, which is how a screenshot of this fixture
    // was nearly misread.
    private static final String TRACE = """
    {
      "compiled": true, "sampleRead": true,
      "input": "host=web01\\n# a comment the grammar never claimed\\nlevel=warn\\n",
      "output": "<records>\\n <pair key=\\"host\\">web01</pair>\\n <pair key=\\"level\\">warn</pair>\\n</records>\\n",
      "frames": [
        {"id": 1, "parentId": 0, "templateId": "00000000-0000-0000-0000-000000000001",
         "templateName": "source", "matchIndex": 0, "depth": 0,
         "inputOffset": 0, "inputLength": 60, "contentOffset": 0, "contentLength": 60},
        {"id": 2, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000002",
         "templateName": "pair", "matchIndex": 0, "depth": 1,
         "inputOffset": 0, "inputLength": 11, "contentOffset": 0, "contentLength": 11,
         "content": "host=web01\\n"},
        {"id": 3, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000003",
         "templateName": "junk_line", "matchIndex": 0, "depth": 1,
         "inputOffset": 11, "inputLength": 38, "contentOffset": 11, "contentLength": 38,
         "content": "# a comment the grammar never claimed\\n"},
        {"id": 4, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000002",
         "templateName": "pair", "matchIndex": 1, "depth": 1,
         "inputOffset": 49, "inputLength": 11, "contentOffset": 49, "contentLength": 11,
         "content": "level=warn\\n"}
      ],
      "captures": [
        {"frameId": 2, "name": "key", "value": "host", "contentOffset": 0, "contentLength": 4},
        {"frameId": 2, "name": "value", "value": "web01", "contentOffset": 5, "contentLength": 5},
        {"frameId": 4, "name": "key", "value": "level", "contentOffset": 0, "contentLength": 5},
        {"frameId": 4, "name": "value", "value": "warn", "contentOffset": 6, "contentLength": 4}
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
                {"trim": {"select": [{"parts": [{"capture": {"var_id": "escaped", "group": 0}}]}]}},
                {"replace": {"select": [{"parts": [{"capture": {"var_id": "line", "group": 0}}]}],
                  "pattern": " +", "replacement": " ", "is_regex": true, "name": "squashed"}},
                {"decode": {"select": [{"parts": [{"capture": {"var_id": "line", "group": 0}}]}],
                  "codec": "base64", "name": "plain"}},
                {"divide": {"select": [{"parts": [{"capture": {"var_id": "line", "group": 0}}]},
                                       {"parts": [{"capture": {"var_id": "line", "group": 0}}]}],
                  "name": "halved"}},
                {"add": {"select": [{"parts": [{"capture": {"var_id": "line", "group": 0}}]},
                                    {"parts": [{"capture": {"var_id": "line", "group": 0}}]}],
                  "name": "total"}},
                {"tokenize": {"select": [{"parts": [{"capture": {"var_id": "line", "group": 0}}]}],
                  "delimiter": ",", "name": "parts"}},
                {"substring": {"select": [{"parts": [{"capture": {"var_id": "line", "group": 0}}]}],
                  "start": 1, "length": 4, "name": "head"}},
                {"format-date": {"select": [{"parts": [{"capture": {"var_id": "line", "group": 0}}]}],
                  "pattern": "HH:mm:ss", "timezone": "UTC", "name": "shown"}},
                {"parse-date": {"select": [{"parts": [{"capture": {"var_id": "line", "group": 0}}]}],
                  "pattern": "iso", "timezone": "UTC", "name": "when"}},
                {"string-join": {"select": [{"parts": [{"capture": {"var_id": "line", "group": 0}}]},
                                            {"parts": [{"capture": {"var_id": "escaped", "group": 0}}]}],
                  "separator": ", ", "name": "joined"}},
                {"call": {"function": "current-user", "select": [], "name": "who"}},
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
                    // A group's rule stops square where its characters do, as every underline in
                    // the editor does (design 18 §5.12.3). The radius this block keeps is for the
                    // wash behind a selected or hovered group, which is why only the bottom
                    // corners are squared rather than the whole thing.
                    // Waited for: until the pattern inspection comes back, RegexController
                    // holds a null info and paints the pattern as plain escaped text with no
                    // group spans in it at all (RegexController.inspect).
                    play.waitFor(() -> play.expect(play.querySelector(REGEX_GROUP)).not().toBeNull());
                    final Query group = play.querySelector(REGEX_GROUP);
                    play.expect(group.computedStyle("border-bottom-left-radius")).toEqual("0px");
                    play.expect(group.computedStyle("border-top-left-radius")).toEqual("2px");
                    // The matches pane answers the pointer too. It has no host and no cursor —
                    // the workbench is editing a pattern with no run behind it — so it lights
                    // itself, which is the whole of what pointing can mean here. Without this it
                    // would be the one marked surface in the editor that says nothing at all,
                    // while `.mark { cursor: pointer }` claims otherwise.
                    play.waitFor(() -> play.expect(play.querySelector(SAMPLE_MARK)).not().toBeNull());
                    final Query match = play.querySelector(SAMPLE_MARK);
                    play.hover(match);
                    play.waitFor(() -> play.expect(match).toHaveClass("highlight"));
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
                // The eight transforms that are one shape share one form (design 46 §8.2). This
                // one has no name, which is the commonest case in the corpus — 42 of its 55
                // trims — and means the result is written straight out rather than bound
                .story("TransformDialog", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("trim", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(play.getByText("trim", CARD_KIND));
                    final Play dialog = dialogMatching(play, screen, "trim");
                    // A form, not the wire form it used to open as
                    play.waitFor(() -> play.expect(
                            dialog.getByLabelText(TextMatch.containing("Select"))).toBeVisible());
                    play.expect(dialog.getByLabelText(TextMatch.containing("Select")))
                            .toHaveValue("escaped");
                    // Blank, and that is the configuration rather than something missing
                    play.expect(dialog.getByLabelText("Name")).toHaveValue("");
                    play.click(dialog.getByRole("button", StroomDom.button("Cancel")));
                    expectNoProblems(play);
                })
                // OK writes what the fields hold back into the project, which is the half a
                // Cancel story never reaches (UI-GUIDE.md): the name is typed, kept, and read
                // back when the card is opened again
                .story("TransformDialogOk", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("trim", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(play.getByText("trim", CARD_KIND));
                    final Play naming = dialogMatching(play, screen, "trim");
                    play.waitFor(() -> play.expect(naming.getByLabelText("Name")).toBeVisible());
                    play.type(naming.getByLabelText("Name"), "tidied");
                    play.click(naming.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText(
                            TextMatch.regex("trim", "i"), StroomDom.DIALOG_TITLE)).toBeNull());
                    // Opened again, the form holds what was saved — and the select with it
                    play.click(play.getByText("trim", CARD_KIND));
                    final Play again = dialogMatching(play, screen, "trim");
                    play.waitFor(() -> play.expect(again.getByLabelText("Name"))
                            .toHaveValue("tidied"));
                    play.expect(again.getByLabelText("Select")).toHaveValue("escaped");
                    play.click(again.getByRole("button", StroomDom.button("Cancel")));
                    expectNoProblems(play);
                })
                // The kinds with a form of their own: replace, which has two words and a flag,
                // and decode, whose codec is a closed set and so a picker rather than a field.
                // With these two every transform in the vocabulary opens as a form (§8.3)
                .story("TransformFormsOfTheirOwn", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("replace", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(play.getByText("replace", CARD_KIND));
                    final Play replacing = dialogMatching(play, screen, "replace");
                    play.waitFor(() -> play.expect(replacing.getByLabelText("Find"))
                            .toHaveValue(" +"));
                    play.expect(replacing.getByLabelText("Replace with")).toHaveValue(" ");
                    play.expect(replacing.getByLabelText("Name")).toHaveValue("squashed");
                    play.click(replacing.getByRole("button", StroomDom.button("Cancel")));
                    // The next card is only safe to click once this dialog has gone: a click
                    // that lands while it is closing opens nothing
                    play.waitFor(() -> play.expect(screen.queryByText(
                            TextMatch.regex("replace", "i"), StroomDom.DIALOG_TITLE)).toBeNull());

                    play.click(play.getByText("decode", CARD_KIND));
                    final Play decoding = dialogMatching(play, screen, "decode");
                    // By value, not by visibility: a select-only SelectionBox carries its aria
                    // label on an inner input the box itself covers, so the labelled element is
                    // not the visible one even when the control plainly is
                    play.waitFor(() -> play.expect(decoding.getByLabelText("Codec"))
                            .toHaveValue("base64"));
                    play.expect(decoding.getByText("Codec")).toBeVisible();
                    play.click(decoding.getByRole("button", StroomDom.button("Cancel")));
                    expectNoProblems(play);
                })
                // divide: the two inputs of a / b, named rather than listed
                .story("FormDivide", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("divide", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(play.getByText("divide", CARD_KIND));
                    final Play dialog = dialogMatching(play, screen, "divide");
                    play.waitFor(() -> play.expect(dialog.getByLabelText("Name")).toBeVisible());
                    play.expect(dialog.getByLabelText("Left")).toBeVisible();
                    expectNoProblems(play);
                })
                // add: a fold: its inputs are a list because there may be any number
                .story("FormAdd", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("add", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(play.getByText("add", CARD_KIND));
                    final Play dialog = dialogMatching(play, screen, "add");
                    play.waitFor(() -> play.expect(dialog.getByLabelText("Name")).toBeVisible());
                    expectNoProblems(play);
                })
                // tokenize: select, the one word the kind turns on, and a name
                .story("FormTokenize", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("tokenize", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(play.getByText("tokenize", CARD_KIND));
                    final Play dialog = dialogMatching(play, screen, "tokenize");
                    play.waitFor(() -> play.expect(dialog.getByLabelText("Name")).toBeVisible());
                    play.expect(dialog.getByLabelText("Delimiter")).toBeVisible();
                    expectNoProblems(play);
                })
                // substring: a start and a length, either of which may be left out
                .story("FormSubstring", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("substring", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(play.getByText("substring", CARD_KIND));
                    final Play dialog = dialogMatching(play, screen, "substring");
                    play.waitFor(() -> play.expect(dialog.getByLabelText("Name")).toBeVisible());
                    play.expect(dialog.getByLabelText("Start")).toBeVisible();
                    expectNoProblems(play);
                })
                // format-date: a pattern and a time zone
                .story("FormFormatDate", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("format-date", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(play.getByText("format-date", CARD_KIND));
                    final Play dialog = dialogMatching(play, screen, "format-date");
                    play.waitFor(() -> play.expect(dialog.getByLabelText("Name")).toBeVisible());
                    play.expect(dialog.getByLabelText("Pattern")).toBeVisible();
                    expectNoProblems(play);
                })
                // parse-date: the only kind needing five fields, which is why there are five
                .story("FormParseDate", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("parse-date", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(play.getByText("parse-date", CARD_KIND));
                    final Play dialog = dialogMatching(play, screen, "parse-date");
                    play.waitFor(() -> play.expect(dialog.getByLabelText("Name")).toBeVisible());
                    play.expect(dialog.getByLabelText("Relative to")).toBeVisible();
                    expectNoProblems(play);
                })
                // string-join: several values and what goes between them
                .story("FormStringJoin", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("string-join", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(play.getByText("string-join", CARD_KIND));
                    final Play dialog = dialogMatching(play, screen, "string-join");
                    play.waitFor(() -> play.expect(dialog.getByLabelText("Name")).toBeVisible());
                    play.expect(dialog.getByLabelText("Separator")).toBeVisible();
                    expectNoProblems(play);
                })
                // call: a function and its arguments, of which there may be none
                .story("FormCall", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("call", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(play.getByText("call", CARD_KIND));
                    final Play dialog = dialogMatching(play, screen, "call");
                    play.waitFor(() -> play.expect(dialog.getByLabelText("Name")).toBeVisible());
                    play.expect(dialog.getByLabelText("Function")).toBeVisible();
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

                // The content pane's marks, one story per state, because what this pane does is
                // visual and a passing assertion says nothing about whether it reads (design 18
                // §5.4, Q17-Q19). At rest the capture tint is the only permanent mark: a match
                // draws nothing until it is pointed at
                .story("InputPaneAtRest", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.click(runButton(play));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_CAPTURE))
                            .not().toBeNull());
                    // The cursor is the document and the document binds nothing, so every
                    // capture in view came from beneath it: tinted, and none of them ruled. The
                    // rule means "this capture is one of the current level's" and at this level
                    // there are none (design 18 §5.11.2, ruled 2026-11-16).
                    play.expect(play.querySelector(MARK_CAPTURE)).not().toHaveClass("mark--ruled");
                    // Every mark carries a transparent rule at rest, so that pointing animates
                    // its colour rather than its existence (§5.12.3): "no rule here" is a
                    // colour, not a style.
                    play.expect(play.querySelector(MARK_CAPTURE).computedStyle("border-bottom-color"))
                            .toEqual("rgba(0, 0, 0, 0)");
                    // The tint is there regardless of depth, which is the half of this that a
                    // reader needs at any level: these bytes became a variable.
                    play.expect(play.querySelector(MARK_CAPTURE).computedStyle("--hue"))
                            .not().toEqual("");
                    expectNoProblems(play);
                })
                // Pointing at a match gives it a rule in its template's hue — the extent that
                // used to be drawn permanently, now shown only where it is asked for
                .story("InputPaneMatchPointedAt", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.click(runButton(play));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_DOCUMENT))
                            .not().toBeNull());
                    play.hover(play.querySelector(MARK_DOCUMENT));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_DOCUMENT))
                            .toHaveClass("highlight"));
                    expectNoProblems(play);
                })
                // Descended into the document, the pane draws a mark per template that matched
                // beneath it — which is where "the immediate child template's colour" (§5.4)
                // becomes visible, and where one match is told from its neighbour
                .story("InputPaneDescended", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.click(runButton(play));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_DOCUMENT))
                            .not().toBeNull());
                    play.click(play.querySelector(MARK_DOCUMENT));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_PAIR)).not().toBeNull());
                    play.expect(play.querySelector(MARK_JUNK)).not().toBeNull();
                    expectNoProblems(play);
                })
                // And a capture the same way, from its own end
                .story("InputPaneCapturePointedAt", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.click(runButton(play));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_CAPTURE))
                            .not().toBeNull());
                    play.hover(play.querySelector(MARK_CAPTURE));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_CAPTURE))
                            .toHaveClass("highlight"));
                    expectNoProblems(play);
                })
                // The same rule, asked for from the other end: pointing at the template's row
                // in the panel marks what it matched in the pane (design 18 §5.5)
                .story("InputPaneFromTheTemplateRow", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.click(runButton(play));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_DOCUMENT))
                            .not().toBeNull());
                    play.click(play.querySelector(MARK_DOCUMENT));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_PAIR)).not().toBeNull());
                    play.hover(play.getByText("pair", TEMPLATE_NAME));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_PAIR))
                            .toHaveClass("highlight"));
                    // The class is not the point — the mark is. Q17 took away every permanent
                    // mark on a match, so if this does not draw, a match has no way to be seen
                    // at all and the ruling has nothing to stand on. A rule rather than the
                    // outline this first asserted: pointing is said with the rule alone
                    // (§5.12.3, ruled 2026-11-17).
                    play.waitFor(() -> play.expect(play.querySelector(MARK_PAIR)
                                    .computedStyle("border-bottom-color"))
                            .not().toEqual("rgba(0, 0, 0, 0)"));
                    play.expect(play.querySelector(MARK_PAIR).computedStyle("border-bottom-width"))
                            .toEqual("2px");
                    // And only that template's match: junk_line's is left alone, or "mark the
                    // one you are pointing at" would pass just as well if everything lit up
                    play.expect(play.querySelector(MARK_JUNK)).not().toHaveClass("highlight");
                    expectNoProblems(play);
                })
                // The pane on its own, over a sample bound three generations deep: a line taken
                // whole by the template that matched it, the key and value taken inside it by the
                // pairs beneath, and a short host name taken inside one of those. These are the
                // only stories with no editor around them — a ContentPane and a host of its own —
                // because what the pane does is meant to be looked at, and in a screenshot of the
                // whole tab it is a corner (design 18 §5.11, ruled 2026-11-16).
                //
                // At the document, which binds nothing: the tint and only the tint.
                .story("InputPaneTintAlone", ShapeshifterEditorStories::renderPaneOnly)
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.querySelector(MARK_CAPTURE)).not().toBeNull());
                    // The tint identifies captures and says nothing about depth, so a capture
                    // from one generation down and one from two look alike: same opacity, same
                    // kind of mark, no rule on either. That is the assertion three steps of
                    // opacity would have failed — opacity takes the glyphs along with the mark,
                    // and on the light theme the first step alone drops the weakest of the eight
                    // generated hues from 5.05:1 to 3.32:1, under AA, on text whose lightness the
                    // theme sets for no other purpose than to clear it.
                    play.expect(play.querySelector(CAPTURE_LINE).computedStyle("opacity")).toEqual("1");
                    play.expect(play.querySelector(CAPTURE_KEY).computedStyle("opacity")).toEqual("1");
                    play.expect(play.querySelector(CAPTURE_RULED)).toBeNull();
                    // Drawn, not absent: every mark carries a transparent rule at rest so that
                    // pointing animates its colour rather than its existence (§5.12.3). "No
                    // rule here" is therefore a colour, not a style.
                    play.expect(play.querySelector(CAPTURE_LINE).computedStyle("border-bottom-color"))
                            .toEqual("rgba(0, 0, 0, 0)");
                    // Two captures bound by different frames are different colours. They were the
                    // same colour until 2026-11-16, because the hue was the capture's index
                    // *within its own frame* and both of these are index 0 — survivable while one
                    // frame's captures were tinted, not survivable now the tint reaches two
                    // generations and the variables pane lists every frame at once.
                    // Read inside the predicate, because a query only resolves while a step runs.
                    play.expect(play.querySelector(CAPTURE_LINE).computedStyle("--hue"))
                            .toSatisfy("a colour of its own, not the key capture's",
                                    hue -> !hue.equals(
                                            play.querySelector(CAPTURE_KEY).computedStyle("--hue").get()));
                    // Three binding generations down is past the cap, so it is drawn nothing at
                    // all — not a fainter tint, nothing. Colouring an IP address and then
                    // recolouring each of its octets costs the first colouring its meaning, and
                    // the cap is what stops that; this capture becoming invisible until the reader
                    // descends to it is the accepted price (design 18 §5.11.1).
                    play.expect(play.querySelector(CAPTURE_PAST_CAP)).toBeNull();
                })

                // With the cursor on a line, the second channel appears: that line's own captures
                // are ruled, and the captures beneath it keep the tint and lose the rule. The two
                // channels disagreeing over a run of text *is* the information — tinted but not
                // ruled means "a capture, but not one of yours" (design 18 §5.11.2).
                .story("InputPaneRuleAlone", context -> renderPaneOnly(context, 2))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.querySelector(CAPTURE_RULED)).not().toBeNull());
                    final Query line = play.querySelector(CAPTURE_LINE);
                    play.expect(line).toHaveClass("mark--ruled");
                    play.expect(line.computedStyle("border-bottom-style")).toEqual("solid");
                    // The width every mark carries, ruled or not: nothing in the pane changes a
                    // rule's width, so the one property that animates is its colour.
                    play.expect(line.computedStyle("border-bottom-width")).toEqual("2px");
                    // Drawn in the capture's own hue rather than left transparent: where a capture
                    // is made only of whitespace the rule is the entire mark, so it has to clear
                    // WCAG 1.4.11's 3:1 on its own. At 50% of the hue it did not — 2.05:1 on the
                    // weakest generated hue against the light theme's control surface — which is
                    // why it is drawn at 75%.
                    play.expect(line.computedStyle("border-bottom-color"))
                            .not().toEqual("rgba(0, 0, 0, 0)");
                    // Square, not rounded: the 2px corner radius the mark carried was shaping a
                    // fill and then an outline, and with neither left it only tapered the ends
                    // of the rule — a mark trailing off rather than stopping where its bytes do.
                    play.expect(line.computedStyle("border-bottom-left-radius")).toEqual("0px");
                    // A capture from the pair beneath: tinted, unruled, and not faded for it
                    final Query key = play.querySelector(CAPTURE_KEY);
                    play.expect(key).not().toHaveClass("mark--ruled");
                    play.expect(key.computedStyle("border-bottom-color")).toEqual("rgba(0, 0, 0, 0)");
                    play.expect(key.computedStyle("opacity")).toEqual("1");
                    // Nothing is pointed at, so no rule is faded for anything.
                    play.expect(play.querySelector(CONTENT_BLOCK)).not().toHaveClass("pointing");
                })

                // Pointing at a ruled capture thickens its rule. The rule carries the hover
                // because the rule is the channel the pointer is asking about; the variables
                // pane's row lights at the same time, which is the `highlight` vocabulary and has
                // no pane to light here.
                //
                // A story of its own rather than the tail of the one above, because the
                // screenshot is taken once the play has ended and a hover is still in force then:
                // asserting rest and then hover in one story leaves no picture of the pane at
                // rest, which is the state it spends its life in.
                .story("InputPaneRulePointedAt", context -> renderPaneOnly(context, 2))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.querySelector(CAPTURE_RULED)).not().toBeNull());
                    final Query line = play.querySelector(CAPTURE_LINE);
                    // Drawn at 75% of its hue at rest, so that a whitespace-only capture's rule
                    // clears WCAG 1.4.11 on its own; pointing at it brings it to full strength.
                    // Kept rather than compared to a literal, because what the browser reports a
                    // colour-mix as is the browser's business, not this story's.
                    final String[] atRest = new String[1];
                    play.expect(line.computedStyle("border-bottom-color"))
                            .toSatisfy("drawn, at less than full strength", colour -> {
                                atRest[0] = String.valueOf(colour);
                                return !"rgba(0, 0, 0, 0)".equals(colour);
                            });
                    play.hover(line);
                    play.waitFor(() -> play.expect(line.computedStyle("border-bottom-color"))
                            .toSatisfy("stronger than it was at rest",
                                    colour -> !colour.equals(atRest[0])
                                              && !"rgba(0, 0, 0, 0)".equals(colour)));
                    play.expect(line.computedStyle("border-bottom-width")).toEqual("2px");
                    // Said by the block as well as by the mark, because the other rules fade
                    // and CSS cannot ask "is some other span hot" from a span (§5.12.3).
                    play.expect(play.querySelector(CONTENT_BLOCK)).toHaveClass("pointing");
                })

                // One rule on screen at a time: with the cursor on a pair, its key and its value
                // are both ruled, and pointing at the key fades the value's rule out. This is
                // the whole hover vocabulary now — the outline every mark drew on hover is gone,
                // because it reached 3px beyond the inline box, broke into a closed box per line
                // wherever a mark wrapped, and was drawn by CSS `:hover` on every ancestor of
                // the pointed-at element as well (design 18 §5.12.3, ruled 2026-11-17).
                .story("InputPaneRuleFaded", context -> renderPaneOnly(context, 4))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.querySelector(CAPTURE_VALUE)).not().toBeNull());
                    final Query key = play.querySelector(CAPTURE_KEY);
                    final Query value = play.querySelector(CAPTURE_VALUE);
                    play.expect(value).toHaveClass("mark--ruled");
                    play.expect(value.computedStyle("border-bottom-color"))
                            .not().toEqual("rgba(0, 0, 0, 0)");
                    // Both are ruled at rest, so "the key has a colour" proves nothing here:
                    // what pointing does is bring its rule to full strength and take the other's
                    // away.
                    final String[] keyAtRest = new String[1];
                    play.expect(key.computedStyle("border-bottom-color"))
                            .toSatisfy("ruled at rest", colour -> {
                                keyAtRest[0] = String.valueOf(colour);
                                return !"rgba(0, 0, 0, 0)".equals(colour);
                            });
                    play.hover(key);
                    play.waitFor(() -> play.expect(key.computedStyle("border-bottom-color"))
                            .toSatisfy("stronger than it was at rest",
                                    colour -> !colour.equals(keyAtRest[0])
                                              && !"rgba(0, 0, 0, 0)".equals(colour)));
                    // Still the current level's — the rule's meaning is untouched, it is simply
                    // not being drawn while another one is being asked about.
                    play.expect(value).toHaveClass("mark--ruled");
                    // Waited for, not read straight off: the fade is a .15s transition, so the
                    // line after the hover catches it part-way down.
                    play.waitFor(() -> play.expect(value.computedStyle("border-bottom-color"))
                            .toEqual("rgba(0, 0, 0, 0)"));
                })

                // ...and a capture from below the cursor, which has no rule at rest, is given
                // one while it is pointed at (ruled 2026-11-17). "The rule means this one is
                // yours" has to hold at rest; while the pointer is on something, the hot rule is
                // the only rule on screen and plainly means "this one". The alternative was a
                // hover that draws nothing on the thing under the pointer, which reads as not
                // having registered.
                .story("InputPaneDeepCapturePointedAt", context -> renderPaneOnly(context, 4))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.querySelector(CAPTURE_PAST_CAP)).not().toBeNull());
                    final Query deep = play.querySelector(CAPTURE_PAST_CAP);
                    play.expect(deep).not().toHaveClass("mark--ruled");
                    play.expect(deep.computedStyle("border-bottom-color")).toEqual("rgba(0, 0, 0, 0)");
                    play.hover(deep);
                    play.waitFor(() -> play.expect(deep.computedStyle("border-bottom-color"))
                            .not().toEqual("rgba(0, 0, 0, 0)"));
                    play.expect(deep.computedStyle("border-bottom-width")).toEqual("2px");
                    // and the current level's two rules are the ones that have gone quiet,
                    // once the .15s fade has run
                    play.waitFor(() -> play.expect(
                                    play.querySelector(CAPTURE_KEY).computedStyle("border-bottom-color"))
                            .toEqual("rgba(0, 0, 0, 0)"));
                    play.waitFor(() -> play.expect(
                                    play.querySelector(CAPTURE_VALUE).computedStyle("border-bottom-color"))
                            .toEqual("rgba(0, 0, 0, 0)"));
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

    // A match in the content pane, and a capture within one (Marks).
    // A match by the template that made it. The pane draws a mark for the cursor's immediate
    // children only (ContentPaneController: trace.children(cursor)), so at the top these are
    // the document's; pair's and junk_line's appear after descending into it.
    private static final String MARK_DOCUMENT =
            ".mark[data-tpl='00000000-0000-0000-0000-000000000001']";

    private static final String MARK_PAIR =
            ".mark[data-tpl='00000000-0000-0000-0000-000000000002']";

    private static final String MARK_JUNK =
            ".mark[data-tpl='00000000-0000-0000-0000-000000000003']";

    private static final String MARK_CAPTURE = ".mark--capture";

    // A capture of the current level, which carries the rule; one from beneath it carries only
    // the tint and so has no class of its own (design 18 §5.11, ruled 2026-11-16). One class
    // rather than a tier per level, because the marks no longer say how deep anything is.
    private static final String CAPTURE_RULED = ".mark--capture.mark--ruled";

    // Captures of the pane-only fixture by the frame that bound them: the line taken whole, a key
    // one frame below it, and the short host name three binding generations down.
    private static final String CAPTURE_LINE = ".mark--capture[data-cap='2:0']";

    private static final String CAPTURE_KEY = ".mark--capture[data-cap='4:0']";

    // Named for the cursor it was written against: three binding generations below the
    // document, so past the cap from there — and one generation below a pair, so drawn when the
    // cursor is on one. The cap re-bases as the cursor walks, which is the point of it.
    private static final String CAPTURE_PAST_CAP = ".mark--capture[data-cap='8:0']";

    // The value of the first pair, ruled alongside its key when the cursor is on that pair:
    // the second rule a story needs to show one fading while the other is pointed at.
    private static final String CAPTURE_VALUE = ".mark--capture[data-cap='4:1']";

    // The content pane's own block of marks, which carries `pointing` while one of them is
    // lit. Scoped to the pane: `.content-box` is an app-wide class, and within Shapeshifter the
    // pattern workbench's matches pane carries it as well.
    private static final String CONTENT_BLOCK = ".content-pane .content-box";

    // A capturing group in the pattern workbench's map of the pattern.
    private static final String REGEX_GROUP = ".regex__group";

    // A marked run in the pattern workbench's matches pane, which renders through Marks as the
    // content and output panes do. A match rather than a capture: the sample tints a capture
    // per regex group, and the canned preview declares no groups.
    private static final String SAMPLE_MARK = ".sample__matches .mark";

    // The templates of the pane-only fixture. Kept apart from the strict_kv ids above so that a
    // selector written for one fixture cannot quietly match the other.
    private static final String SOURCE_ID = "00000000-0000-0000-0000-000000000021";

    private static final String LINE_ID = "00000000-0000-0000-0000-000000000022";

    private static final String PAIR_ID = "00000000-0000-0000-0000-000000000023";

    private static final String HOST_ID = "00000000-0000-0000-0000-000000000024";

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

    /// The content pane by itself, over [#nestedTrace()]: no editor, no tabs, no panel — the one
    /// thing the pane's marks are for is being looked at, and everything else in the tab is in
    /// the way of seeing them.
    ///
    /// The pane is a controller with a host, so the story brings a host of its own rather than
    /// the document's. It is small because the pane asks little of it: a trace, a cursor, a
    /// sample source so the pane knows there is data, and a colour per template.
    ///
    /// @param context The story's context, which disposes the controller.
    /// @return The pane.
    private static Widget renderPaneOnly(final StoryContext context) {
        return renderPaneOnly(context, TraceModel.ROOT);
    }

    /// The pane by itself with the cursor on a chosen frame, since which frame is selected is
    /// what decides whether anything is ruled at all (design 18 §5.11.2).
    private static Widget renderPaneOnly(final StoryContext context, final long cursor) {
        final ContentPane pane = new ContentPane();
        final ContentPaneController controller =
                new ContentPaneController(new SimpleEventBus()).attach(pane);
        context.addCleanUp(controller::dispose);
        final PaneOnlyHost host = new PaneOnlyHost(new TraceModel(nestedTrace()), controller, cursor);
        controller.setHost(host);
        return controller.asWidget();
    }

    // Two lines, each taken whole by the template that matched it, each holding two pairs that
    // take a key and a value out of it, and one of those values holding a short host name. Three
    // binding generations is the point: with the cursor anywhere, two of them are tinted and the
    // third is drawn nothing, and with the cursor on a line exactly one of them is ruled. Built
    // in Java rather than as JSON because every offset here has to agree with every other, and a
    // constructor says which number is which where a wall of JSON does not.
    private static ShapeshifterTrace nestedTrace() {
        final String input = "host=web01 port=8080\nlevel=warn id=42\n";
        final List<Frame> frames = Arrays.asList(
                frame(1, 0, SOURCE_ID, "source", 0, 0, 0, input.length()),
                // "host=web01 port=8080\n" and "level=warn id=42\n"
                frame(2, 1, LINE_ID, "line", 0, 1, 0, 21),
                frame(3, 1, LINE_ID, "line", 1, 1, 21, 17),
                // the pairs within the first line, then within the second
                frame(4, 2, PAIR_ID, "pair", 0, 2, 0, 10),
                frame(5, 2, PAIR_ID, "pair", 1, 2, 11, 9),
                frame(6, 3, PAIR_ID, "pair", 0, 2, 0, 10),
                frame(7, 3, PAIR_ID, "pair", 1, 2, 11, 5),
                // and a template beneath the first pair that reads the host's short name out
                // of its value, so the pane has a capture three deep to leave unruled
                frame(8, 4, HOST_ID, "hostname", 0, 3, 5, 5));
        final List<Capture> captures = Arrays.asList(
                // Each line, taken whole: the first binding generation, since neither the
                // document nor the source frame above them binds anything
                capture(2, "line", "host=web01 port=8080", 0, 20),
                capture(3, "line", "level=warn id=42", 0, 16),
                // and the key and value inside each pair, one frame below
                capture(4, "key", "host", 0, 4),
                capture(4, "value", "web01", 5, 5),
                capture(5, "key", "port", 0, 4),
                capture(5, "value", "8080", 5, 4),
                capture(6, "key", "level", 0, 5),
                capture(6, "value", "warn", 6, 4),
                capture(7, "key", "id", 0, 2),
                capture(7, "value", "42", 3, 2),
                // inside the value capture of pair #0, which is itself inside the line capture:
                // the third binding generation, and so past the cap from any cursor above it
                capture(8, "short", "web", 0, 3));
        return new ShapeshifterTrace(true, true, input, "", frames, captures,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), 7L,
                Collections.emptyList(), Collections.emptyList(), 412_000L);
    }

    private static Frame frame(final long id, final long parentId, final String templateId,
                               final String templateName, final int matchIndex, final int depth,
                               final int contentOffset, final int contentLength) {
        // The content is left null so that TraceModel cuts it out of the parent's, which is the
        // only way these offsets can be wrong in the fixture and right on the screen.
        return new Frame(id, parentId, templateId, templateName, matchIndex, depth,
                contentOffset, contentLength, contentOffset, contentLength, null);
    }

    private static Capture capture(final long frameId, final String name, final String value,
                                   final int contentOffset, final int contentLength) {
        return new Capture(frameId, name, value, "scalar", contentOffset, contentLength);
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

    /// What the content pane needs of a host, and nothing else (design 18 §5.1). The editor's own
    /// host carries a project, a document, modes and a run; a pane shown by itself has none of
    /// those and would gain nothing from a mock of them.
    ///
    /// The one method with a body worth reading is [#hover(Hot)]. In the editor, pointing at a
    /// mark asks the host to light what the request names, and the host tells every surface;
    /// here the only surface is the pane, so the request goes straight back to it. That is what
    /// makes the hover reachable in a story at all — a real pointer's hover has lapsed by the
    /// time a screenshot is taken.
    private static final class PaneOnlyHost implements ProjectHost {

        private final TraceModel trace;
        private final ContentPaneController pane;
        private long cursor;

        private PaneOnlyHost(final TraceModel trace, final ContentPaneController pane, final long cursor) {
            this.trace = trace;
            this.pane = pane;
            this.cursor = cursor;
        }

        @Override
        public Project getProject() {
            // The pane never asks: it draws a trace, and takes its colours from colour(String).
            return null;
        }

        @Override
        public void replace(final Project project) {
            // Nothing here edits.
        }

        @Override
        public String colour(final String templateId) {
            // The palette by position, as the editor's host gives it, written out because
            // Colours.hue is not this package's to call.
            if (LINE_ID.equals(templateId)) {
                return "hsl(47, 62%, var(--mark__lightness))";
            }
            if (PAIR_ID.equals(templateId)) {
                return "hsl(94, 62%, var(--mark__lightness))";
            }
            return "hsl(0, 62%, var(--mark__lightness))";
        }

        @Override
        public void setColour(final String templateId, final String colour) {
            // Nothing here edits.
        }

        @Override
        public List<String> modes() {
            return Collections.emptyList();
        }

        @Override
        public void declareMode(final String mode) {
            // Nothing here edits.
        }

        @Override
        public void forgetMode(final String mode) {
            // Nothing here edits.
        }

        @Override
        public String getSample() {
            return trace.content(TraceModel.ROOT);
        }

        @Override
        public String getKeptSampleText() {
            return trace.content(TraceModel.ROOT);
        }

        @Override
        public SourceLocation getKeptSampleLocation() {
            return null;
        }

        @Override
        public SampleSource getSampleSource() {
            // Not null, or the pane shows its empty state instead of the trace.
            return SampleSource.pasted(trace.content(TraceModel.ROOT));
        }

        @Override
        public void setSampleSource(final SampleSource source) {
            // The sample is the fixture's.
        }

        @Override
        public void run() {
            // The trace is the fixture's; there is nothing to run.
        }

        @Override
        public TraceModel trace() {
            return trace;
        }

        @Override
        public boolean isStale() {
            return false;
        }

        @Override
        public long cursor() {
            return cursor;
        }

        @Override
        public void setCursor(final long frameId) {
            cursor = frameId;
            pane.refresh();
        }

        @Override
        public boolean canGoBack() {
            return false;
        }

        @Override
        public boolean canGoForward() {
            return false;
        }

        @Override
        public void goBack() {
            // There is nowhere to go back to.
        }

        @Override
        public void goForward() {
            // There is nowhere to go forward to.
        }

        @Override
        public void hover(final Hot hot) {
            pane.setHot(hot);
        }
    }
}
