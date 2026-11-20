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

/// Rung 5 of design 46's ladder — `text_019_single_line_split`, a step at a time (§6.4). What is
/// new here is **names with a lifetime**: a list declared on the document, filled one column at
/// a time by a capture in the header row, and read back by position in every data row with
/// `heading[matchCount()]`; a scalar bound to what a body wrote, and tested before anything is
/// written. And the delimiter match, and a limit that is the whole difference between two
/// templates.
///
/// The mechanism is [Walkthrough]'s; this file is only the steps.
///
/// **Driven mode first.** The table makes each template and then points a card at a mode with
/// `New…` on the card, which the card never had (§6.1a). Here each mode is added with
/// `+ mode` before anything dispatches into it, and the first — the one the document
/// dispatches into — is dispatched from its own pane (design 47 C). The others are dispatched
/// from cards in the templates that own them, which is a decision and is the author's.
///
/// **The mode is called `root`**, which is the fixture's name for it. The editor once used the
/// same word for having no mode at all; it says `default` for that now (design 47 §7.3), and
/// the panel heads the two `mode: default` and `mode: root`.
///
/// **Every literal is a `text` card**, typed as a line as it looks (design 47 A), and a
/// reference that is not a capture is written in the line as `${…}` (design 47 H). The fixture
/// is in that spelling since H, so the keystone holds the lines' cards.
public final class ShapeshifterSplitStories {

    private static final String TITLE = "App/Shapeshifter/ShapeshifterSplit";

    // The engine fixture's own input (fixtures/projects/text_019_single_line_split): one line, a
    // header record and a data record joined by `|`, each a comma-separated row of quoted values.
    // One value is an emoji, which is the fixture's and is kept.
    private static final String HEADER = "\"dateTime\",\"uuid\",\"firstName\",\"surname\",\"username\","
                                         + "\"bloodGroup\",\"emotionalState\",\"address\",\"company\","
                                         + "\"companyLogo\",\"lorum\"";
    private static final String DATA = "\"2020-08-29T04:00:40.832\",\"fd296b10-8c7d-4988-83ea-5e6114c66e8a\","
                                       + "\"Chantay\",\"Huel\",\"teodora.toy\",\"AB+\",\"😗\","
                                       + "\"3242 Berge Centers, Port Brenda, MO Z28 6XL\","
                                       + "\"Ebert, Jones and McGlynn\","
                                       + "\"https://pigment.github.io/fake-logos/logos/medium/color/13.png\","
                                       + "\"alias modi et accusamus\"";
    private static final String SAMPLE_TEXT = HEADER + "|" + DATA;

    private static final String ACE_CONTENT = ".ace_content";
    private static final String OUTPUT_BOX = ".output-pane__box";

    // What the document writes around its records
    private static final String PROLOGUE = "<?xml version=\"1.1\" encoding=\"UTF-8\"?>\n"
                                           + "<records xmlns=\"records:2\"\n"
                                           + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                                           + "         xsi:schemaLocation=\"records:2 file://records-v2.0.xsd\"\n"
                                           + "         version=\"2.0\">";
    private static final String EPILOGUE = "\n</records>\n";

    // fixtures/projects/text_019_single_line_split/project.json, with two changes, both of a
    // kind rung 2 has the note for: its "version" is the one the editor writes, and
    // header_column's `"body": []` is dropped, because the engine's own writer drops an empty
    // body.
    private static final String PROJECT = """
            {
              "name": "text_019_single_line_split",
              "version": 5,
              "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "auto"},
              "templates": [
                {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                 "declarations": [{"name": "heading", "type": "list"}],
                 "match": "source",
                 "body": [
                   {"text": """
                                          + ShapeshifterEditorStories.asJsonString(PROLOGUE) + """
            },
                   {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "root"}},
                   {"text": "\\n</records>\\n"}]},
                {"id": "00000000-0000-0000-0000-000000000002", "name": "header_record", "mode": "root",
                 "match": {"delimiter": {"delimiter": "|"}},
                 "match_limits": {"max_match": 1},
                 "body": [
                   {"apply-templates": {"select": {"parts": [{"capture": {"group": 1}}]},
                                        "mode": "header_columns"}}]},
                {"id": "00000000-0000-0000-0000-000000000003", "name": "header_column",
                 "mode": "header_columns",
                 "match": {"delimiter": {"delimiter": ",", "container_start": "\\"", "container_end": "\\""}},
                 "captures": [{"name": "heading", "select": {"group": 1}}]},
                {"id": "00000000-0000-0000-0000-000000000004", "name": "data_record", "mode": "root",
                 "declarations": [{"name": "record_body", "type": "scalar"}],
                 "match": {"delimiter": {"delimiter": "|"}},
                 "body": [
                   {"variable": {"name": "record_body", "body": [
                     {"apply-templates": {"select": {"parts": [{"capture": {"group": 1}}]},
                                          "mode": "data_columns"}}]}},
                   {"if": {"test": {"exists": {"select": {"parts": [
                             {"capture": {"var_id": "record_body", "group": 0}}]}}},
                           "then": [
                             {"text": "\\n   <record>"},
                             {"value-of": {"parts": [{"capture": {"var_id": "record_body", "group": 0}}]}},
                             {"text": "\\n   </record>"}]}}]},
                {"id": "00000000-0000-0000-0000-000000000005", "name": "data_column", "mode": "data_columns",
                 "match": {"delimiter": {"delimiter": ",", "container_start": "\\"", "container_end": "\\""}},
                 "body": [
                   {"text": "\\n      <data name=\\""},
                   {"value-of": {"parts": [
                     {"capture": {"var_id": "heading", "group": 0, "match_index": {"function": "matchCount"}}}]}},
                   {"text": "\\" value=\\""},
                   {"value-of": {"parts": [{"capture": {"group": 1}}]}},
                   {"text": "\\"/>"}]}
              ]
            }""";

    // The run the last stage is served: the fixture's input and its whole output, with a frame
    // for the document and one for each record. The columns' frames are left out: the screen at
    // the end is the output, and a canned trace a column deep says nothing the output does not.
    private static final String TRACE = """
            {
              "compiled": true, "sampleRead": true,
              "input": """
                                        + ShapeshifterEditorStories.asJsonString(SAMPLE_TEXT) + """
            ,
              "output": """
                                        + ShapeshifterEditorStories.asJsonString(output()) + """
            ,
              "frames": [
                {"id": 1, "parentId": 0, "templateId": "00000000-0000-0000-0000-000000000001",
                 "templateName": "source", "matchIndex": 0, "depth": 0,
                 "inputOffset": 0, "inputLength": 393, "contentOffset": 0, "contentLength": 393},
                {"id": 2, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000002",
                 "templateName": "header_record", "matchIndex": 0, "depth": 1,
                 "inputOffset": 0, "inputLength": 123, "contentOffset": 0, "contentLength": 122},
                {"id": 3, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000004",
                 "templateName": "data_record", "matchIndex": 0, "depth": 1,
                 "inputOffset": 123, "inputLength": 270, "contentOffset": 123, "contentLength": 270}
              ],
              "captures": [],
              "groups": [], "attempts": [], "guards": [],
              "attemptsSeen": 3,
              "timings": [
                {"templateId": "00000000-0000-0000-0000-000000000002", "attempts": 1, "matched": 1, "nanos": 0},
                {"templateId": "00000000-0000-0000-0000-000000000004", "attempts": 1, "matched": 1, "nanos": 0}
              ],
              "outputs": [
                {"frameId": 1, "offset": 0, "length": 924, "unit": "BYTES"},
                {"frameId": 3, "offset": 216, "length": 696, "unit": "BYTES"}
              ],
              "instructions": [
                {"frameId": 1, "index": 0, "offset": 0, "length": 216, "unit": "BYTES"},
                {"frameId": 1, "index": 1, "offset": 216, "length": 696, "unit": "BYTES"},
                {"frameId": 1, "index": 2, "offset": 912, "length": 12, "unit": "BYTES"},
                {"frameId": 3, "index": 1, "offset": 216, "length": 696, "unit": "BYTES"}
              ],
              "messages": [],
              "runNanos": 210000
            }""";

    /// The walkthrough, numbered sequentially so the screenshots sort into the order they
    /// happened; each stage's javadoc names the §6.4 step it drives.
    private static final List<Walkthrough.Stage> STAGES = List.of(
            new Walkthrough.Stage("Ts01Settings", ShapeshifterSplitStories::settings),
            new Walkthrough.Stage("Ts02Sample", ShapeshifterSplitStories::sample),
            new Walkthrough.Stage("Ts03RootRenamed", ShapeshifterSplitStories::rootRenamed),
            new Walkthrough.Stage("Ts04Heading", ShapeshifterSplitStories::heading),
            new Walkthrough.Stage("Ts05RootMode", ShapeshifterSplitStories::rootMode),
            new Walkthrough.Stage("Ts06Envelope", ShapeshifterSplitStories::envelope),
            new Walkthrough.Stage("Ts07HeaderRecord", ShapeshifterSplitStories::headerRecord),
            new Walkthrough.Stage("Ts08OnlyTheFirst", ShapeshifterSplitStories::onlyTheFirst),
            new Walkthrough.Stage("Ts09HeaderColumns", ShapeshifterSplitStories::headerColumns),
            new Walkthrough.Stage("Ts10HeaderColumn", ShapeshifterSplitStories::headerColumn),
            new Walkthrough.Stage("Ts11HeadingCapture", ShapeshifterSplitStories::headingCapture),
            new Walkthrough.Stage("Ts12DataRecord", ShapeshifterSplitStories::dataRecord),
            new Walkthrough.Stage("Ts13RecordBody", ShapeshifterSplitStories::recordBody),
            new Walkthrough.Stage("Ts14Variable", ShapeshifterSplitStories::variable),
            new Walkthrough.Stage("Ts15IfExists", ShapeshifterSplitStories::ifExists),
            new Walkthrough.Stage("Ts16DataColumn", ShapeshifterSplitStories::dataColumn),
            new Walkthrough.Stage("Ts17DataLine", ShapeshifterSplitStories::dataLine),
            new Walkthrough.Stage("Ts18Built", ShapeshifterSplitStories::built));

    private ShapeshifterSplitStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        Walkthrough.register(registry, TITLE, ShapeshifterSplitStories.class, STAGES,
                Walkthrough.routes(Walkthrough.emptyTrace(SAMPLE_TEXT)),
                Walkthrough.routes(TRACE));
    }

    /// §6.4 step 1. Only the name: `auto` and errors not ignored are what a new project has.
    private static void settings(final Play play, final Play screen) {
        play.waitFor(() -> play.expect(play.getByText("document",
                ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
        play.click(play.getByText("Settings", ShapeshifterEditorStories.TEMPLATE_NAME));
        play.waitFor(() -> play.expect(play.getByText("Source — the document")).toBeVisible());
        play.clear(play.getByLabelText("Project name"));
        play.type(play.getByLabelText("Project name"), "text_019_single_line_split");
    }

    /// §6.4 step 2. The table says a header and two data rows; the fixture has one data row, and
    /// it is the fixture that is pasted.
    private static void sample(final Play play, final Play screen) {
        ShapeshifterEditorStories.openSamplePage(play);
        play.click(play.querySelector(ACE_CONTENT));
        play.waitFor(() -> play.expect(play.querySelector(ShapeshifterEditorStories.ACE_INPUT))
                .toHaveFocus());
        play.keyboard(SAMPLE_TEXT);
        play.waitFor(() -> play.expect(play.querySelector(ACE_CONTENT).textContent())
                .toMatch("teodora.toy"));
    }

    /// §6.4 step 3, and the rename the table does not have (§6.1a).
    private static void rootRenamed(final Play play, final Play screen) {
        ShapeshifterEditorStories.selectTemplate(play, "document");
        play.click(play.getByRole("button", ShapeshifterEditorStories.EDIT_TITLE));
        play.waitFor(() -> play.expect(play.getByLabelText("Template name")).toHaveFocus());
        play.keyboard("source{Enter}");
        play.waitFor(() -> play.expect(play.getByText("source",
                ShapeshifterEditorStories.STRIP_NAME)).toBeInTheDocument());
    }

    /// §6.4 step 4. The list the header's columns go into, declared on the document so that it
    /// lives as long as the run does — design 44 §5m's second reason for the document row.
    private static void heading(final Play play, final Play screen) {
        declare(play, screen, "heading", "list");
    }

    /// §6.4 step 5's dispatch, from the mode's side: `root` added, and the document pointed at
    /// it from the mode's own pane.
    private static void rootMode(final Play play, final Play screen) {
        Walkthrough.addMode(play, "root");
        Walkthrough.dispatchFromDocument(play, screen, null);
    }

    /// §6.4 step 5's envelope: the prologue, which lands below the dispatch and is moved above
    /// it, and the closing tag after it. Each is a line typed as it looks, a text card.
    private static void envelope(final Play play, final Play screen) {
        // The panel's row says `document` whatever the template is called (design 44 Q11)
        ShapeshifterEditorStories.selectTemplate(play, "document");
        play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeVisible());
        Walkthrough.enter(play, line(PROLOGUE));
        play.keyboard("{Escape}");
        play.click(play.getByRole("button", "Move the selected instruction up"));
        Walkthrough.enter(play, line(EPILOGUE));
        play.keyboard("{Escape}");
        play.waitFor(() -> play.expect(play.querySelectorAll(ShapeshifterEditorStories.CARD_KIND)
                .textContents()).toEqual(List.of("text", "apply-templates", "text")));
    }

    /// §6.4 step 6. The header record: a delimiter match on `|`.
    private static void headerRecord(final Play play, final Play screen) {
        addTemplate(play, "root", "header_record");
        delimiter(play, screen, "|", false);
        play.keyboard("{Escape}");
    }

    /// §6.4 step 7. The limit that makes it the header and not every record: one match. Set in
    /// the workbench, which is where the limits are, folded away under their title bar while
    /// the template has none (ruled 2026-11-20).
    private static void onlyTheFirst(final Play play, final Play screen) {
        play.click(ShapeshifterEditorStories.workbenchButton(play));
        play.waitFor(() -> play.expect(guardAndLimits(play, "no guard · no limits"))
                .toHaveAttribute("aria-expanded", "false"));
        play.click(guardAndLimits(play, "no guard · no limits"));
        play.waitFor(() -> play.expect(play.getByLabelText("Match at most")).toBeVisible());
        play.type(play.getByLabelText("Match at most"), "1");
        play.keyboard("{Tab}");
        // The title bar says what they hold, so it is read when they are folded away again
        play.waitFor(() -> play.expect(guardAndLimits(play, "no guard · max 1")).toBeVisible());
        closeWorkbench(play);
    }

    // The workbench's guard and limits, by their title bar, which says what they hold (as a
    // pattern, but none of what it says is special in one)
    static Query guardAndLimits(final Play play, final String holds) {
        return play.getByRole("button", TextMatch.regex("^Guard and limits " + holds + "$"));
    }

    /// §6.4 step 8. The header's columns: a mode added for them, and the header record's card
    /// dispatching its first group into it.
    private static void headerColumns(final Play play, final Play screen) {
        Walkthrough.addMode(play, "header_columns");
        ShapeshifterEditorStories.selectTemplate(play, "header_record");
        dispatch(play, screen, ShapeshifterEditorStories.addLink(play), "$1", "header_columns");
    }

    /// §6.4 step 9. A column of the header: a delimiter match on `,`, its values in quotes.
    private static void headerColumn(final Play play, final Play screen) {
        addTemplate(play, "header_columns", "header_column");
        delimiter(play, screen, ",", true);
        play.keyboard("{Escape}");
    }

    /// §6.4 step 10. Each column's value added to the list the document declared. The body is
    /// empty: the capture is the whole of what this template is for.
    private static void headingCapture(final Play play, final Play screen) {
        play.click(play.findByRole("button", "+ capture"));
        final Play dialog = ShapeshifterEditorStories.dialogMatching(play, screen, "New Capture");
        dialog.type(dialog.getByLabelText("Binds into"), "heading");
        play.clear(dialog.getByLabelText("Group"));
        dialog.type(dialog.getByLabelText("Group"), "1");
        play.click(dialog.getByRole("button", StroomDom.button("OK")));
        play.waitFor(() -> play.expect(screen.queryByText(TextMatch.regex("New Capture", "i"),
                StroomDom.DIALOG_TITLE)).toBeNull());
        play.waitFor(() -> play.expect(play.getByRole("button", StroomDom.button("heading")))
                .toBeVisible());
    }

    /// §6.4 step 11. The data records: the same match as the header's, in the same mode, after
    /// it — and nothing else on screen says why it is not the header too.
    private static void dataRecord(final Play play, final Play screen) {
        addTemplate(play, "root", "data_record");
        delimiter(play, screen, "|", false);
        play.keyboard("{Escape}");
    }

    /// §6.4 step 12. The mode the record's columns are dispatched into. Where the record's body
    /// goes, `record_body`, is declared at the next step, from the variable that binds it
    /// (design 47 I).
    private static void recordBody(final Play play, final Play screen) {
        Walkthrough.addMode(play, "data_columns");
    }

    /// §6.4 step 13. The record's columns, written into `record_body` rather than straight out:
    /// a `variable`, and in its own list the dispatch into the columns' mode.
    private static void variable(final Play play, final Play screen) {
        ShapeshifterEditorStories.selectTemplate(play, "data_record");
        play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeVisible());
        play.click(ShapeshifterEditorStories.addLink(play));
        final Play card = ShapeshifterEditorStories.cardEditor(play);
        ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Instruction"), "variable");
        play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
        card.type(card.getByLabelText("Name"), "record_body");
        // Nothing declares it yet, so the card offers to, as a scalar on this template
        play.click(card.getByRole("button", StroomDom.button("declare record_body here")));
        play.waitFor(() -> play.expect(play.getByRole("button", StroomDom.button("record_body"))).toBeVisible());
        play.waitFor(() -> play.expect(card.getByLabelText("Name")).toHaveFocus());
        play.keyboard("{Enter}");
        play.waitFor(() -> play.expect(card.queryByLabelText("Name")).toBeNull());
        play.keyboard("{Escape}");
        // The variable's own list is the first of the two that end with + instruction
        dispatch(play, screen, link(play, 0), "$1", "data_columns");
    }

    /// §6.4 step 14. Nothing is written for a record whose columns wrote nothing: an `if`,
    /// testing that `record_body` exists, holding the record's tags around it.
    private static void ifExists(final Play play, final Play screen) {
        // The body's own list, after the variable
        play.click(link(play, 1));
        final Play card = ShapeshifterEditorStories.cardEditor(play);
        ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Instruction"), "if");
        // A new if arrives with its first clause row (design 47 I); each of the row's boxes is
        // named, so all of it is reachable and readable from the keyboard (§7.2, finding 6)
        play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
        // The name is typed, as an editable box's is; its arrow is what opens the list
        card.type(card.getByLabelText("Name"), "record_body");
        play.keyboard("{Tab}");
        ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Operator"), "exists");
        play.click(card.getByRole("button", StroomDom.button("OK")));
        // Taken: the card open now is the next one, its kind left to what is typed
        play.waitFor(() -> play.expect(card.getByLabelText("Instruction")).toHaveValue("auto"));
        play.keyboard("{Escape}");
        // The if's own list: the second of three now — the variable's, the if's, the body's
        play.click(link(play, 1));
        Walkthrough.enterNext(play, Walkthrough.typed("\\n   <record>${record_body}\\n   </record>"));
        play.keyboard("{Escape}");
    }

    /// §6.4 step 15. A column of a data record, matched as the header's columns are. Its match
    /// taken, the keyboard is in the card its empty body has opened, which is the next step's.
    private static void dataColumn(final Play play, final Play screen) {
        addTemplate(play, "data_columns", "data_column");
        delimiter(play, screen, ",", true);
    }

    /// §6.4 step 16. The value, named by the heading in the same position: `heading[matchCount()]`
    /// is the list's entry at this column's own count — the least discoverable thing in the six
    /// samples (§6.4 question a), written in the line inside `${…}` as the Select field's help
    /// spells it (design 47 H). The card its match's Enter opened is where it is typed.
    private static void dataLine(final Play play, final Play screen) {
        Walkthrough.enterNext(play, Walkthrough.typed(
                "\\n      <data name=\"${heading[matchCount()]}\" value=\"$1\"/>"));
        play.keyboard("{Escape}");
    }

    /// §6.4 step 17, and the keystone.
    private static void built(final Play play, final Play screen) {
        final Spy requests = play.spy(ScreenHarness.REQUEST_SPY);
        play.click(ShapeshifterEditorStories.runButton(play));
        play.waitFor(() -> play.expect(play.querySelector(OUTPUT_BOX).textContent())
                .toMatch("<data name=\"lorum\" value=\"alias modi et accusamus\"/>"));
        Walkthrough.expectTried(play, "header_record", "1");
        Walkthrough.expectTried(play, "data_record", "1");
        // The two delimiter templates in mode root read the same but for the first one's limit,
        // which its row says (design 47 §7.3, finding 8). And the templates with no mode are
        // called default, so the mode called root is only that
        Walkthrough.expectLimit(play, "header_record", "· first only");
        Walkthrough.expectLimit(play, "data_record", null);
        play.expect(play.getByText("mode: default")).toBeVisible();
        play.expect(play.getByText("mode: root")).toBeVisible();
        play.waitFor(() -> play.expect("the project the editor built",
                        () -> JsonValues.jsonContains(Walkthrough.projectBuilt(requests),
                                ShapeshifterEditorStories.withoutIds(PROJECT)))
                .toEqual(true));
    }

    // A template added to a mode with that mode's own link, named. It arrives with its match
    // line open, the keyboard in a regex's pattern.
    private static void addTemplate(final Play play, final String mode, final String name) {
        play.click(play.getByTitle("Click to add a template to mode " + mode));
        Walkthrough.named(play, name);
        play.waitFor(() -> play.expect(play.getByLabelText("Match pattern")).toHaveFocus());
    }

    // The match of the template just made made a delimiter, on its match line (design 47 G):
    // the kind, then the separator typed over the newline a delimiter starts as, and for quoted
    // values the container either side. Enter takes it and goes on into the body, which is
    // empty: the card that opens there is left open for the caller.
    private static void delimiter(final Play play, final Play screen, final String delimiter,
                                  final boolean quoted) {
        ShapeshifterEditorStories.pickFromBox(play, screen, play.getByLabelText("Kind of match"), "delimiter");
        play.waitFor(() -> play.expect(play.getByLabelText("Split on")).toHaveFocus());
        play.keyboard(delimiter);
        if (quoted) {
            play.keyboard("{Tab}\"{Tab}\"");
        }
        play.keyboard("{Enter}");
        play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
        play.expect(play.querySelector(ShapeshifterEditorStories.MATCH_CHIP).textContent()).toMatch("split on");
    }

    // The workbench closed, and the template's own match line saying what it is.
    private static void closeWorkbench(final Play play) {
        play.click(play.getByRole("button", TextMatch.regex("close the workbench", "i")));
        play.waitFor(() -> play.expect(play.queryByRole("button",
                TextMatch.regex("close the workbench", "i"))).toBeNull());
        play.waitFor(() -> play.expect(play.querySelector(ShapeshifterEditorStories.MATCH_CHIP)
                .textContent()).toMatch("split on"));
    }

    // A name declared on the selected template, of a type.
    private static void declare(final Play play, final Play screen, final String name, final String type) {
        play.click(play.findByRole("button", "+ declaration"));
        final Play dialog = ShapeshifterEditorStories.dialogMatching(play, screen, "New Declaration");
        dialog.type(dialog.getByLabelText("Name"), name);
        ShapeshifterEditorStories.pickFromBox(play, screen, dialog.getByLabelText("Type"), type);
        play.click(dialog.getByRole("button", StroomDom.button("OK")));
        play.waitFor(() -> play.expect(screen.queryByText(TextMatch.regex("New Declaration", "i"),
                StroomDom.DIALOG_TITLE)).toBeNull());
        play.waitFor(() -> play.expect(play.getByRole("button", StroomDom.button(name))).toBeVisible());
    }

    // A dispatch into a mode, from the card a link opens: an apply-templates, its Select and
    // its Mode, taken with Enter in the Select. The card that opens after it is left.
    private static void dispatch(final Play play, final Play screen, final Query link, final String select,
                                 final String mode) {
        play.waitFor(() -> play.expect(link).toBeVisible());
        play.click(link);
        final Play card = ShapeshifterEditorStories.cardEditor(play);
        ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Instruction"), "apply-templates");
        play.waitFor(() -> play.expect(card.getByLabelText("Select")).toBeVisible());
        // The mode just added is the one nothing dispatches into, so the card starts on it
        // (design 47 I)
        play.expect(card.getByLabelText("Mode")).toHaveValue(mode);
        card.type(card.getByLabelText("Select"), select);
        play.keyboard("{Enter}");
        play.waitFor(() -> play.expect(card.queryByLabelText("Select")).toBeNull());
        play.keyboard("{Escape}");
    }

    // One of the `+ instruction` links on screen, by its place: a list that holds others ends
    // with one of its own before the list it is in ends with its.
    private static Query link(final Play play, final int n) {
        return play.getAllByTitle(ShapeshifterEditorStories.ADD_LINK).nth(n);
    }

    // Text as the line takes it: a backslash and a dollar doubled, and a line break spelt \n,
    // since Enter is what adds the card; and as the keyboard types it.
    private static String line(final String value) {
        return Walkthrough.typed(value.replace("\\", "\\\\").replace("$", "$$").replace("\n", "\\n"));
    }

    // The fixture's output: the prologue, a record of eleven values, and the closing tag.
    private static String output() {
        final String[] names = {"dateTime", "uuid", "firstName", "surname", "username", "bloodGroup",
            "emotionalState", "address", "company", "companyLogo", "lorum"};
        final String[] values = {"2020-08-29T04:00:40.832", "fd296b10-8c7d-4988-83ea-5e6114c66e8a", "Chantay",
            "Huel", "teodora.toy", "AB+", "😗", "3242 Berge Centers, Port Brenda, MO Z28 6XL",
            "Ebert, Jones and McGlynn", "https://pigment.github.io/fake-logos/logos/medium/color/13.png",
            "alias modi et accusamus"};
        final StringBuilder out = new StringBuilder(PROLOGUE).append("\n   <record>");
        for (int i = 0; i < names.length; i++) {
            out.append("\n      <data name=\"").append(names[i]).append("\" value=\"").append(values[i])
                    .append("\"/>");
        }
        return out.append("\n   </record>").append(EPILOGUE).toString();
    }
}
