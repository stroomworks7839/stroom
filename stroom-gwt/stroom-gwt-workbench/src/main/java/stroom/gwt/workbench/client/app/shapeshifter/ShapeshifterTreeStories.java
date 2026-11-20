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
import stroom.gwt.workbench.client.app.rest.JsonValues;
import stroom.gwt.workbench.client.app.rest.RecordedRequest;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.widgets.tree.ExplorerFixture;
import stroom.gwt.workbench.client.widgets.tree.TreeFixtures;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.shapeshifter.shared.ShapeshifterDoc;

import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.Map;

/// Building and reading a pattern tree in the workbench (design 44 §4a, ruled 2026-11-20): what
/// each node matched, marked in the sample when it is pointed at or chosen; where a sequence that
/// matched nowhere stopped; a node's one text edited where it is drawn; the add line, which adds
/// a regex fragment as the nodes it is; and the toolbar in its four groups.
///
/// The project has two trees over two lines of `key=value`: `kv`, which matches them, and
/// `colon`, which looks for a `:` and so matches nowhere. The preview route answers as the engine
/// would — the workbench's trial of a tree carries a label on every node, and is refused here if
/// it does not, so the stories hold the editor to sending them.
public final class ShapeshifterTreeStories {

    private static final String TITLE = "App/Shapeshifter/ShapeshifterTree";

    private static final String NODE_MARK = ".sample__read .mark--capture";
    private static final String FIELD = "Node text";
    private static final String ADD = "Add after the selected node, as a regex";

    private static final String KV = "00000000-0000-0000-0000-000000000011";
    private static final String COLON = "00000000-0000-0000-0000-000000000012";

    private static final String PROJECT = """
            {
             "name": "trees",
             "version": 5,
             "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "utf-8"},
             "templates": [
              {"id": "00000000-0000-0000-0000-000000000010", "name": "source", "match": "source",
               "body": []},
              {"id": "00000000-0000-0000-0000-000000000011", "name": "kv",
               "match": {"pattern": {"sequence": [{"take_while": "[a-z]"},
                 {"tag": "="}, {"take_until": "\\\\n"}, {"tag": "\\\\n"}]}},
               "body": []},
              {"id": "00000000-0000-0000-0000-000000000012", "name": "colon",
               "match": {"pattern": {"sequence": [{"take_while": "[a-z]"},
                 {"tag": ":"}, {"take_until": "\\\\n"}, {"tag": "\\\\n"}]}},
               "body": []}
             ]
            }""";

    private static final String SAMPLE = "host=web01\\nlevel=warn\\n";

    private static final DocRef REF = new DocRef(ShapeshifterDoc.TYPE, "ss-trees", "Trees");

    private static final String DOC = "{\n"
                                      + "  \"type\": \"" + ShapeshifterDoc.TYPE + "\","
                                      + " \"uuid\": \"ss-trees\", \"name\": \"Trees\",\n"
                                      + "  \"description\": null,\n"
                                      + "  \"data\": " + ShapeshifterEditorStories.asJsonString(PROJECT) + ",\n"
                                      + "  \"sampleText\": \"" + SAMPLE + "\",\n"
                                      + "  \"sampleKind\": \"PASTED\"\n"
                                      + "}";

    // kv tried: both lines, each node's stretch under its trial label (PatternNodes.trialLabel)
    private static final String KV_TRIAL = trace("""
            {"id": 1, "parentId": 0, "templateId": "00000000-0000-0000-0000-000000000011",
             "templateName": "kv", "matchIndex": 1, "depth": 0,
             "inputOffset": 0, "inputLength": 11, "contentOffset": 0, "contentLength": 11},
            {"id": 2, "parentId": 0, "templateId": "00000000-0000-0000-0000-000000000011",
             "templateName": "kv", "matchIndex": 2, "depth": 0,
             "inputOffset": 11, "inputLength": 11, "contentOffset": 11, "contentLength": 11}""", """
            {"frameId": 1, "index": 1, "name": "ssnode0", "contentOffset": 0, "contentLength": 4},
            {"frameId": 1, "index": 2, "name": "ssnode1", "contentOffset": 4, "contentLength": 1},
            {"frameId": 1, "index": 3, "name": "ssnode2", "contentOffset": 5, "contentLength": 5},
            {"frameId": 1, "index": 4, "name": "ssnode3", "contentOffset": 10, "contentLength": 1},
            {"frameId": 2, "index": 1, "name": "ssnode0", "contentOffset": 0, "contentLength": 5},
            {"frameId": 2, "index": 2, "name": "ssnode1", "contentOffset": 5, "contentLength": 1},
            {"frameId": 2, "index": 3, "name": "ssnode2", "contentOffset": 6, "contentLength": 4},
            {"frameId": 2, "index": 4, "name": "ssnode3", "contentOffset": 10, "contentLength": 1}""", """
            {"parentFrameId": 0, "templateId": "00000000-0000-0000-0000-000000000011", "matched": true,
             "inputOffset": 0, "contentOffset": 0, "nanos": 0},
            {"parentFrameId": 0, "templateId": "00000000-0000-0000-0000-000000000011", "matched": true,
             "inputOffset": 11, "contentOffset": 11, "nanos": 0}""");

    // colon tried: nowhere
    private static final String COLON_TRIAL = trace("", "", """
            {"parentFrameId": 0, "templateId": "00000000-0000-0000-0000-000000000012", "matched": false,
             "inputOffset": 0, "contentOffset": 0, "nanos": 0},
            {"parentFrameId": 0, "templateId": "00000000-0000-0000-0000-000000000012", "matched": false,
             "inputOffset": 11, "contentOffset": 11, "nanos": 0}""");

    // colon's leading runs tried (Templates.prefixExperiment): its first node alone matches, the
    // key, and the two longer runs nowhere
    private static final String COLON_PROGRESS = trace("""
            {"id": 1, "parentId": 0, "templateId": "00000000-0000-0000-0000-000000000012-to-1",
             "templateName": "colon to 1", "matchIndex": 1, "depth": 0,
             "inputOffset": 0, "inputLength": 4, "contentOffset": 0, "contentLength": 4}""", """
            {"frameId": 1, "index": 1, "name": "ssnode0", "contentOffset": 0, "contentLength": 4}""", "");

    private ShapeshifterTreeStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component(TITLE, ShapeshifterTreeStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // Every node marks what it matched: none at rest, as the tree's nodes have no labels;
                // the one chosen, in every match; the one pointed at, while it is
                .story("ANodeMarksWhatItMatched", ShapeshifterTreeStories::render)
                .withPlay(play -> {
                    open(play, "kv");
                    play.waitFor(() -> play.expect(sampleBar(play)).toHaveTextContent(TextMatch.regex("2 matches")));
                    play.expect(play.querySelectorAll(NODE_MARK).count()).toEqual(0);
                    play.click(node(play, "^tag \"=\"$"));
                    play.waitFor(() -> play.expect(play.querySelectorAll(NODE_MARK).count()).toEqual(2));
                    play.expect(play.querySelectorAll(NODE_MARK).first().textContent()).toEqual("=");
                    play.expect(play.querySelectorAll(NODE_MARK).first()).toHaveAttribute("title", "tag \"=\"");
                    // A node has no colour of its own, so its text keeps the sample's: a hue here
                    // would name nothing else on screen (ruled 2026-11-20)
                    play.expect(play.querySelectorAll(NODE_MARK).first()).toHaveClass("mark--node");
                    play.expect(play.querySelectorAll(NODE_MARK).first()).not().toHaveAttribute("style");
                    // Pointing at another shows it instead, and leaving it shows the chosen again
                    play.hover(node(play, "^take_while \\[a-z\\]"));
                    play.waitFor(() -> play.expect(play.querySelectorAll(NODE_MARK).first().textContent())
                            .toEqual("host"));
                    play.expect(play.querySelectorAll(NODE_MARK).nth(1).textContent()).toEqual("level");
                    play.unhover(node(play, "^take_while \\[a-z\\]"));
                    play.waitFor(() -> play.expect(play.querySelectorAll(NODE_MARK).first().textContent())
                            .toEqual("="));
                    // A click on the chosen node's mark lets it go, as a click on a regex's group
                    // kept shown does (ruled 2026-11-20), and does not open the sample to edit it
                    play.click(play.querySelectorAll(NODE_MARK).first());
                    play.waitFor(() -> play.expect(play.querySelectorAll(NODE_MARK).count()).toEqual(0));
                    play.expect(node(play, "^tag \"=\"$")).not().toHaveAttribute("aria-selected", "true");
                    play.expect(play.getByLabelText("Sample text to match against")).not().toBeVisible();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A sequence that matched nowhere says how far it got: its first node matched the
                // key, and then its tag ":" did not, which the sample and the tree both say
                .story("ASequenceThatMatchedNowhereSaysWhereItStopped", ShapeshifterTreeStories::render)
                .withPlay(play -> {
                    open(play, "colon");
                    play.waitFor(() -> play.expect(sampleBar(play))
                            .toHaveTextContent(TextMatch.regex("No matches · stopped at tag \":\", node 2 of 4")));
                    play.expect(play.within(node(play, "^tag \":\"$"))
                            .querySelector(".pattern-tree__node-label--stopped")).toBeVisible();
                    play.expect(play.querySelector(".sample__read .mark--gap[title^='Stopped here']"))
                            .not().toBeNull();
                    // Red, where the trial's other places are quiet ticks: stopping is a failure
                    // and not matching on a trial is none (ruled 2026-11-20)
                    play.expect(play.querySelector(".sample__read .mark--gap[title^='Stopped here']"))
                            .not().toHaveClass("mark--tried");
                    // The run that got furthest is drawn, and its node is there to point at
                    play.hover(node(play, "^take_while \\[a-z\\]"));
                    play.waitFor(() -> play.expect(play.querySelectorAll(NODE_MARK).first().textContent())
                            .toEqual("host"));
                    // The mark is the tree's that stopped: another template's tree, which matches,
                    // carries none
                    ShapeshifterEditorStories.selectTemplate(play, "kv");
                    play.waitFor(() -> play.expect(sampleBar(play)).toHaveTextContent(TextMatch.regex("2 matches")));
                    play.expect(play.querySelector(".pattern-tree__node-label--stopped")).toBeNull();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A node with one text is edited where it is drawn: a double click, the text, Enter
                .story("ANodesTextIsEditedInPlace", ShapeshifterTreeStories::render)
                .withPlay(play -> {
                    open(play, "kv");
                    play.dblClick(node(play, "^tag \"=\"$"));
                    play.waitFor(() -> play.expect(play.getByLabelText(FIELD)).toHaveFocus());
                    play.expect(play.getByLabelText(FIELD)).toHaveValue("=");
                    play.clear(play.getByLabelText(FIELD));
                    play.keyboard(":{Enter}");
                    play.waitFor(() -> play.expect(node(play, "^tag \":\"$")).toBeVisible());
                    play.expect(play.queryByLabelText(FIELD)).toBeNull();
                    // Escape puts the text back, and the workbench stays open
                    play.dblClick(node(play, "^tag \":\"$"));
                    play.waitFor(() -> play.expect(play.getByLabelText(FIELD)).toHaveFocus());
                    play.keyboard("x{Escape}");
                    play.waitFor(() -> play.expect(play.queryByLabelText(FIELD)).toBeNull());
                    play.expect(node(play, "^tag \":\"$")).toBeVisible();
                    play.expect(play.getByRole("button", TextMatch.regex("close the workbench", "i"))).toBeVisible();
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    // Emptied, the text is refused as the dialog refuses it, and the node kept
                    play.dblClick(node(play, "^tag \":\"$"));
                    play.waitFor(() -> play.expect(play.getByLabelText(FIELD)).toHaveFocus());
                    play.clear(play.getByLabelText(FIELD));
                    play.keyboard("{Enter}");
                    play.waitFor(() -> play.expect(play.getByText(TextMatch.regex("needs its text")))
                            .toBeVisible());
                    play.expect(node(play, "^tag \":\"$")).toBeVisible();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // The add line: a regex fragment, added after the chosen node as the nodes it is,
                // the last of them chosen so the next follows it
                .story("TheAddLineAddsTheNodesARegexIs", ShapeshifterTreeStories::render)
                .withPlay(play -> {
                    open(play, "kv");
                    play.click(node(play, "^tag \"=\"$"));
                    // [[ is a [ typed: a lone one starts a key's name
                    play.type(play.getByLabelText(ADD), ";[[0-9]+");
                    play.keyboard("{Enter}");
                    play.waitFor(() -> play.expect(node(play, "^tag \";\"$")).toBeVisible());
                    play.expect(node(play, "^take_while \\[0-9\\]")).toHaveAttribute("aria-selected", "true");
                    play.expect(play.getByLabelText(ADD)).toHaveValue("");
                    play.expect(play.getByLabelText(ADD)).toHaveFocus();
                    // Both after the tag, in order
                    play.expect(play.getAllByRole("treeitem").nth(3).textContent()).toEqual("tag \";\"");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // The toolbar in four groups, each button named for where text is shown
                .story("TheTreeToolbarIsGrouped", ShapeshifterTreeStories::render)
                .withPlay(play -> {
                    open(play, "kv");
                    play.expect(play.querySelectorAll(".workbench-form__toolbar .toolbar__separator").count())
                            .toEqual(3);
                    play.expect(play.getByRole("button", TextMatch.regex("^Unwrap: "))).toBeInTheDocument();
                    play.expect(play.getByRole("button", TextMatch.regex("^Add after the selected node")))
                            .toBeInTheDocument();
                    ShapeshifterEditorStories.expectNoProblems(play);
                });
    }

    private static Widget render(final StoryContext context) {
        final RestFixtures fixtures = TreeFixtures.explorerRoutes(
                        new ExplorerFixture(ExplorerFixture.folder("System",
                                ExplorerFixture.doc("Trees", ShapeshifterDoc.TYPE))))
                .get("/shapeshifter/v1/ss-trees", RestReply.json(DOC))
                .post("/shapeshifter/v1/validate", RestReply.json(ShapeshifterEditorStories.VALIDATION))
                .post("/shapeshifter/v1/preview", ShapeshifterTreeStories::preview)
                .post("/shapeshifter/v1/print", RestReply.json(ShapeshifterEditorStories.PRINTED))
                .post("/shapeshifter/v1/explode", ShapeshifterTreeStories::explode)
                .get("/shapeshifter/v1/library", RestReply.json(ShapeshifterEditorStories.LIBRARY))
                .get("/shapeshifter/v1/encodings", RestReply.json(ShapeshifterEditorStories.ENCODINGS))
                .post("/permission/doc/v1/fetchDocumentUserPermissions",
                        RestReply.json(ShapeshifterEditorStories.DOC_PERMISSIONS))
                .build();
        return ShapeshifterEditorStories.render(context, false, fixtures, REF);
    }

    // The workbench's trials answered as the engine would, by the templates they carry: kv alone
    // (labelled on every node, or refused), colon alone, colon's leading runs; and the run of the
    // whole document, which matches nothing here
    private static RestReply preview(final RecordedRequest request) {
        final Object body = JsonValues.parse(request.getBody());
        final String project = body instanceof Map
                ? String.valueOf(((Map<?, ?>) body).get("project"))
                : "";
        final Object parsed = JsonValues.parse(project);
        final List<?> templates = parsed instanceof Map && ((Map<?, ?>) parsed).get("templates") instanceof List
                ? (List<?>) ((Map<?, ?>) parsed).get("templates")
                : List.of();
        final String first = templates.isEmpty() || !(templates.get(0) instanceof Map)
                ? ""
                : String.valueOf(((Map<?, ?>) templates.get(0)).get("id"));
        if (templates.size() == 1 && first.equals(KV)) {
            return project.contains("ssnode3")
                    ? RestReply.json(KV_TRIAL)
                    : RestReply.of(400, "text/plain", "The trial of a tree carries a label on every node");
        }
        if (templates.size() == 1 && first.equals(COLON)) {
            return RestReply.json(COLON_TRIAL);
        }
        if (first.startsWith(COLON + "-to-")) {
            return RestReply.json(COLON_PROGRESS);
        }
        return RestReply.json(Walkthrough.emptyTrace(SAMPLE.replace("\\n", "\n")));
    }

    // The fragment the add line sends, as the engine explodes it
    private static RestReply explode(final RecordedRequest request) {
        return request.getBody().contains(";[0-9]+")
                ? RestReply.json("{\"text\": " + ShapeshifterEditorStories.asJsonString("""
                {"sequence": [{"tag": ";"}, {"take_while": "[0-9]"}]}""") + "}")
                : RestReply.of(400, "text/plain", "Not the fragment the story types");
    }

    // A trial's trace: its frames, groups and attempts, over the sample
    private static String trace(final String frames, final String groups, final String attempts) {
        return "{\n"
               + "  \"compiled\": true, \"sampleRead\": true,\n"
               + "  \"input\": \"" + SAMPLE + "\",\n"
               + "  \"output\": \"\",\n"
               + "  \"frames\": [" + frames + "], \"captures\": [],\n"
               + "  \"groups\": [" + groups + "], \"attempts\": [" + attempts + "], \"guards\": [],\n"
               + "  \"attemptsSeen\": 0, \"timings\": [],\n"
               + "  \"outputs\": [], \"instructions\": [], \"messages\": [],\n"
               + "  \"runNanos\": 0\n"
               + "}";
    }

    // The template chosen, and its workbench opened by its match, as a tree's is
    private static void open(final Play play, final String template) {
        ShapeshifterEditorStories.selectTemplate(play, template);
        play.waitFor(() -> play.expect(play.querySelector(ShapeshifterEditorStories.MATCH_CHIP)).not().toBeNull());
        play.click(play.querySelector(".template-strip__match-open"));
        play.waitFor(() -> play.expect(node(play, "^take_while \\[a-z\\]")).toBeVisible());
    }

    private static Query node(final Play play, final String pattern) {
        return play.getByRole("treeitem", TextMatch.regex(pattern));
    }

    private static Query sampleBar(final Play play) {
        return play.getByRole("button", TextMatch.regex("^Sample( |$)"));
    }
}
