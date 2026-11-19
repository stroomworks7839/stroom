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
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.Spy;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;

import java.util.List;

/// Rung 4 of design 46's ladder — `library_parts`, a step at a time (§6.3). What is new here is
/// **the pattern library**: parts defined once and named with `ref`, one of them built from
/// another, and a template whose match is a pattern tree of labelled nodes rather than a regex.
///
/// The mechanism is [Walkthrough]'s; this file is only the steps.
///
/// **Driven in the order §6.3 asked to be tried first**, not the table's. The table defines the
/// four parts (steps 3–5) before the template that uses them (6–7). §6.3 says the natural order
/// is the other way — write the line, then lift `TIMESTAMP` out of it — and that whether the
/// editor allows it is the thing to try first. It does: the tree's *Extract to library* turns
/// the selected node into a part and leaves a `ref` in its place, keeping the node's label on
/// the ref and off the part. So the line is written out in full, and the four parts are lifted
/// from it afterwards — `FIELD` last, after `KEY` has been lifted out of it, which is a part
/// built from a part arrived at the way a person factors.
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

    // The timestamp, as the regex the TIMESTAMP part is
    private static final String TIMESTAMP = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z";

    // fixtures/projects/library_parts/project.json, with its "version" made the one the editor
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
                 {"take_until": " pid=", "label": "msg"},
                 {"ref": "FIELD"},
                 {"take_while": "\\\\p{Nd}", "label": "pid"},
                 {"take_through": "\\n"}]}},
               "match_limits": {"min_match": 0, "max_match": -1},
               "body": [{"value-of": {"parts": [
                 {"text": "<event time=\\""}, {"capture": {"label": "time"}},
                 {"text": "\\" host=\\""}, {"capture": {"label": "host"}},
                 {"text": "\\" severity=\\""}, {"capture": {"label": "severity"}},
                 {"text": "\\" pid=\\""}, {"capture": {"label": "pid"}},
                 {"text": "\\">"}, {"capture": {"label": "msg"}},
                 {"text": "</event>\\n"}]}}]}
             ],
             "patterns": {
              "TIMESTAMP": {"regex": "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"},
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
            new Walkthrough.Stage("Lp05Time", ShapeshifterLibraryStories::time),
            new Walkthrough.Stage("Lp06Host", ShapeshifterLibraryStories::host),
            new Walkthrough.Stage("Lp07Severity", ShapeshifterLibraryStories::severity),
            new Walkthrough.Stage("Lp08Message", ShapeshifterLibraryStories::message),
            new Walkthrough.Stage("Lp09Field", ShapeshifterLibraryStories::field),
            new Walkthrough.Stage("Lp10Pid", ShapeshifterLibraryStories::pid),
            new Walkthrough.Stage("Lp11LiftTimestamp", ShapeshifterLibraryStories::liftTimestamp),
            new Walkthrough.Stage("Lp12LiftWord", ShapeshifterLibraryStories::liftWord),
            new Walkthrough.Stage("Lp13LiftKey", ShapeshifterLibraryStories::liftKey),
            new Walkthrough.Stage("Lp14LiftField", ShapeshifterLibraryStories::liftField),
            new Walkthrough.Stage("Lp15Body", ShapeshifterLibraryStories::body),
            new Walkthrough.Stage("Lp16Built", ShapeshifterLibraryStories::built));

    private ShapeshifterLibraryStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        Walkthrough.register(registry, TITLE, ShapeshifterLibraryStories.class, STAGES,
                Walkthrough.routes(Walkthrough.emptyTrace(SAMPLE_TEXT)),
                Walkthrough.routes(TRACE));
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

    /// §6.3 step 6. The one template, in the root with the document — no mode, so nothing to
    /// dispatch into. It arrives with its match line open for a regex; this match is not one,
    /// so Escape leaves it.
    private static void line(final Play play, final Play screen) {
        play.click(play.getByTitle("Click to add a template to the root"));
        Walkthrough.named(play, "line");
        play.waitFor(() -> play.expect(play.getByLabelText("Match pattern")).toHaveFocus());
        play.keyboard("{Escape}");
    }

    /// §6.3 step 7 begins: the workbench, and the match made a pattern tree. A new template's
    /// match is an empty regex, which becomes a tree of one empty regex node.
    private static void tree(final Play play, final Play screen) {
        play.click(ShapeshifterEditorStories.workbenchButton(play));
        play.waitFor(() -> play.expect(play.getByLabelText("Match kind")).toBeInTheDocument());
        ShapeshifterEditorStories.pickFromBox(play, screen, play.getByLabelText("Match kind"), "pattern tree");
        play.waitFor(() -> play.expect(node(play, "^regex\\s*$")).toBeVisible());
    }

    /// The timestamp, written where it is used: the empty node opened and made the regex,
    /// labelled `time`. It becomes the TIMESTAMP part at stage 11.
    private static void time(final Play play, final Play screen) {
        play.click(node(play, "^regex\\s*$"));
        play.click(play.getByRole("button", TextMatch.containing("Edit the selected node")));
        fill(play, screen, "Edit Node", "regex", TIMESTAMP, "time");
        play.waitFor(() -> play.expect(node(play, "→ time$")).toBeVisible());
    }

    /// The host. Adding a node after the root makes the root a sequence of the two.
    private static void host(final Play play, final Play screen) {
        after(play, screen, "tag", " host=", null);
        after(play, screen, "take_while", "\\S", "host");
    }

    private static void severity(final Play play, final Play screen) {
        after(play, screen, "tag", " severity=", null);
        after(play, screen, "take_while", "\\p{L}", "severity");
    }

    private static void message(final Play play, final Play screen) {
        after(play, screen, "tag", " msg=", null);
        after(play, screen, "take_until", " pid=", "msg");
    }

    /// What `FIELD` will be, written inline first: a sequence of a space, a run of letters and
    /// an `=`. It matches ` pid=` and labels nothing — a node can be structural.
    private static void field(final Play play, final Play screen) {
        after(play, screen, "sequence", null, null);
        play.click(play.getByRole("button", TextMatch.containing("Add a child to the selected node")));
        fill(play, screen, "New Node", "tag", " ", null);
        after(play, screen, "take_while", "[a-z]", null);
        after(play, screen, "tag", "=", null);
    }

    /// The pid, and the end of the line. The inner sequence selected again so the next node
    /// goes after it, in the line and not in it. The terminator is a newline, spelt `\n`.
    private static void pid(final Play play, final Play screen) {
        play.click(node(play, "^sequence \\(3\\)$"));
        after(play, screen, "take_while", "\\p{Nd}", "pid");
        after(play, screen, "take_through", "\\n", null);
        play.waitFor(() -> play.expect(node(play, "^take_through")).toBeVisible());
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

    /// §6.3 step 8. One `value-of` whose captures are the labels, by name — `$time`, not a group
    /// number. The workbench closed first: the body is the template's, beside the sample.
    private static void body(final Play play, final Play screen) {
        play.click(play.getByRole("button", TextMatch.regex("close the workbench", "i")));
        play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeVisible());
        play.click(ShapeshifterEditorStories.addLink(play));
        Walkthrough.valueOf(play, screen, "\"<event time=\\\"\" $time \"\\\" host=\\\"\" $host"
                                          + " \"\\\" severity=\\\"\" $severity \"\\\" pid=\\\"\" $pid"
                                          + " \"\\\">\" $msg \"</event>\\n\"");
        play.keyboard("{Escape}");
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

    // A node added after the selected one, which becomes the selected one.
    private static void after(final Play play, final Play screen, final String kind, final String text,
                              final String label) {
        play.click(play.getByRole("button", TextMatch.containing("Add a node after the selected one")));
        fill(play, screen, "New Node", kind, text, label);
    }

    // The node dialog filled in and taken: its kind, the one text field that kind has, and a
    // label. The text field is called what the kind calls it — Pattern, Character class,
    // Terminator, Text — and it is the one visible field it can be.
    private static void fill(final Play play, final Play screen, final String title, final String kind,
                             final String text, final String label) {
        final Play dialog = ShapeshifterEditorStories.dialogMatching(play, screen, title);
        ShapeshifterEditorStories.pickFromBox(play, screen, dialog.getByLabelText("Kind"), kind);
        if (text != null) {
            // Typed into as it arrives: a new node's field is empty, and so is the empty regex
            // the tree starts from, so clearing it first was an action no author makes
            final Query field = dialog.getByLabelText(TextMatch.regex("^(Text|Character class|Terminator|Pattern)$"));
            play.expect(field.value()).toEqual("");
            dialog.type(field, Walkthrough.typed(text));
        }
        if (label != null) {
            dialog.type(dialog.getByLabelText("Label"), label);
        }
        play.click(dialog.getByRole("button", StroomDom.button("OK")));
        play.waitFor(() -> play.expect(screen.queryByText(TextMatch.regex(title, "i"),
                StroomDom.DIALOG_TITLE)).toBeNull());
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
