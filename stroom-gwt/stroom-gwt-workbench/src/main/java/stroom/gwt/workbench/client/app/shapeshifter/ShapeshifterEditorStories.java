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
import com.google.gwt.dom.client.Element;
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
    static final String VALIDATION = """
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
    // "outputs" and "instructions" say what each frame wrote and what each top-level card of
    // its body wrote. They are measured on the sink's own position, so unlike a frame's content
    // offsets — which are relative to its parent's content — these are absolute in "output".
    // Spans nest: the source's covers both pairs', and each pair's covers its own five cards'.
    //
    // junk_line's single card is an emit-error and writes nothing, and it is here at length 0
    // on purpose. A frame that wrote no characters must not spend one of the two generations
    // the output pane tints (design 18 §5.13.1), and a fixture with nothing of the sort in it
    // could not tell whether the pane gets that right.
    static final String TRACE = """
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
      "groups": [], "attempts": [], "guards": [],
      "attemptsSeen": 3,
      "timings": [
        {"templateId": "00000000-0000-0000-0000-000000000002", "attempts": 3, "matched": 2, "nanos": 0},
        {"templateId": "00000000-0000-0000-0000-000000000003", "attempts": 1, "matched": 1, "nanos": 0}
      ],
      "outputs": [
        {"frameId": 1, "offset": 0, "length": 83, "unit": "BYTES"},
        {"frameId": 2, "offset": 10, "length": 31, "unit": "BYTES"},
        {"frameId": 3, "offset": 41, "length": 0, "unit": "BYTES"},
        {"frameId": 4, "offset": 41, "length": 31, "unit": "BYTES"}
      ],
      "instructions": [
        {"frameId": 1, "index": 0, "offset": 0, "length": 10, "unit": "BYTES"},
        {"frameId": 1, "index": 1, "offset": 10, "length": 62, "unit": "BYTES"},
        {"frameId": 1, "index": 2, "offset": 72, "length": 11, "unit": "BYTES"},
        {"frameId": 2, "index": 0, "offset": 10, "length": 12, "unit": "BYTES"},
        {"frameId": 2, "index": 1, "offset": 22, "length": 4, "unit": "BYTES"},
        {"frameId": 2, "index": 2, "offset": 26, "length": 2, "unit": "BYTES"},
        {"frameId": 2, "index": 3, "offset": 28, "length": 5, "unit": "BYTES"},
        {"frameId": 2, "index": 4, "offset": 33, "length": 8, "unit": "BYTES"},
        {"frameId": 3, "index": 0, "offset": 41, "length": 0, "unit": "BYTES"},
        {"frameId": 4, "index": 0, "offset": 41, "length": 12, "unit": "BYTES"},
        {"frameId": 4, "index": 1, "offset": 53, "length": 5, "unit": "BYTES"},
        {"frameId": 4, "index": 2, "offset": 58, "length": 2, "unit": "BYTES"},
        {"frameId": 4, "index": 3, "offset": 60, "length": 4, "unit": "BYTES"},
        {"frameId": 4, "index": 4, "offset": 64, "length": 8, "unit": "BYTES"}
      ],
      "messages": [
        {"severity": "warning", "text": "unrecognised line: # a comment the grammar never claimed",
         "frameId": 3}
      ],
      "runNanos": 412000
    }""";

    // ShapeshifterResource.preview() for the workbench's sample: the pair template tried alone
    // (Templates.experiment), so its matches are the frames at the root, each with its two groups
    // placed within it. The comment line is tried and not matched.
    static final String PAIR_TRIAL = """
    {
      "compiled": true, "sampleRead": true,
      "input": "host=web01\\n# a comment the grammar never claimed\\nlevel=warn\\n",
      "output": "",
      "frames": [
        {"id": 1, "parentId": 0, "templateId": "00000000-0000-0000-0000-000000000002",
         "templateName": "pair", "matchIndex": 1, "depth": 0,
         "inputOffset": 0, "inputLength": 11, "contentOffset": 0, "contentLength": 11},
        {"id": 2, "parentId": 0, "templateId": "00000000-0000-0000-0000-000000000002",
         "templateName": "pair", "matchIndex": 2, "depth": 0,
         "inputOffset": 49, "inputLength": 11, "contentOffset": 49, "contentLength": 11}
      ],
      "captures": [],
      "groups": [
        {"frameId": 1, "index": 1, "name": null, "contentOffset": 0, "contentLength": 4},
        {"frameId": 1, "index": 2, "name": null, "contentOffset": 5, "contentLength": 5},
        {"frameId": 2, "index": 1, "name": null, "contentOffset": 0, "contentLength": 5},
        {"frameId": 2, "index": 2, "name": null, "contentOffset": 6, "contentLength": 4}
      ],
      "attempts": [
        {"parentFrameId": 0, "templateId": "00000000-0000-0000-0000-000000000002", "matched": true,
         "inputOffset": 0, "contentOffset": 0, "nanos": 0},
        {"parentFrameId": 0, "templateId": "00000000-0000-0000-0000-000000000002", "matched": false,
         "inputOffset": 11, "contentOffset": 11, "nanos": 0},
        {"parentFrameId": 0, "templateId": "00000000-0000-0000-0000-000000000002", "matched": true,
         "inputOffset": 49, "contentOffset": 49, "nanos": 0}
      ],
      "guards": [], "instructions": [], "outputs": [], "messages": [], "timings": [],
      "attemptsSeen": 3, "runNanos": 1000
    }""";

    // ShapeshifterResource.patternInfo(): the pair template's regex, its two groups, each from
    // its '(' to just past its ')' (PatternInfo.Group)
    static final String PATTERN_INFO = """
            {
              "valid": true, "error": null,
              "groups": [
                {"index": 1, "name": null, "start": 0, "end": 8},
                {"index": 2, "name": null, "start": 9, "end": 17}
              ],
              "explain": "a lower-case key, '=', then everything to the end of the line"
            }""";

    // ShapeshifterResource.library(): what a pattern tree's ref may name
    static final String LIBRARY = """
            {"entries": [
              {"name": "ipv4", "regex": "(?:\\\\d{1,3}\\\\.){3}\\\\d{1,3}"},
              {"name": "iso8601", "regex": "\\\\d{4}-\\\\d{2}-\\\\d{2}T[\\\\d:.]+Z?"}
            ]}""";

    // ShapeshifterResource.encodings(): the canonical labels the source and template forms offer
    static final String ENCODINGS = """
            ["utf-8", "utf-16", "iso-8859-1", "ascii", "raw"]""";

    // ShapeshifterResource.print(): a pattern tree as the regex it means
    static final String PRINTED = """
            {"text": "([a-z]+)=([^\\\\n]*)\\\\n"}""";

    // DocPermissionResource.fetchDocumentUserPermissions(): an owner and a group that may view
    static final String DOC_PERMISSIONS = """
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
    static final String META_ROWS = """
            {
              "values": [
                {"meta": {"id": 42, "feedName": "TEST_FEED", "typeName": "Raw Events",
                  "status": "UNLOCKED", "createMs": 1700000000000}}
              ],
              "pageResponse": {"offset": 0, "length": 1, "total": 1, "exact": true}
            }""";

    private static final RestFixtures FIXTURES = TreeFixtures.explorerRoutes(
                    new ExplorerFixture(ExplorerFixture.folder("System",
                            ExplorerFixture.doc("Key Value Lines", ShapeshifterDoc.TYPE))))
            .get("/shapeshifter/v1/ss-strict-kv", RestReply.json(DOC))
            .post("/shapeshifter/v1/validate", RestReply.json(VALIDATION))
            .post("/shapeshifter/v1/preview", ShapeshifterEditorStories::preview)
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
            .post("/shapeshifter/v1/preview", ShapeshifterEditorStories::preview)
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

                    // A pane below follows too: the body offers nowhere to add a card read only
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(addLink(play)).toBeInTheDocument());

                    play.run("lock the document", () -> readAgain(true));
                    play.expect(play.findByRole("button", "Save (read only)")).toBeInTheDocument();
                    play.expect(play.getByText("Read only", ".link-tab-panel__read-only-note")).toBeVisible();
                    play.waitFor(() -> play.expect(play.queryByTitle(ADD_LINK)).toBeNull());
                    // The panel is still drawn: locking narrows what may be changed, it doesn't
                    // empty the tab
                    play.expect(play.getByText("pair", TEMPLATE_NAME)).toBeInTheDocument();

                    play.run("unlock the document", () -> readAgain(false));
                    play.expect(play.findByRole("button", "Save")).toBeInTheDocument();
                    play.expect(play.queryByText("Read only", ".link-tab-panel__read-only-note")).toBeNull();
                    play.waitFor(() -> play.expect(addLink(play)).toBeInTheDocument());
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
                    play.waitFor(() -> play.expect(play.getByText("Match", STRIP_KEY))
                            .toBeInTheDocument());
                    play.expect(play.getByText("Match is", STRIP_KEY)).toBeInTheDocument();
                    play.expect(play.getByText("Guard", STRIP_KEY)).toBeInTheDocument();
                    // The match chip carries the pattern, and opens the workbench
                    play.expect(play.querySelector(MATCH_CHIP)).not().toBeNull();
                    // Declarations and captures are sections of the template, above its body
                    play.expect(play.getByText("Declarations", STRIP_KEY)).toBeInTheDocument();
                    play.expect(play.getByText("Captures", STRIP_KEY)).toBeInTheDocument();
                    expectNoProblems(play);
                })
                // The body of the chosen template: one card per instruction, as the project says
                .story("TemplateBody", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByText("Body", STRIP_KEY))
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
                    // Marked by the card that wrote it, two generations deep (design 18 §5.13.1):
                    // the source's three cards, and the five cards of each pair inside the
                    // second of them. The cursor is the document, which has no body of its own,
                    // so nothing here is ruled — the rule says "this card is one of yours".
                    play.waitFor(() -> play.expect(play.querySelector(OUTPUT_SOURCE_CARD))
                            .not().toBeNull());
                    play.expect(play.querySelector(OUTPUT_PAIR_CARD)).not().toBeNull();
                    play.expect(play.querySelector(OUTPUT_RULED)).toBeNull();
                    // Two cards of different templates are different colours. hue(index) for
                    // both would have made the source's card 0 and a pair's card 0 one colour,
                    // which is the defect CaptureHues was written to fix in the other pane.
                    play.expect(play.querySelector(OUTPUT_SOURCE_CARD).computedStyle("--hue"))
                            .toSatisfy("a colour of its own, not the pair's first card's",
                                    hue -> !hue.equals(play.querySelector(OUTPUT_PAIR_CARD)
                                            .computedStyle("--hue").get()));
                    expectNoProblems(play);
                })
                // A lock change on its own, with no re-read: the panes are told through the
                // read-only context, not by being filled again (UI-GUIDE.md: a controller calls
                // setReadOnly from the context it watches)
                .story("LockChangeWithoutReread", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(addLink(play)).toBeInTheDocument());
                    play.run("lock the document, without reading it again", () ->
                            host().setReadOnly(true));
                    // The body pane below the document follows the lock on its own
                    play.waitFor(() -> play.expect(play.queryByTitle(ADD_LINK)).toBeNull());
                    play.run("unlock the document, without reading it again", () ->
                            host().setReadOnly(false));
                    play.waitFor(() -> play.expect(addLink(play)).toBeInTheDocument());
                    expectNoProblems(play);
                })

                // The match line's workbench opens the pattern workbench over the navigator's rows
                .story("PatternWorkbench", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.querySelector(MATCH_CHIP)).not().toBeNull());
                    play.click(workbenchButton(play));
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

                // A capture binds a group of the match to a name the body can read. Its dialog
                // is opened from the captures section of the chosen template
                .story("CaptureDialogOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    selectTemplate(play, "pair");
                    play.click(play.findByRole("button", "+ capture"));
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
                    play.click(play.findByRole("button", "+ capture"));
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
                    play.click(play.findByRole("button", "+ declaration"));
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
                    play.click(play.findByRole("button", "+ declaration"));
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
                // The input, the variables and the output are each under a title bar that folds
                // the pane away (design 47 E). A folded pane is its bar, and the room it had goes
                // to the panes that are open: to the input while it is, then to the output
                .story("DataPanesFoldAway", context -> render(context, false))
                .withPlay(play -> {
                    final Query input = play.getByRole("button", StroomDom.button("Input"));
                    final Query variables = play.getByRole("button", StroomDom.button("Variables"));
                    final Query output = play.getByRole("button", StroomDom.button("Output"));
                    // Run, then the pair, so that its captures are in scope and the variables
                    // open by themselves (design 48 C6)
                    play.waitFor(() -> play.expect(runButton(play)).toBeVisible());
                    play.click(runButton(play));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_DOCUMENT)).not().toBeNull());
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(variables).toHaveAttribute("aria-expanded", "true"));
                    play.waitFor(() -> play.expect(input).toHaveAttribute("aria-expanded", "true"));
                    play.expect(play.querySelector(".content-pane")).toBeVisible();
                    final int[] before = {0, 0};
                    play.run("measure the input and the output", () -> {
                        before[0] = play.querySelector(".content-pane").element().get().getOffsetHeight();
                        before[1] = play.querySelector(".output-pane").element().get().getOffsetHeight();
                    });

                    // The variables folded: the input, which takes the rest, has their room
                    play.click(variables);
                    play.expect(variables).toHaveAttribute("aria-expanded", "false");
                    play.expect(play.querySelector(".variables-pane")).not().toBeVisible();
                    play.waitFor(() -> play.expect("the input has the variables' room",
                            () -> play.querySelector(".content-pane").element().get().getOffsetHeight()
                                  > before[0]).toEqual(true));

                    // The input folded too: now it is the output that takes the rest
                    play.click(input);
                    play.expect(input).toHaveAttribute("aria-expanded", "false");
                    play.expect(play.querySelector(".content-pane")).not().toBeVisible();
                    play.waitFor(() -> play.expect("the output has the input's room",
                            () -> play.querySelector(".output-pane").element().get().getOffsetHeight()
                                  > before[1]).toEqual(true));

                    // All three: three bars, and nothing lost when they are opened again
                    play.click(output);
                    play.expect(play.querySelector(".output-pane")).not().toBeVisible();
                    play.click(input);
                    play.click(variables);
                    play.click(output);
                    play.waitFor(() -> play.expect(play.querySelector(".content-pane")).toBeVisible());
                    play.expect(play.querySelector(".variables-pane")).toBeVisible();
                    play.expect(play.querySelector(".output-pane")).toBeVisible();
                    play.waitFor(() -> play.expect("the output is the size it was",
                            () -> Math.abs(play.querySelector(".output-pane").element().get()
                                    .getOffsetHeight() - before[1]) <= 1).toEqual(true));
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    expectNoProblems(play);
                })
                // The messages are under the work, not under the panel, and fold away under a
                // title bar as the data panes do. The bar says how many there are, so it still
                // says there is something to read when they are folded
                .story("MessagesFoldAway", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    final Query bar = play.getByRole("button", StroomDom.button("Messages · 1"));
                    play.waitFor(() -> play.expect(bar).toHaveAttribute("aria-expanded", "true"));
                    final Query message = play.getByText(TextMatch.containing("unrecognised line"));
                    play.expect(message).toBeVisible();
                    final int[] before = {0};
                    play.run("measure the template", () -> before[0] =
                            play.querySelector(".template-strip").element().get().getOffsetHeight());
                    play.click(bar);
                    play.expect(bar).toHaveAttribute("aria-expanded", "false");
                    play.expect(message).not().toBeVisible();
                    play.waitFor(() -> play.expect("the work has the messages' room",
                            () -> play.querySelector(".template-strip").element().get().getOffsetHeight()
                                  > before[0]).toEqual(true));
                    play.click(bar);
                    play.waitFor(() -> play.expect(message).toBeVisible());
                    expectNoProblems(play);
                })
                // Two of the three folded, and left so: the input with the whole column but two
                // bars, which is what a long sample is read in
                .story("DataPanesFolded", context -> render(context, false))
                .withPlay(play -> {
                    final Query variables = play.getByRole("button", StroomDom.button("Variables"));
                    final Query output = play.getByRole("button", StroomDom.button("Output"));
                    // Run, then the pair, so that its captures are in scope and the variables open
                    play.waitFor(() -> play.expect(runButton(play)).toBeVisible());
                    play.click(runButton(play));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_DOCUMENT)).not().toBeNull());
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(variables).toHaveAttribute("aria-expanded", "true"));
                    final int[] before = {0};
                    play.run("measure the input", () -> before[0] =
                            play.querySelector(".content-pane").element().get().getOffsetHeight());
                    play.click(variables);
                    play.click(output);
                    play.expect(play.querySelector(".variables-pane")).not().toBeVisible();
                    play.expect(play.querySelector(".output-pane")).not().toBeVisible();
                    play.expect(play.querySelector(".content-pane")).toBeVisible();
                    // Everything the other two had, less their bars
                    play.waitFor(() -> play.expect("the input has the room of both",
                            () -> play.querySelector(".content-pane").element().get().getOffsetHeight()
                                  > before[0] + 150).toEqual(true));
                    expectNoProblems(play);
                })
                // A line typed into the card the body's add link opens adds the cards it describes (design
                // 47 A): text is a text card, `$1` a value-of card, and a line that mixes them
                // is split into the sequence. And the half of this that no assertion about cards
                // can show: **a line being typed survives the body being drawn again.** A run's
                // trace lands a moment after every edit and redraws the cards to say what each
                // wrote — so what is typed, and the keyboard, have to be carried across or the
                // run of the last card wipes the next.
                .story("ALineAddsCardsAndSurvivesARun", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "junk_line");
                    play.waitFor(() -> play.expect(addLink(play)).toBeInTheDocument());
                    // junk_line arrives with one card, its emit-error
                    play.expect(play.querySelectorAll(CARD_KIND).textContents())
                            .toEqual(Arrays.asList("emit-error"));

                    // A templated line, split: text, value-of, text
                    Walkthrough.enter(play, "<junk>$0</junk>");
                    play.waitFor(() -> play.expect(play.querySelectorAll(CARD_KIND).textContents())
                            .toEqual(Arrays.asList("emit-error", "text", "value-of", "text")));

                    // Half a line, into the card that opened after those, and then the run that
                    // edit set off lands. The first row on screen afterwards is a different
                    // element — which is what proves the cards were drawn again — and the line
                    // holds the same text and the keyboard.
                    final Spy requests = play.spy(ScreenHarness.REQUEST_SPY);
                    final int[] runs = {0};
                    final Element[] before = {null};
                    play.keyboard("costs $$");
                    play.run("note the first row and the runs so far", () -> {
                        before[0] = play.getByText("emit-error", CARD_KIND).element().get();
                        runs[0] = requests.getCalls().size();
                    });
                    play.waitFor(() -> play.expect("a run since the line was started",
                            () -> requests.getCalls().size() > runs[0]).toEqual(true));
                    play.waitFor(() -> play.expect("the rows were drawn again",
                            () -> play.getByText("emit-error", CARD_KIND).element().get()
                                  != before[0]).toEqual(true));
                    play.expect(play.getByLabelText(Walkthrough.ENTRY).value()).toEqual("costs $$");
                    play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus();

                    // ...so the line can be finished where it was left. Two dollars write one,
                    // and a line with no reference in it is one text card.
                    play.keyboard("5{Enter}");
                    play.waitFor(() -> play.expect(play.querySelectorAll(CARD_KIND).count())
                            .toEqual(5));
                    play.expect(play.getByText(TextMatch.containing("costs $5"))).toBeInTheDocument();
                    expectNoProblems(play);
                })
                // The add link opens a card whose kind is left to what is typed; choosing a kind
                // makes it that kind's editor, in place (design 47 D), and OK takes it
                .story("NewCardOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(addLink(play)).toBeInTheDocument());
                    newCard(play, screen, "text");
                    final Play card = cardEditor(play);
                    // In the list, where the card will be, and not over it: there is no dialog
                    play.expect(screen.queryByText(TextMatch.regex("text", "i"), StroomDom.DIALOG_TITLE))
                            .toBeNull();
                    // The link it was opened from has given way to it
                    play.expect(play.queryByTitle(ADD_LINK)).toBeNull();
                    play.waitFor(() -> play.expect(card.getByLabelText("Text")).toBeVisible());
                    card.type(card.getByLabelText("Text"), "hello");
                    // Nothing has reached the project yet: what the card holds is a draft
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    play.click(card.getByRole("button", StroomDom.button("OK")));
                    // A card for it joins the body, and the document has work to save
                    play.waitFor(() -> play.expect(play.getAllByText("text", CARD_KIND).count())
                            .toSatisfy("a fourth text card", count -> ((Integer) count) == 4));
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    // and no other opens after it: a card of a chosen kind is a form filled in, not
                    // a line typed, and an empty line after it was left open on every finished
                    // body (design 48 C10). The add link is back for the next.
                    play.waitFor(() -> play.expect(play.queryByLabelText(Walkthrough.ENTRY)).toBeNull());
                    play.expect(addLink(play)).toBeInTheDocument();
                    expectNoProblems(play);
                })
                // Cancel adds no card and leaves the document clean
                .story("NewCardCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(addLink(play)).toBeInTheDocument());
                    newCard(play, screen, "text");
                    final Play card = cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Text")).toBeVisible());
                    card.type(card.getByLabelText("Text"), "hello");
                    play.click(card.getByRole("button", StroomDom.button("Cancel")));
                    expectNoCardOpen(play);
                    // The pair template's three text cards, as the fixture has them
                    play.expect(play.getAllByText("text", CARD_KIND).count())
                            .toSatisfy("still three text cards", count -> ((Integer) count) == 3);
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    // The link is back, and the keyboard is on it
                    play.waitFor(() -> play.expect(addLink(play)).toHaveFocus());
                    expectNoProblems(play);
                })
                // Extracting a node to the library asks for the new part's name: the name
                // prompt, which the tree's extract and wrap share. (Adding a part from the
                // panel names it where it is shown, design 47 §7.2, finding 15.)
                .story("NamePromptOk", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    final Play dialog = extractTag(play);
                    dialog.type(dialog.getByRole("textbox").first(), "OPEN");
                    play.click(dialog.getByRole("button", StroomDom.button("OK")));
                    // The part is in the panel's library section, and the node names it
                    play.waitFor(() -> play.expect(play.getByText("OPEN", TEMPLATE_NAME))
                            .toBeInTheDocument());
                    expectNoProblems(play);
                })
                // Cancelling the prompt makes no part
                .story("NamePromptCancel", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    final Play dialog = extractTag(play);
                    dialog.type(dialog.getByRole("textbox").first(), "OPEN");
                    play.click(dialog.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText(TextMatch.regex("Extract to Library", "i"),
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.queryByText("OPEN", TEMPLATE_NAME)).toBeNull();
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
                .story("TranslateCard", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("translate", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(cardLine(play, "translate"));
                    final Play card = cardEditor(play);
                    // A form, not the wire form it used to open as
                    play.waitFor(() -> play.expect(card.getByLabelText("Select")).toBeVisible());
                    play.expect(card.getByLabelText("Name")).toHaveValue("escaped");
                    // One line per replacement, the newline written as \n because the field
                    // cannot hold the character itself
                    play.expect(card.getByLabelText(TextMatch.containing("Replacements")))
                            .toHaveValue("& = &amp;\n< = &lt;\n\\n =  ");
                    play.click(card.getByRole("button", StroomDom.button("Cancel")));
                    expectNoProblems(play);
                })
                // The eight transforms that are one shape share one form (design 46 §8.2). This
                // one has no name, which is the commonest case in the corpus — 42 of its 55
                // trims — and means the result is written straight out rather than bound
                .story("TransformCard", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("trim", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(cardLine(play, "trim"));
                    final Play card = cardEditor(play);
                    // A form, not the wire form it used to open as
                    play.waitFor(() -> play.expect(
                            card.getByLabelText(TextMatch.containing("Select"))).toBeVisible());
                    play.expect(card.getByLabelText(TextMatch.containing("Select")))
                            .toHaveValue("escaped");
                    // Blank, and that is the configuration rather than something missing
                    play.expect(card.getByLabelText("Name")).toHaveValue("");
                    play.click(card.getByRole("button", StroomDom.button("Cancel")));
                    expectNoProblems(play);
                })
                // OK writes what the fields hold back into the project, which is the half a
                // Cancel story never reaches (UI-GUIDE.md): the name is typed, kept, and read
                // back when the card is opened again
                .story("TransformCardOk", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("trim", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(cardLine(play, "trim"));
                    final Play naming = cardEditor(play);
                    play.waitFor(() -> play.expect(naming.getByLabelText("Name")).toBeVisible());
                    play.type(naming.getByLabelText("Name"), "tidied");
                    play.click(naming.getByRole("button", StroomDom.button("OK")));
                    expectNoCardOpen(play);
                    // Opened again, the form holds what was saved — and the select with it
                    play.click(cardLine(play, "trim"));
                    final Play again = cardEditor(play);
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
                    play.click(cardLine(play, "replace"));
                    final Play replacing = cardEditor(play);
                    play.waitFor(() -> play.expect(replacing.getByLabelText("Find"))
                            .toHaveValue(" +"));
                    play.expect(replacing.getByLabelText("Replace with")).toHaveValue(" ");
                    play.expect(replacing.getByLabelText("Name")).toHaveValue("squashed");
                    play.click(replacing.getByRole("button", StroomDom.button("Cancel")));
                    // The next card is opened once this one has closed, so that what is read
                    // next is the card asked for and not the one being put away
                    expectNoCardOpen(play);

                    play.click(cardLine(play, "decode"));
                    final Play decoding = cardEditor(play);
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
                    play.click(cardLine(play, "divide"));
                    final Play card = cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
                    play.expect(card.getByLabelText("Left")).toBeVisible();
                    expectNoProblems(play);
                })
                // add: a fold: its inputs are a list because there may be any number
                .story("FormAdd", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("add", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(cardLine(play, "add"));
                    final Play card = cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
                    expectNoProblems(play);
                })
                // tokenize: select, the one word the kind turns on, and a name
                .story("FormTokenize", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("tokenize", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(cardLine(play, "tokenize"));
                    final Play card = cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
                    play.expect(card.getByLabelText("Delimiter")).toBeVisible();
                    expectNoProblems(play);
                })
                // substring: a start and a length, either of which may be left out
                .story("FormSubstring", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("substring", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(cardLine(play, "substring"));
                    final Play card = cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
                    play.expect(card.getByLabelText("Start")).toBeVisible();
                    expectNoProblems(play);
                })
                // format-date: a pattern and a time zone
                .story("FormFormatDate", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("format-date", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(cardLine(play, "format-date"));
                    final Play card = cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
                    play.expect(card.getByLabelText("Pattern")).toBeVisible();
                    expectNoProblems(play);
                })
                // parse-date: the only kind needing five fields, which is why there are five
                .story("FormParseDate", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("parse-date", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(cardLine(play, "parse-date"));
                    final Play card = cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
                    play.expect(card.getByLabelText("Relative to")).toBeVisible();
                    expectNoProblems(play);
                })
                // string-join: several values and what goes between them
                .story("FormStringJoin", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("string-join", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(cardLine(play, "string-join"));
                    final Play card = cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
                    play.expect(card.getByLabelText("Separator")).toBeVisible();
                    expectNoProblems(play);
                })
                // call: a function and its arguments, of which there may be none
                .story("FormCall", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    selectTemplate(play, "branch");
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText("call", CARD_KIND))
                            .toBeInTheDocument());
                    play.click(cardLine(play, "call"));
                    final Play card = cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
                    play.expect(card.getByLabelText("Function")).toBeVisible();
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
                    // The output pane descended too, and now has a level to call its own: the
                    // source's three cards are ruled and the pairs' cards beneath them are not
                    // (design 18 §5.13.2). The apply-templates card is the one worth looking at
                    // — its colour is overwritten everywhere by the cards of the matches it
                    // dispatched, and its extent is still legible because the rule is a channel
                    // of its own.
                    play.waitFor(() -> play.expect(play.querySelector(OUTPUT_RULED)).not().toBeNull());
                    play.expect(play.querySelector(OUTPUT_SOURCE_CARD)).toHaveClass("mark--ruled");
                    play.expect(play.querySelector(OUTPUT_PAIR_CARD)).not().toHaveClass("mark--ruled");
                    expectNoProblems(play);
                })

                // The crumb's states, which it used to answer with one blue ring apiece: hover
                // drew the ring a focus ring draws, the cursor's own crumb had no mark at all,
                // and the box that holds the crumbs cut every ring off at the top. Ruled
                // 2026-11-17: ARIA and pseudo-classes, as the stepping screen's path bar has
                // them (design 18 §5.12.1).
                .story("BreadcrumbStates", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.click(runButton(play));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_DOCUMENT))
                            .not().toBeNull());
                    play.click(play.querySelector(MARK_DOCUMENT));
                    play.waitFor(() -> play.expect(play.querySelector(CRUMB_CURRENT))
                            .not().toBeNull());
                    // The box holding the crumbs hides its overflow, so it has to hand on the
                    // room a crumb draws outside itself: a focus ring's 1px line and 4px band.
                    // It used to be exactly the tallest crumb's border box, which is why the
                    // ring was cut off flat along the top. Geometry rather than a class name,
                    // because the clip was invisible to every assertion about classes.
                    play.expect(play.querySelector(CRUMB_CURRENT).top())
                            .toSatisfy("5px of room above and below the crumb", top -> {
                                final double above = (Double) top
                                        - play.querySelector(CRUMB_SEGMENTS).top().get();
                                final double below =
                                        play.querySelector(CRUMB_SEGMENTS).bottom().get()
                                        - play.querySelector(CRUMB_CURRENT).bottom().get();
                                return above >= 5 && below >= 5;
                            });
                    // Three fills, not one: where the cursor is, what is pointed at, and both.
                    // An ancestor under the pointer must never read as the selection moving,
                    // which is the whole reason the current crumb does not simply borrow the
                    // hovered fill.
                    play.expect(play.querySelector(CRUMB_CURRENT)
                                    .computedStyle("background-color"))
                            .toSatisfy("differs from an ancestor at rest", fill ->
                                    !fill.equals(play.querySelector(CRUMB_OUTER)
                                            .computedStyle("background-color").get()));
                    play.hover(play.querySelector(CRUMB_OUTER));
                    play.waitFor(() -> play.expect(play.querySelector(CRUMB_OUTER)
                            .computedStyle("background-color"))
                            .not().toEqual("rgba(0, 0, 0, 0)"));
                    play.expect(play.querySelector(CRUMB_OUTER)
                                    .computedStyle("background-color"))
                            .toSatisfy("a hovered ancestor is still not the cursor's own", fill ->
                                    !fill.equals(play.querySelector(CRUMB_CURRENT)
                                            .computedStyle("background-color").get()));
                    // ...and pointing draws no ring of its own. The outline was the channel the
                    // keyboard needs; hover answers in a fill.
                    play.expect(play.querySelector(CRUMB_OUTER).computedStyle("outline-style"))
                            .toEqual("none");
                    // A template's colour is a swatch beside the name, as it is on a body card
                    // and a variables row, and not a line under it: a line under a thing means
                    // "selected" on the tabs one row up, and the crumb drew one under every
                    // crumb, always. The swatch sits outside the name's own box, so the fills
                    // above never wash over it.
                    play.expect(play.querySelector(CRUMB_SWATCH)).not().toBeNull();
                    play.expect(play.querySelector(CRUMB_CURRENT)
                            .computedStyle("border-bottom-width")).toEqual("0px");
                    expectNoProblems(play);
                })

                // The variables pane says "nothing" once, not once a frame (ruled 2026-11-17).
                // Descended into the source frame, the path is the document and the source, and
                // neither binds a name: the pane used to print "this pattern bound nothing"
                // above "the document binds nothing", two levels of nothing stacked up, which
                // reads as a pane that has failed rather than as a frame that genuinely binds
                // nothing.
                .story("VariablesPaneWithNothingInScope", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.click(runButton(play));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_DOCUMENT))
                            .not().toBeNull());
                    play.click(play.querySelector(MARK_DOCUMENT));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_PAIR)).not().toBeNull());
                    // Nothing in scope: the pane is folded away, saying so once if opened, and
                    // not once a frame (design 48 C6)
                    final Query variables = play.getByRole("button", StroomDom.button("Variables"));
                    play.waitFor(() -> play.expect(variables).toHaveAttribute("aria-expanded", "false"));
                    play.expect(play.getByText(NOTHING_IN_SCOPE)).toBeInTheDocument();
                    play.expect(play.queryByText("the document binds nothing")).toBeNull();
                    play.expect(play.queryByText("this pattern bound nothing")).toBeNull();
                    // ...and the sections come back whole the moment any frame on the path binds
                    // one. A section saying it bound nothing is right beside a section that
                    // bound something — that is a fact about one scope among several — and
                    // wrong only when it is the whole pane.
                    // ...and opens by itself once something is
                    play.click(play.querySelector(MARK_PAIR));
                    play.waitFor(() -> play.expect(variables).toHaveAttribute("aria-expanded", "true"));
                    play.waitFor(() -> play.expect(play.getByText("$key", VARIABLES_NAME))
                            .toBeVisible());
                    play.expect(play.queryByText(NOTHING_IN_SCOPE)).toBeNull();
                    play.expect(play.getByText("the document binds nothing")).toBeVisible();
                    expectNoProblems(play);
                })

                // Pointing at output written by a card *below* the cursor lights the row of the
                // template that wrote it (design 18 §5.13.4). For the cursor's own cards the body
                // panel lights the card itself and says everything; for a deeper one the body
                // panel is showing the cursor's template, so without this nothing on screen would
                // say whose card is under the pointer.
                //
                // A story of its own, because the screenshot is taken once the play has ended
                // and a hover is still in force then.
                .story("OutputPaneDeepCardPointedAt", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.click(runButton(play));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_DOCUMENT))
                            .not().toBeNull());
                    play.click(play.querySelector(MARK_DOCUMENT));
                    play.waitFor(() -> play.expect(play.querySelector(OUTPUT_PAIR_CARD))
                            .not().toBeNull());
                    play.expect(play.getByText("pair", TEMPLATE_NAME).closest(TEMPLATE_ROW))
                            .not().toHaveClass("highlight");
                    play.hover(play.querySelector(OUTPUT_PAIR_CARD));
                    play.waitFor(() -> play.expect(play.getByText("pair", TEMPLATE_NAME)
                            .closest(TEMPLATE_ROW)).toHaveClass("highlight"));
                    // and not the template whose body is the one on screen
                    play.expect(play.getByText("junk_line", TEMPLATE_NAME).closest(TEMPLATE_ROW))
                            .not().toHaveClass("highlight");
                    expectNoProblems(play);
                })

                // A pane that is redrawn under a stationary pointer is told again what is
                // pointed at. Descending is the common way in: the click refreshes every pane
                // with the pointer still on the mark it was clicking, the Html is replaced so
                // the `highlight` on it is gone, and the browser fires no `mouseover` for a
                // pointer standing still — so without the re-light the thing under the pointer
                // goes dark and stays dark until the pointer finds a different mark.
                //
                // The `:hover` rules used to cover this for free. Removing them (design 18
                // §5.12.3) left it to be done on purpose, and this is the regression test.
                .story("InputPaneStaysLitAcrossADescend", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.click(runButton(play));
                    play.waitFor(() -> play.expect(play.querySelector(EDITOR_CAPTURE_KEY))
                            .not().toBeNull());
                    play.hover(play.querySelector(EDITOR_CAPTURE_KEY));
                    play.waitFor(() -> play.expect(play.querySelector(EDITOR_CAPTURE_KEY))
                            .toHaveClass("highlight"));
                    // Clicking a capture descends into the match that holds it — the *source*
                    // frame, not the pair that bound the capture, because the pane marks only
                    // the cursor's direct children and the pair is a grandchild of the document.
                    // One level per click (§5.12.4), so the capture is still a generation below
                    // afterwards and still drawn, under the same key.
                    play.click(play.querySelector(EDITOR_CAPTURE_KEY));
                    play.waitFor(() -> play.expect(play.querySelector(OUTPUT_SOURCE_CARD))
                            .toHaveClass("mark--ruled"));
                    // ...and still lit, which is the whole point: the pane was redrawn under a
                    // pointer that never moved.
                    play.expect(play.querySelector(EDITOR_CAPTURE_KEY)).toHaveClass("highlight");
                    play.expect(play.querySelector(EDITOR_CAPTURE_KEY)).not().toHaveClass("mark--ruled");
                    expectNoProblems(play);
                })

                // Clicking output written by a card *below* the cursor descends — one level,
                // to the frame whose body ran it, which is what the reader is asking to stand
                // in (design 18 §5.13.3). The cursor is the document here and the clicked card
                // belongs to a pair two frames down, so this is also the test that the descent
                // goes one level rather than jumping to the frame that was clicked.
                .story("OutputPaneDeepCardClicked", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.click(runButton(play));
                    play.waitFor(() -> play.expect(play.querySelector(OUTPUT_PAIR_CARD))
                            .not().toBeNull());
                    // The document's own body writes nothing, so it spends no generation and
                    // the cards in reach belong to its grandchildren.
                    play.expect(play.querySelector(OUTPUT_RULED)).toBeNull();
                    play.click(play.querySelector(OUTPUT_PAIR_CARD));
                    // One level: the cursor is the source frame, whose three cards are now the
                    // ruled ones — not the pair whose card was clicked.
                    play.waitFor(() -> play.expect(play.querySelector(OUTPUT_SOURCE_CARD))
                            .toHaveClass("mark--ruled"));
                    play.expect(play.querySelector(OUTPUT_PAIR_CARD)).not().toHaveClass("mark--ruled");
                    expectNoProblems(play);
                })

                // ...and clicking output written by one of the cursor's *own* cards does not
                // descend, because there is no frame beneath a card to descend to (design 18
                // §5.13.3). It brings that card into view in the body panel instead, which on a
                // body of three cards is nothing at all — so what this story can prove is the
                // half that is observable: the cursor stays where it is.
                //
                // That is worth a story even so. The obvious wiring is to send every click
                // through `setCursor`, and under it this click would descend into whatever
                // `Marks.frameOf` found above the card — the cursor itself, or worse, past it.
                .story("OutputPaneOwnCardClicked", context -> render(context, false))
                .withPlay(play -> {
                    selectTemplate(play, "pair");
                    play.click(runButton(play));
                    play.waitFor(() -> play.expect(play.querySelector(MARK_DOCUMENT))
                            .not().toBeNull());
                    play.click(play.querySelector(MARK_DOCUMENT));
                    play.waitFor(() -> play.expect(play.querySelector(OUTPUT_SOURCE_CARD))
                            .toHaveClass("mark--ruled"));
                    play.click(play.querySelector(OUTPUT_SOURCE_CARD));
                    // Still the source's cards that are ruled, and still its body on show: a
                    // card is not a level, so clicking one moves nothing.
                    play.expect(play.querySelector(OUTPUT_SOURCE_CARD)).toHaveClass("mark--ruled");
                    play.expect(play.querySelector(OUTPUT_PAIR_CARD)).not().toHaveClass("mark--ruled");
                    // ...and the panel still has the source template selected, which it shows
                    // as "document" because that template is the one matching the source.
                    play.expect(play.getByText("document", TEMPLATE_NAME).closest(TEMPLATE_ROW))
                            .toHaveClass("template-row--selected");
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
                });
    }

    // Stroom's Ace editor's text area
    static final String ACE_INPUT = ".ace_text-input";

    // ShapeshifterResource.validate(): sent on every edit that has no sample to run over,
    // carrying the project the editor is holding. This, not the save, is where a story can read
    // what the UI built — see ShapeshifterAuthoringStories for why, and for what an edit sends
    // once there *is* a sample.
    static final String VALIDATE_PATH = "/shapeshifter/v1/validate";

    // Ace marks a read-only editor on its root
    private static final String ACE_READ_ONLY = ".ace_editor.ace_read-only";

    // A template's name in the panel's list, and the Data row's, which is a row too
    static final String TEMPLATE_NAME = ".template-row__name";

    // The row the name sits in, which is what carries `highlight` when the template is lit
    private static final String TEMPLATE_ROW = ".template-row";
    private static final String VARIABLES_NAME = ".variables-pane__name";
    private static final String CRUMB_SEGMENTS = ".breadcrumb__segments";
    private static final String CRUMB_SWATCH = ".breadcrumb__segment .swatch";
    static final String CRUMB_CURRENT = ".breadcrumb__name[aria-current=\"true\"]";
    private static final String CRUMB_OUTER =
            ".breadcrumb__name:not([aria-current=\"true\"])";
    private static final String NOTHING_IN_SCOPE = "No name is in scope at the selected match.";

    // The kind of instruction a body card holds ("text", "value-of", ...)
    static final String CARD_KIND = ".body-card__kind";

    // The strip labels each row with its key ("match", "match is", "guard", "body")
    private static final String STRIP_KEY = ".key-label";

    // An item in the list a SelectionBox opens (selection-item-cell.css). The list is a GWT
    // PopupPanel on the document body, so it is only reachable through play.screen().
    static final String SELECTION_ITEM = ".selection-item-cell__text";

    // The selected template's name, in the strip's head. The panel's own row for the document
    // template reads "document" whatever the template is called, so this is where a rename shows.
    static final String STRIP_NAME = ".template-strip__head .name-edit__name";

    // The edit button in a template's title, which opens its name and its settings
    static final String EDIT_TITLE = "Edit the name and settings";

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

    // A capturing group in the pattern workbench's map of the pattern.
    private static final String REGEX_GROUP = ".regex__group";

    // A marked run in the pattern workbench's matches pane, which renders through Marks as the
    // content and output panes do. A match rather than a capture: the sample tints a capture
    // per regex group, and the canned preview declares no groups.
    private static final String SAMPLE_MARK = ".sample__read .mark";

    // Cards of the output, by the frame whose body ran them: the source's first card, which
    // wrote "<records>", and the first card of the first pair it dispatched.
    private static final String OUTPUT_SOURCE_CARD = ".mark--instruction[data-instr='1:0']";

    private static final String OUTPUT_PAIR_CARD = ".mark--instruction[data-instr='2:0']";

    // A card of the current level, which carries the rule.
    private static final String OUTPUT_RULED = ".mark--instruction.mark--ruled";

    // The key of the first pair in this fixture's content pane, which is drawn one generation
    // below the document and is the cursor's own once the pane descends into that pair.
    private static final String EDITOR_CAPTURE_KEY = ".mark--capture[data-cap='2:0']";


    // The box that carries a template's match: what it matches, which opens in place when it
    // is a regex (design 47 B), and the way into the pattern workbench
    static final String MATCH_CHIP = ".template-strip__match-chip";

    // The match as it reads at rest, which is what is clicked to open it
    static final String MATCH_OPEN = ".template-strip__match-open";

    // A part's kind in the framed sequence's list; clicking it selects the part
    private static final String PART_KIND = ".parts__kind";

    // The way into the pattern workbench, beside the match
    static Query workbenchButton(final Play play) {
        return play.getByRole("button", StroomDom.button("workbench"));
    }

    // Selects a template and opens its match workbench, where the parts and the pattern tree live
    // The composite fixture's tree template in the workbench, its first tag selected and
    // Extract to the library pressed: the name prompt, as a Play scoped to it.
    private static Play extractTag(final Play play) {
        openWorkbench(play, "tree");
        play.click(play.getAllByRole("treeitem", TextMatch.regex("^tag")).first());
        play.click(play.getByRole("button", TextMatch.containing("Extract to the library")));
        return dialogMatching(play, play.screen(), "Extract to Library");
    }

    private static void openWorkbench(final Play play, final String name) {
        selectTemplate(play, name);
        play.waitFor(() -> play.expect(play.querySelector(MATCH_CHIP)).not().toBeNull());
        play.click(workbenchButton(play));
        play.waitFor(() -> play.expect(play.getByRole("button",
                TextMatch.regex("close the workbench", "i"))).toBeInTheDocument());
    }

    // Opens the sample page: the Data row in the panel is a destination, not a frame, so
    // choosing it puts the page where the navigator's rows were (design 44 §5a)
    static void openSamplePage(final Play play) {
        play.waitFor(() -> play.expect(play.getByText("Data", TEMPLATE_NAME)).toBeInTheDocument());
        play.click(play.getByText("Data", TEMPLATE_NAME));
        play.waitFor(() -> play.expect(play.getByText("Sample data from")).toBeInTheDocument());
    }

    // Picks a template in the panel, which is what fills the strip beside it
    static void selectTemplate(final Play play, final String name) {
        play.waitFor(() -> play.expect(play.getByText(name, TEMPLATE_NAME)).toBeInTheDocument());
        play.click(play.getByText(name, TEMPLATE_NAME));
    }

    // The host the story shows, for plays that act as ShapeshifterPlugin does
    static DocumentEditorHost<ShapeshifterDoc> host() {
        return ScreenHarness.shownComponent();
    }

    // Reads the shown document again, as DocumentPlugin does, read only or not
    private static void readAgain(final boolean readOnly) {
        final DocumentEditorHost<ShapeshifterDoc> host = host();
        host.setReadOnly(readOnly);
        host.read(host.getDocRef(), host.getEntity());
    }

    // The link a card list ends with, `+ instruction`, by its title. Pressed, it gives way to
    // a card whose kind is `auto` — what is typed says what it is (design 47 A) — until one is
    // chosen (design 47 D).
    static final String ADD_LINK = "Click to add an instruction";

    // A card's line, by its kind. Clicking the line opens the card where it is; clicking the
    // kind itself opens it with the kinds offered, which a story opening a card to read or
    // change its fields does not want in the way.
    static Query cardLine(final Play play, final String kind) {
        return play.getByText(kind, CARD_KIND).closest(".body-card__card");
    }

    // The card that is open, as a Play to scope queries to it. One is open at a time, and its
    // kind box is the one thing labelled Instruction.
    static Play cardEditor(final Play play) {
        return play.within(play.findByLabelText("Instruction").closest(".instruction-edit"));
    }

    // No card is open: the draft was taken, or dropped.
    static void expectNoCardOpen(final Play play) {
        play.waitFor(() -> play.expect(play.queryByLabelText("Instruction")).toBeNull());
    }

    // The body's add link. A body whose cards hold lists of their own has one for each, and
    // this is for the bodies that have the one.
    static Query addLink(final Play play) {
        return play.getByTitle(ADD_LINK);
    }

    // A new card of a kind: the add link pressed, which opens a card whose kind is left to
    // what is typed, and a kind chosen for it.
    static void newCard(final Play play, final Play screen, final String kind) {
        play.click(addLink(play));
        pickFromBox(play, screen, cardEditor(play).getByLabelText("Instruction"), kind);
    }

    // Run sits on the document's toolbar, beside Save, whichever tab is showing
    static Query runButton(final Play play) {
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

    static void expectNoProblems(final Play play) {
        play.expect(play.spy(ScreenHarness.ALERT_SPY)).not().toHaveBeenCalled();
        play.expect(play.spy(ScreenHarness.UNHANDLED_REQUEST_SPY)).not().toHaveBeenCalled();
    }

    // The project JSON goes into the document's JSON as a string, so it is quoted and escaped once
    // more. Done here rather than by hand, so the fixture above stays the engine's fixture
    static String asJsonString(final String text) {
        final StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                json.append('\\').append(c);
            } else if (c == '\n') {
                json.append("\\n");
            } else if (c < 0x20) {
                // Any other control character, as JSON spells it: a binary sample's length
                // bytes are these (rung 6)
                final String hex = "0123456789abcdef";
                json.append("\\u00").append(hex.charAt(c >> 4)).append(hex.charAt(c & 0xF));
            } else {
                json.append(c);
            }
        }
        return json.append('"').toString();
    }

    static Widget render(final StoryContext context, final boolean readOnly) {
        return render(context, readOnly, FIXTURES, DOC_REF);
    }

    // The run of the whole document is answered with its trace; the workbench's trial of the pair
    // template alone (Templates.experiment, a project of that template and no other) with the
    // pair's own matches
    static RestReply preview(final RecordedRequest request) {
        final String body = request.getBody();
        return body.contains("00000000-0000-0000-0000-000000000002")
               && !body.contains("00000000-0000-0000-0000-000000000001")
                ? RestReply.json(PAIR_TRIAL)
                : RestReply.json(TRACE);
    }

    /// The strict_kv document, with some of its routes answered otherwise.
    ///
    /// @param context The story's context.
    /// @param overrides Routes that answer before the document's own.
    /// @return The editor.
    static Widget render(final StoryContext context, final RestFixtures overrides) {
        return render(context, false, overrides.followedBy(FIXTURES), DOC_REF);
    }

    // A dialog by a word in its title, as a Play to scope queries to it: nested dialogs (the
    // template dialog and the mode prompt it opens) each need their own OK.
    static Play dialogMatching(final Play play, final Play screen, final String title) {
        // Case-insensitively: the dialogs title themselves "Edit Template" and "New Capture", so a
        // caller naming the thing it is editing should not have to know which words are capital.
        return play.within(screen.findByText(TextMatch.regex(title, "i"), StroomDom.DIALOG_TITLE)
                .closest(StroomDom.DIALOG));
    }

    // A SelectionBox's value, chosen the way a person does: click the box, then the item in the
    // popup it opens. Scoped to the item cells, because by the time a mode has templates in it
    // its name is also a heading in the panel, and an unscoped search finds both.
    static void pickFromBox(final Play play, final Play screen,
                                     final Query box, final String item) {
        play.click(box);
        play.click(screen.findByText(item, SELECTION_ITEM));
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
    static String withoutIds(final String projectJson) {
        return projectJson == null
                ? null
                : projectJson.replaceAll("\"id\"\\s*:\\s*\"[^\"]*\"", "\"id\": \"-\"");
    }

    /// The same screen over the composite fixture, whose templates reach the framed sequence, the
    /// pattern tree and a choose.
    static Widget renderComposite(final StoryContext context) {
        return render(context, false, COMPOSITE_FIXTURES, COMPOSITE_REF);
    }

    static Widget render(final StoryContext context, final boolean readOnly,
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
