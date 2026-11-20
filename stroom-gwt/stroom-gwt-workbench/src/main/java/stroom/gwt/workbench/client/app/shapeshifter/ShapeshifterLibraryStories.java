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

import stroom.gwt.workbench.client.app.rest.JsonValues;
import stroom.gwt.workbench.client.app.rest.RecordedRequest;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.Spy;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;

import java.util.List;
import java.util.Map;

/// Rung 4 of design 46's ladder — `library_parts`, a step at a time (§6.3). What is new here is
/// **the pattern library**: parts defined once and named with `ref`, one of them built from
/// another, and a template whose match is a pattern tree of labelled nodes rather than a regex.
///
/// The mechanism is [Walkthrough]'s; this file is only the steps.
///
/// **Written as a regex, then factored** (design 47 F). The table defines the four parts
/// (steps 3–5) before the template that uses them (6–7). §6.3 says the natural order is the
/// other way — write the line, then lift `TIMESTAMP` out of it — and the editor allows it: the
/// tree's *Extract to library* turns the selected node into a part and leaves a `ref` in its
/// place, keeping the node's label on the ref. The line is written as an author coming from a
/// regex writes it, one regex with a named group for each field, on the template's match line;
/// the match is made a pattern tree, which explodes the regex into the nodes it is; and the four
/// parts are lifted from that — `FIELD` last, after `KEY` has been lifted out of it.
///
/// The fixture is in the explode's spelling (design 47 §7.13), so the tree the explode makes is
/// the fixture's line with its parts written in place. The engine's `PatternExplodeTest` holds
/// that to be so for [#REGEX]; the explode route here serves that tree, [#EXPLODED].
public final class ShapeshifterLibraryStories {

    private static final String TITLE = "App/Shapeshifter/ShapeshifterLibrary";

    // The engine fixture's first two lines (fixtures/projects/library_parts), as the step table
    // has it: "pastes two log lines". Typed as an author types them, a line and Enter.
    private static final String LINE_1 =
            "2026-09-09T10:15:32Z host=web01 severity=warn msg=Disk usage at 91 percent on /var pid=4821";
    private static final String LINE_2 =
            "2026-09-09T10:15:33Z host=web01 severity=info msg=Health check passed in 12ms pid=4821";
    private static final String SAMPLE_TEXT = LINE_1 + "\n" + LINE_2 + "\n";
    private static final String SAMPLE = LINE_1 + "{Enter}" + LINE_2 + "{Enter}";

    private static final String ACE_CONTENT = ".ace_content";
    private static final String OUTPUT_BOX = ".output-pane__box";

    // The line as an author writes it, as PatternExplodeTest.RUNG_FOUR has it: the engine holds
    // that regex to explode to the fixture's line with its parts in place
    private static final String REGEX = "(?<time>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z)"
                                        + " host=(?<host>\\S+) severity=(?<severity>\\p{L}+) msg=(?<msg>.*?)"
                                        + "(?: [a-z]+=)(?<pid>\\p{Nd}+)[^\\n]*\\n";

    // The timestamp's twelve nodes: as the explode writes them, labelled, and as the TIMESTAMP
    // part holds them once lifted
    private static final String TIMESTAMP = """
            {"sequence": [{"take_while": "[0-9]", "min": 4, "max": 4}, {"tag": "-"},
              {"take_while": "[0-9]", "min": 2, "max": 2}, {"tag": "-"},
              {"take_while": "[0-9]", "min": 2, "max": 2}, {"tag": "T"},
              {"take_while": "[0-9]", "min": 2, "max": 2}, {"tag": ":"},
              {"take_while": "[0-9]", "min": 2, "max": 2}, {"tag": ":"},
              {"take_while": "[0-9]", "min": 2, "max": 2}, {"tag": "Z"}]}""";

    // ShapeshifterResource.explode() for REGEX: what the engine makes of it, which
    // PatternExplodeTest holds to be the fixture's line with its parts in place
    private static final String EXPLODED = "{\"sequence\": [" + labelled(TIMESTAMP, "time") + ", " + """
            {"tag": " host="}, {"take_while": "\\\\S", "label": "host"},
            {"tag": " severity="}, {"take_while": "\\\\p{L}", "label": "severity"},
            {"tag": " msg="}, {"repeat": {"take_while": ".", "max": 1}, "min": 0, "greedy": false, "label": "msg"},
            {"sequence": [{"tag": " "}, {"take_while": "[a-z]"}, {"tag": "="}]},
            {"take_while": "\\\\p{Nd}", "label": "pid"}, {"take_while": "[^\\\\n]", "min": 0},
            {"tag": "\\n"}]}""";

    // fixtures/projects/library_parts/project.json, in the explode's spelling since design 47
    // §7.13, with its "version" made the one the editor
    // writes, as every rung's is (rung 2 has the note). Nothing else differs: the writer emits
    // match limits and an apply-templates' depth whether or not they are the defaults, so the
    // fixture's spelling of them is the editor's too.
    private static final String PROJECT = """
            {
             "name": "library_parts",
             "version": 5,
             "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "auto"},
             "templates": [
              {"id": "00000000-0000-0000-0000-0000000000d0", "name": "document", "match": "source",
               "match_limits": {"min_match": 0, "max_match": -1},
               "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                             "max_depth": 64}}]},
              {"id": "00000000-0000-0000-0000-000000000001", "name": "line",
               "match": {"pattern": {"sequence": [
                 {"ref": "TIMESTAMP", "label": "time"},
                 {"tag": " host="},
                 {"ref": "WORD", "label": "host"},
                 {"tag": " severity="},
                 {"take_while": "\\\\p{L}", "label": "severity"},
                 {"tag": " msg="},
                 {"repeat": {"take_while": ".", "max": 1}, "min": 0, "greedy": false, "label": "msg"},
                 {"ref": "FIELD"},
                 {"take_while": "\\\\p{Nd}", "label": "pid"},
                 {"take_while": "[^\\\\n]", "min": 0},
                 {"tag": "\\n"}]}},
               "match_limits": {"min_match": 0, "max_match": -1},
               "body": [
                 {"text": "<event time=\\""}, {"value-of": {"parts": [{"capture": {"label": "time"}}]}},
                 {"text": "\\" host=\\""}, {"value-of": {"parts": [{"capture": {"label": "host"}}]}},
                 {"text": "\\" severity=\\""}, {"value-of": {"parts": [{"capture": {"label": "severity"}}]}},
                 {"text": "\\" pid=\\""}, {"value-of": {"parts": [{"capture": {"label": "pid"}}]}},
                 {"text": "\\">"}, {"value-of": {"parts": [{"capture": {"label": "msg"}}]}},
                 {"text": "</event>\\n"}]}
             ],
             "patterns": {
              "TIMESTAMP": """ + TIMESTAMP + """
            ,
              "WORD": {"take_while": "\\\\S"},
              "FIELD": {"sequence": [{"tag": " "}, {"ref": "KEY"}, {"tag": "="}]},
              "KEY": {"take_while": "[a-z]"}
             }
            }""";

    // The run the last stage is served: the two lines, and the two events the fixture's own
    // output has for them — a frame for the document and one for each line.
    private static final String TRACE = """
            {
              "compiled": true, "sampleRead": true,
              "input": """
                                        + ShapeshifterEditorStories.asJsonString(SAMPLE_TEXT) + """
            ,
              "output": """
                                        + ShapeshifterEditorStories.asJsonString(
            "<event time=\"2026-09-09T10:15:32Z\" host=\"web01\" severity=\"warn\" pid=\"4821\">"
            + "Disk usage at 91 percent on /var</event>\n"
            + "<event time=\"2026-09-09T10:15:33Z\" host=\"web01\" severity=\"info\" pid=\"4821\">"
            + "Health check passed in 12ms</event>\n") + """
            ,
              "frames": [
                {"id": 1, "parentId": 0, "templateId": "00000000-0000-0000-0000-0000000000d0",
                 "templateName": "document", "matchIndex": 0, "depth": 0,
                 "inputOffset": 0, "inputLength": 179, "contentOffset": 0, "contentLength": 179},
                {"id": 2, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000001",
                 "templateName": "line", "matchIndex": 0, "depth": 1,
                 "inputOffset": 0, "inputLength": 92, "contentOffset": 0, "contentLength": 92},
                {"id": 3, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000001",
                 "templateName": "line", "matchIndex": 1, "depth": 1,
                 "inputOffset": 92, "inputLength": 87, "contentOffset": 92, "contentLength": 87}
              ],
              "captures": [],
              "groups": [], "attempts": [], "guards": [],
              "attemptsSeen": 2,
              "timings": [
                {"templateId": "00000000-0000-0000-0000-000000000001", "attempts": 2, "matched": 2, "nanos": 0}
              ],
              "outputs": [
                {"frameId": 1, "offset": 0, "length": 227, "unit": "BYTES"},
                {"frameId": 2, "offset": 0, "length": 116, "unit": "BYTES"},
                {"frameId": 3, "offset": 116, "length": 111, "unit": "BYTES"}
              ],
              "instructions": [
                {"frameId": 1, "index": 0, "offset": 0, "length": 227, "unit": "BYTES"},
                {"frameId": 2, "index": 0, "offset": 0, "length": 116, "unit": "BYTES"},
                {"frameId": 3, "index": 0, "offset": 116, "length": 111, "unit": "BYTES"}
              ],
              "messages": [],
              "runNanos": 160000
            }""";

    /// The walkthrough, numbered sequentially so the screenshots sort into the order they
    /// happened; each stage's javadoc names the §6.3 step it drives.
    private static final List<Walkthrough.Stage> STAGES = List.of(
            new Walkthrough.Stage("Lp01Settings", ShapeshifterLibraryStories::settings),
            new Walkthrough.Stage("Lp02Sample", ShapeshifterLibraryStories::sample),
            new Walkthrough.Stage("Lp03Line", ShapeshifterLibraryStories::line),
            new Walkthrough.Stage("Lp04Tree", ShapeshifterLibraryStories::tree),
            new Walkthrough.Stage("Lp05LiftTimestamp", ShapeshifterLibraryStories::liftTimestamp),
            new Walkthrough.Stage("Lp06LiftWord", ShapeshifterLibraryStories::liftWord),
            new Walkthrough.Stage("Lp07LiftKey", ShapeshifterLibraryStories::liftKey),
            new Walkthrough.Stage("Lp08LiftField", ShapeshifterLibraryStories::liftField),
            new Walkthrough.Stage("Lp09Body", ShapeshifterLibraryStories::body),
            new Walkthrough.Stage("Lp10Built", ShapeshifterLibraryStories::built));

    private ShapeshifterLibraryStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        // The explode route answers the regex the line was given with what the engine makes of
        // it, and refuses anything else, so what the editor sends is what was typed. That REGEX
        // and EXPLODED are the engine's RUNG_FOUR and its explode is kept by hand: the engine
        // holds RUNG_FOUR's explode to the fixture, and the keystone holds EXPLODED's lifts to it
        final RestFixtures exploding = RestFixtures.builder()
                .post("/shapeshifter/v1/explode", request -> REGEX.equals(patternOf(request))
                        ? RestReply.json("{\"text\": " + ShapeshifterEditorStories.asJsonString(EXPLODED) + "}")
                        : RestReply.of(400, "text/plain", "Not the regex rung 4 types"))
                .build();
        final RestFixtures building = exploding.followedBy(Walkthrough.routes(Walkthrough.emptyTrace(SAMPLE_TEXT)));
        Walkthrough.register(registry, TITLE, ShapeshifterLibraryStories.class, STAGES, building,
                exploding.followedBy(Walkthrough.routes(TRACE)));
        // Stage 4's other way to the tree: the regex's own link, where the stage picks the kind
        Walkthrough.registerBeside(registry, TITLE, ShapeshifterLibraryStories.class, STAGES,
                stageNamed("Lp03Line"),
                "Lp04ExplodeIntoTheTree", ShapeshifterLibraryStories::explodeIntoTheTree, building);
    }

    // Where in the walkthrough a stage is, by its name
    private static int stageNamed(final String name) {
        for (int i = 0; i < STAGES.size(); i++) {
            if (STAGES.get(i).getName().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No stage " + name);
    }

    // The pattern an explode request asks about
    private static String patternOf(final RecordedRequest request) {
        final Object body = JsonValues.parse(request.getBody());
        return body instanceof Map
                ? String.valueOf(((Map<?, ?>) body).get("pattern"))
                : null;
    }

    // A node of the tree with a label on it, as the wire format writes one: a key of the node
    private static String labelled(final String node, final String label) {
        return node.substring(0, node.lastIndexOf('}')) + ", \"label\": \"" + label + "\"}";
    }

    /// §6.3 step 1. The name, and errors ignored; the encoding is already `auto`, which is what a
    /// new project starts with, and so is the buffer size.
    private static void settings(final Play play, final Play screen) {
        play.waitFor(() -> play.expect(play.getByText("document",
                ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
        play.click(play.getByText("Settings", ShapeshifterEditorStories.TEMPLATE_NAME));
        play.waitFor(() -> play.expect(play.getByText("Source — the document")).toBeVisible());
        play.clear(play.getByLabelText("Project name"));
        play.type(play.getByLabelText("Project name"), "library_parts");
        play.click(play.getByLabelText("Ignore errors"));
    }

    /// §6.3 step 2. Two log lines.
    private static void sample(final Play play, final Play screen) {
        ShapeshifterEditorStories.openSamplePage(play);
        play.click(play.querySelector(ACE_CONTENT));
        play.waitFor(() -> play.expect(play.querySelector(ShapeshifterEditorStories.ACE_INPUT))
                .toHaveFocus());
        play.keyboard(SAMPLE);
        play.waitFor(() -> play.expect(play.querySelector(ACE_CONTENT).textContent())
                .toMatch("severity=info"));
    }

    /// §6.3 steps 6 and 7. The one template, in the default mode with the document — no mode, so
    /// nothing to dispatch into — and the whole line as one regex on its match line, a named
    /// group for each field. Enter goes on into the body, which is empty; Escape leaves the card
    /// that opens there, since what the match is is not finished.
    private static void line(final Play play, final Play screen) {
        play.click(play.getByTitle("Click to add a template to mode default"));
        Walkthrough.named(play, "line");
        Walkthrough.matching(play, Walkthrough.typed(REGEX));
        play.keyboard("{Escape}");
    }

    /// §6.3 step 7, finished: the workbench, and the match made a pattern tree. The regex
    /// explodes into the nodes it is, its named groups the labels.
    private static void tree(final Play play, final Play screen) {
        play.click(ShapeshifterEditorStories.workbenchButton(play));
        play.waitFor(() -> play.expect(play.getByLabelText("Match kind")).toBeInTheDocument());
        ShapeshifterEditorStories.pickFromBox(play, screen, play.getByLabelText("Match kind"), "pattern tree");
        play.waitFor(() -> play.expect(node(play, "→ time$")).toBeVisible());
        play.expect(node(play, "→ msg$")).toBeVisible();
        play.expect(node(play, "^sequence \\(3\\)$")).toBeVisible();
        // The tree takes the room it is drawn in, so the sections under it start below its lowest
        // node rather than under the nodes (design 47 §7.7 item 4): the canvas the nodes are laid
        // over is sized to the layout
        final Query nodes = play.getAllByRole("treeitem");
        final Query asRegex = play.getByText(TextMatch.regex("^As regex"), ".workbench-form__heading");
        play.expect("the lowest node's bottom less the top of the section under the tree", () -> {
            double lowest = 0;
            for (int i = 0; i < nodes.count().get(); i++) {
                lowest = Math.max(lowest, nodes.nth(i).bottom().get());
            }
            return lowest - asRegex.top().get();
        }).toBeLessThanOrEqual(0);
    }

    /// §6.3 step 7, the other way: the regex exploded from its own form, by the link after its
    /// settings, rather than by choosing the kind. The same tree, from the same route.
    private static void explodeIntoTheTree(final Play play, final Play screen) {
        play.click(ShapeshifterEditorStories.workbenchButton(play));
        play.waitFor(() -> play.expect(play.getByRole("button", "explode into the tree ›")).toBeVisible());
        play.click(play.getByRole("button", "explode into the tree ›"));
        play.waitFor(() -> play.expect(node(play, "→ time$")).toBeVisible());
        play.expect(node(play, "→ msg$")).toBeVisible();
        play.expect(node(play, "^sequence \\(3\\)$")).toBeVisible();
        play.expect(play.getByLabelText("Match kind")).toHaveValue("pattern tree");
    }

    /// §6.3 step 3, in the order it comes to a person: the line is written, and the timestamp is
    /// lifted out of it into a part. The node becomes `ref TIMESTAMP`, still labelled `time`.
    private static void liftTimestamp(final Play play, final Play screen) {
        lift(play, screen, "→ time$", "TIMESTAMP");
        play.waitFor(() -> play.expect(node(play, "^ref TIMESTAMP → time$")).toBeVisible());
    }

    /// §6.3 step 4's first half.
    private static void liftWord(final Play play, final Play screen) {
        lift(play, screen, "→ host$", "WORD");
        play.waitFor(() -> play.expect(node(play, "^ref WORD → host$")).toBeVisible());
    }

    /// §6.3 step 4's second half, lifted from inside what will be FIELD.
    private static void liftKey(final Play play, final Play screen) {
        lift(play, screen, "^take_while \\[a-z\\]", "KEY");
        play.waitFor(() -> play.expect(node(play, "^ref KEY$")).toBeVisible());
    }

    /// §6.3 step 5: a part whose body references another part, arrived at by lifting the
    /// sequence that already holds `ref KEY`.
    private static void liftField(final Play play, final Play screen) {
        lift(play, screen, "^sequence \\(3\\)$", "FIELD");
        play.waitFor(() -> play.expect(node(play, "^ref FIELD$")).toBeVisible());
        // The library lists the four, as the parts every tree may name
        play.expect(play.getByText("FIELD", ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument();
        play.expect(play.getByText("KEY", ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument();
    }

    /// §6.3 step 8. The event, typed as it looks: one line, whose captures are the labels by
    /// name — `$time`, not a group number — and which the field splits into the text and
    /// value-of cards it describes (design 47 A and H). The workbench closed first: the body is
    /// the template's, beside the sample.
    private static void body(final Play play, final Play screen) {
        play.click(play.getByRole("button", TextMatch.regex("close the workbench", "i")));
        play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeVisible());
        Walkthrough.enter(play, "<event time=\"$time\" host=\"$host\" severity=\"$severity\" pid=\"$pid\">"
                                + "$msg</event>\\n");
        play.keyboard("{Escape}");
        play.waitFor(() -> play.expect(play.querySelectorAll(ShapeshifterEditorStories.CARD_KIND).textContents())
                .toEqual(List.of("text", "value-of", "text", "value-of", "text", "value-of", "text", "value-of",
                        "text", "value-of", "text")));
    }

    /// §6.3 step 9, and the keystone.
    private static void built(final Play play, final Play screen) {
        final Spy requests = play.spy(ScreenHarness.REQUEST_SPY);
        play.click(ShapeshifterEditorStories.runButton(play));
        // Read off the output pane's whole text: the input holds the same words
        play.waitFor(() -> play.expect(play.querySelector(OUTPUT_BOX).textContent())
                .toMatch("Health check passed in 12ms</event>"));
        Walkthrough.expectTried(play, "line", "2");
        play.waitFor(() -> play.expect("the project the editor built",
                        () -> JsonValues.jsonContains(Walkthrough.projectBuilt(requests),
                                ShapeshifterEditorStories.withoutIds(PROJECT)))
                .toEqual(true));
    }

    // A node of the tree, by what its box says: its kind and what it holds, then → and its
    // label where it has one.
    private static Query node(final Play play, final String pattern) {
        return play.getByRole("treeitem", TextMatch.regex(pattern));
    }

    // A node lifted out into a part of the library, named.
    private static void lift(final Play play, final Play screen, final String node, final String name) {
        play.click(node(play, node));
        play.click(play.getByRole("button", TextMatch.containing("Extract to the library")));
        final Play naming = ShapeshifterEditorStories.dialogMatching(play, screen, "Extract to Library");
        naming.type(naming.getByRole("textbox").first(), name);
        play.click(naming.getByRole("button", StroomDom.button("OK")));
        play.waitFor(() -> play.expect(screen.queryByText(TextMatch.regex("Extract to Library", "i"),
                StroomDom.DIALOG_TITLE)).toBeNull());
    }
}
