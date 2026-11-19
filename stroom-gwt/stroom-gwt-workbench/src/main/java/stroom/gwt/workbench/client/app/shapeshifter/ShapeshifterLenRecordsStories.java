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
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Spy;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;

import java.util.List;

/// Rung 6 of design 46's ladder — `progressive_len_records`, a step at a time (§6.5). What is
/// new here is **a binary sample**: length-prefixed records, a varint and then that many bytes of
/// name, which cannot be pasted. The sample is a stored stream, chosen from the sample page's
/// list (ruled 2026-11-12); the match is a framed sequence, a `read` and a `take` whose length is
/// the value the `read` labelled; and the template reads its input raw.
///
/// The stream is the story's: the list is served one row, and the stream viewer one record. The
/// preview is served as every rung's is, so what the run makes of the bytes is the engine's to
/// show, and is shown in `ShapeshifterResourceEndpointsTest` and `TestStreamSamples`, where the
/// bytes are read as stored rather than as text (design 47 §7.17).
///
/// The mechanism is [Walkthrough]'s; this file is only the steps.
public final class ShapeshifterLenRecordsStories {

    private static final String TITLE = "App/Shapeshifter/ShapeshifterLenRecords";

    // fixtures/projects/progressive_len_records/input.bin, a byte to a character as the preview
    // shows a sample that is not UTF-8 (TraceChars): 5 "Alice", 3 "Bob", 4 "Carl"
    private static final String INPUT = "\u0005Alice\u0003Bob\u0004Carl";

    private static final String OUTPUT_BOX = ".output-pane__box";
    // A part's kind, as the parts list shows it
    private static final String PART_KIND = ".parts__kind";

    // The stream viewer's reply for stream 42's record, as DataResource.fetch() returns it: the
    // sample is set once the viewer has it
    private static final String DATA = """
            {"type": "data", "feedName": "TEST_FEED", "streamTypeName": "Raw Events",
              "classification": "UNKNOWN CLASSIFICATION",
              "sourceLocation": {"metaId": 42, "partIndex": 0, "recordIndex": 0, "highlights": []},
              "itemRange": {"offset": 0, "length": 1}, "totalItemCount": {"count": 1, "exact": true},
              "totalCharacterCount": {"count": 15, "exact": true}, "totalBytes": 15,
              "availableChildStreamTypes": [null], "data": """
                                       + ShapeshifterEditorStories.asJsonString(INPUT) + """
            , "html": false,
              "dataType": "NON_SEGMENTED", "displayMode": "TEXT"}""";

    // fixtures/projects/progressive_len_records/project.json, with its "version" made the one the
    // editor writes, as every rung's is
    private static final String PROJECT = """
            {
             "name": "progressive_len_records",
             "version": 5,
             "source": {"buffer_size": 1000000, "ignore_errors": true, "encoding": "utf-8"},
             "templates": [
              {"id": "-", "name": "document", "match": "source",
               "match_limits": {"min_match": 0, "max_match": -1},
               "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                             "max_depth": 64}}]},
              {"id": "-", "name": "root",
               "match": {"parts": [{"read": {"as": "varint", "label": "len"}},
                                   {"take": {"length": {"label": "len"}, "label": "name"}}]},
               "match_limits": {"min_match": 0, "max_match": -1},
               "body": [{"text": "<rec name=\\""}, {"value-of": {"parts": [{"capture": {"label": "name"}}]}},
                        {"text": "\\"/>\\n"}],
               "encoding": "raw"}
             ]
            }""";

    // The run the last stage is served: the three records, and the fixture's own output for them
    private static final String TRACE = """
            {
              "compiled": true, "sampleRead": true,
              "input": """
                                        + ShapeshifterEditorStories.asJsonString(INPUT) + """
            ,
              "output": """
                                        + ShapeshifterEditorStories.asJsonString(
            "<rec name=\"Alice\"/>\n<rec name=\"Bob\"/>\n<rec name=\"Carl\"/>\n") + """
            ,
              "frames": [
                {"id": 1, "parentId": 0, "templateId": "fixture-document", "templateName": "document", "matchIndex": 0,
                 "depth": 0, "inputOffset": 0, "inputLength": 15, "contentOffset": 0, "contentLength": 15},
                {"id": 2, "parentId": 1, "templateId": "fixture-root", "templateName": "root", "matchIndex": 0,
                 "depth": 1, "inputOffset": 0, "inputLength": 6, "contentOffset": 0, "contentLength": 6},
                {"id": 3, "parentId": 1, "templateId": "fixture-root", "templateName": "root", "matchIndex": 1,
                 "depth": 1, "inputOffset": 6, "inputLength": 4, "contentOffset": 6, "contentLength": 4},
                {"id": 4, "parentId": 1, "templateId": "fixture-root", "templateName": "root", "matchIndex": 2,
                 "depth": 1, "inputOffset": 10, "inputLength": 5, "contentOffset": 10, "contentLength": 5}
              ],
              "captures": [],
              "groups": [], "attempts": [], "guards": [],
              "attemptsSeen": 3,
              "timings": [{"templateId": "fixture-root", "attempts": 3, "matched": 3, "nanos": 0}],
              "outputs": [
                {"frameId": 1, "offset": 0, "length": 57, "unit": "BYTES"},
                {"frameId": 2, "offset": 0, "length": 20, "unit": "BYTES"},
                {"frameId": 3, "offset": 20, "length": 18, "unit": "BYTES"},
                {"frameId": 4, "offset": 38, "length": 19, "unit": "BYTES"}
              ],
              "instructions": [],
              "messages": [],
              "runNanos": 90000
            }""";

    /// The walkthrough, numbered sequentially so the screenshots sort into the order they
    /// happened; each stage's javadoc names the §6.5 step it drives.
    private static final List<Walkthrough.Stage> STAGES = List.of(
            new Walkthrough.Stage("Lr01Settings", ShapeshifterLenRecordsStories::settings),
            new Walkthrough.Stage("Lr02Stream", ShapeshifterLenRecordsStories::stream),
            new Walkthrough.Stage("Lr03Template", ShapeshifterLenRecordsStories::template),
            new Walkthrough.Stage("Lr04Raw", ShapeshifterLenRecordsStories::raw),
            new Walkthrough.Stage("Lr05Parts", ShapeshifterLenRecordsStories::parts),
            new Walkthrough.Stage("Lr06Length", ShapeshifterLenRecordsStories::length),
            new Walkthrough.Stage("Lr07Name", ShapeshifterLenRecordsStories::name),
            new Walkthrough.Stage("Lr08Body", ShapeshifterLenRecordsStories::body),
            new Walkthrough.Stage("Lr09Built", ShapeshifterLenRecordsStories::built));

    private ShapeshifterLenRecordsStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        final RestFixtures stream = RestFixtures.builder()
                .post("/data/v1/fetch", RestReply.json(DATA))
                .build();
        Walkthrough.register(registry, TITLE, ShapeshifterLenRecordsStories.class, STAGES,
                stream.followedBy(Walkthrough.routes(Walkthrough.emptyTrace(INPUT))),
                stream.followedBy(Walkthrough.routes(TRACE)));
    }

    /// §6.5 step 1. The name, the buffer, the encoding, and errors ignored.
    private static void settings(final Play play, final Play screen) {
        play.waitFor(() -> play.expect(play.getByText("document",
                ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
        play.click(play.getByText("Settings", ShapeshifterEditorStories.TEMPLATE_NAME));
        play.waitFor(() -> play.expect(play.getByText("Source — the document")).toBeVisible());
        play.clear(play.getByLabelText("Project name"));
        play.type(play.getByLabelText("Project name"), "progressive_len_records");
        play.clear(play.getByLabelText("Buffer size"));
        play.type(play.getByLabelText("Buffer size"), "1000000");
        play.keyboard("{Tab}");
        ShapeshifterEditorStories.pickFromBox(play, screen, play.getByLabelText("Encoding"), "utf-8");
        play.click(play.getByLabelText("Ignore errors"));
    }

    /// §6.5 step 2, which a paste could not do: the sample is a stored stream, chosen from the
    /// page's list. It is the sample once the stream viewer has its record.
    private static void stream(final Play play, final Play screen) {
        ShapeshifterEditorStories.openSamplePage(play);
        ShapeshifterEditorStories.pickFromBox(play, screen, play.getByLabelText("Sample data from"), "a stream");
        play.waitFor(() -> play.expect(play.getByText("TEST_FEED")).toBeVisible());
        play.click(play.getByText("TEST_FEED"));
        play.waitFor(() -> play.expect(play.getByText("TEST_FEED 42")).toBeVisible());
    }

    /// §6.5 step 3. The one template, in the default mode with the document. It arrives with its
    /// match line open for a regex; this match is a framed sequence, which is the workbench's.
    private static void template(final Play play, final Play screen) {
        play.click(play.getByTitle("Click to add a template to mode default"));
        Walkthrough.named(play, "root");
        play.waitFor(() -> play.expect(play.getByLabelText("Match pattern")).toHaveFocus());
        play.keyboard("{Escape}");
    }

    /// §6.5 step 4. The template reads its input raw: its own encoding, in its title's settings.
    private static void raw(final Play play, final Play screen) {
        play.click(play.getByRole("button", ShapeshifterEditorStories.EDIT_TITLE));
        play.waitFor(() -> play.expect(play.getByLabelText("Template name")).toHaveFocus());
        ShapeshifterEditorStories.pickFromBox(play, screen, play.getByLabelText("Encoding"), "raw");
        play.click(play.getByRole("button", StroomDom.button("OK")));
        play.waitFor(() -> play.expect(play.queryByLabelText("Encoding")).toBeNull());
    }

    /// §6.5 step 5. The match made a framed sequence, in the workbench. It starts as one pattern
    /// part.
    private static void parts(final Play play, final Play screen) {
        play.click(ShapeshifterEditorStories.workbenchButton(play));
        play.waitFor(() -> play.expect(play.getByLabelText("Match kind")).toBeInTheDocument());
        ShapeshifterEditorStories.pickFromBox(play, screen, play.getByLabelText("Match kind"), "framed sequence");
        play.waitFor(() -> play.expect(play.querySelector(PART_KIND)).toHaveTextContent("pattern"));
    }

    /// §6.5 step 6. The first part made the length: a `read` of a varint, labelled `len`.
    private static void length(final Play play, final Play screen) {
        play.click(play.querySelector(PART_KIND));
        play.click(play.getByRole("button", TextMatch.containing("Edit the selected part")));
        final Play dialog = ShapeshifterEditorStories.dialogMatching(play, screen, "Edit Part");
        ShapeshifterEditorStories.pickFromBox(play, screen, dialog.getByLabelText("Kind"), "read");
        ShapeshifterEditorStories.pickFromBox(play, screen, dialog.getByLabelText("As"), "varint");
        dialog.type(dialog.getByLabelText("Label"), "len");
        ok(play, screen, "Edit Part");
        play.waitFor(() -> play.expect(play.querySelector(PART_KIND)).toHaveTextContent("read"));
    }

    /// §6.5 step 7. The name: a `take` as long as the value `len` holds. Its Length is typed —
    /// nothing offers the labels an earlier part has defined (design 46 §6.5's question).
    private static void name(final Play play, final Play screen) {
        play.click(play.getByRole("button", TextMatch.containing("Add a part after the selected one")));
        final Play dialog = ShapeshifterEditorStories.dialogMatching(play, screen, "New Part");
        ShapeshifterEditorStories.pickFromBox(play, screen, dialog.getByLabelText("Length from"), "label's value");
        play.clear(dialog.getByLabelText("Length value"));
        dialog.type(dialog.getByLabelText("Length value"), "len");
        dialog.type(dialog.getByLabelText("Label"), "name");
        ok(play, screen, "New Part");
        play.waitFor(() -> play.expect(play.querySelectorAll(PART_KIND).textContents())
                .toEqual(List.of("read", "take")));
    }

    /// §6.5 step 8. The record written as it looks: one line, which the field splits into a
    /// text, a value-of and a text (design 47 A and H). The workbench closed first.
    private static void body(final Play play, final Play screen) {
        play.click(play.getByRole("button", TextMatch.regex("close the workbench", "i")));
        play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeVisible());
        Walkthrough.enter(play, "<rec name=\"$name\"/>\\n");
        play.keyboard("{Escape}");
        play.waitFor(() -> play.expect(play.querySelectorAll(ShapeshifterEditorStories.CARD_KIND).textContents())
                .toEqual(List.of("text", "value-of", "text")));
    }

    /// §6.5 step 9, and the keystone.
    private static void built(final Play play, final Play screen) {
        final Spy requests = play.spy(ScreenHarness.REQUEST_SPY);
        play.click(ShapeshifterEditorStories.runButton(play));
        play.waitFor(() -> play.expect(play.querySelector(OUTPUT_BOX).textContent())
                .toMatch("<rec name=\"Carl\"/>"));
        Walkthrough.expectTried(play, "root", "3");
        play.waitFor(() -> play.expect("the project the editor built",
                        () -> JsonValues.jsonContains(Walkthrough.projectBuilt(requests),
                                ShapeshifterEditorStories.withoutIds(PROJECT)))
                .toEqual(true));
    }

    // A part dialog's OK, and the dialog gone
    private static void ok(final Play play, final Play screen, final String title) {
        final Play dialog = ShapeshifterEditorStories.dialogMatching(play, screen, title);
        play.click(dialog.getByRole("button", StroomDom.button("OK")));
        play.waitFor(() -> play.expect(screen.queryByText(TextMatch.regex(title, "i"), StroomDom.DIALOG_TITLE))
                .toBeNull());
    }
}
