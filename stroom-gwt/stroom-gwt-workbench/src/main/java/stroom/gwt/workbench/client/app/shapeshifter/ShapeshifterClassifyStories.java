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
import stroom.gwt.workbench.framework.client.play.Spy;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;

import java.util.List;

/// Rung 3 of design 46's ladder — `classify_alerts`, a step at a time (§6.2). What is new is
/// **`classify`**, where every template runs rather than one: a sweep that tags a region instead
/// of consuming it.
///
/// It is the third and last of the dispatch strategies a walkthrough can reach, and the reason
/// this rung was walked next: the panel and the dispatch card learnt to say which strategy is in
/// force (§10 W4), and `CLASSIFY` was the one branch of that with no screenshot — asserted by a
/// unit test and by reading, which is the shape of both findings this ladder has turned up.
///
/// **Two orders the step table gives that the editor does not allow**, the same two rung 2 met
/// (§6.1a): the root has to be renamed, which §6.2's table omits, and the `flags` mode has to be
/// made by the first template that lives in it, because the dispatch card's Mode is a picker
/// over modes that already exist.
public final class ShapeshifterClassifyStories {

    private static final String TITLE = "App/Shapeshifter/ShapeshifterClassify";

    // fixtures/projects/classify_alerts. Three lines, and the third is the lesson: `code=98` is
    // deliberately absent from the output.
    private static final String SAMPLE_TEXT = "INFO started\n"
                                              + "ERROR connection lost code=57\n"
                                              + "WARN retrying code=98\n";
    private static final String SAMPLE = "INFO started{Enter}"
                                         + "ERROR connection lost code=57{Enter}"
                                         + "WARN retrying code=98{Enter}";

    private static final String ACE_CONTENT = ".ace_content";

    // The output pane's text, read whole: each instruction is marked as its own span.
    private static final String OUTPUT_BOX = ".output-pane__box";

    // The fixture's project, with its "version" raised from 4 to the version a project built
    // today gets (§6.1a records why: the version is when the fixture was written, not an
    // authoring act).
    private static final String PROJECT = """
            {
             "name": "classify_alerts",
             "version": 5,
             "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "utf-8"},
             "templates": [
              {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
               "body": [
                {"text": "<flags>"},
                {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                     "mode": "flags", "dispatch": "classify"}},
                {"text": "</flags>\\n"}]},
              {"id": "00000000-0000-0000-0000-000000000002", "name": "has_error", "mode": "flags",
               "match": {"regex": {"pattern": "ERROR"}},
               "body": [{"text": "<error/>"}]},
              {"id": "00000000-0000-0000-0000-000000000003", "name": "has_timeout", "mode": "flags",
               "match": {"regex": {"pattern": "timed out"}},
               "body": [{"text": "<timeout/>"}]},
              {"id": "00000000-0000-0000-0000-000000000004", "name": "first_code", "mode": "flags",
               "match": {"regex": {"pattern": "code=([0-9]+)"}},
               "body": [{"text": "<code>"}, {"value-of": {"parts": [{"capture": {"group": 1}}]}},
                        {"text": "</code>"}]}
             ]
            }""";

    // The run the last stage is served. Two frames under the source and no more: `has_timeout`
    // matched nothing, and `first_code` ran **once, at its leftmost match**, which is why
    // `code=98` on the third line is nowhere in the output. The whole file is one region, so
    // `<flags>` wraps the lot rather than appearing per line.
    private static final String TRACE = """
            {
              "compiled": true, "sampleRead": true,
              "input": "INFO started\\nERROR connection lost code=57\\nWARN retrying code=98\\n",
              "output": "<flags><error/><code>57</code></flags>\\n",
              "frames": [
                {"id": 1, "parentId": 0, "templateId": "00000000-0000-0000-0000-000000000001",
                 "templateName": "source", "matchIndex": 0, "depth": 0,
                 "inputOffset": 0, "inputLength": 65, "contentOffset": 0, "contentLength": 65},
                {"id": 2, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000002",
                 "templateName": "has_error", "matchIndex": 0, "depth": 1,
                 "inputOffset": 13, "inputLength": 5, "contentOffset": 13, "contentLength": 5,
                 "content": "ERROR"},
                {"id": 3, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000004",
                 "templateName": "first_code", "matchIndex": 0, "depth": 1,
                 "inputOffset": 35, "inputLength": 7, "contentOffset": 35, "contentLength": 7,
                 "content": "code=57"}
              ],
              "captures": [],
              "groups": [], "attempts": [], "guards": [],
              "attemptsSeen": 3, "timings": [],
              "outputs": [
                {"frameId": 1, "offset": 0, "length": 39, "unit": "BYTES"},
                {"frameId": 2, "offset": 7, "length": 8, "unit": "BYTES"},
                {"frameId": 3, "offset": 15, "length": 15, "unit": "BYTES"}
              ],
              "instructions": [
                {"frameId": 1, "index": 0, "offset": 0, "length": 7, "unit": "BYTES"},
                {"frameId": 1, "index": 1, "offset": 7, "length": 23, "unit": "BYTES"},
                {"frameId": 1, "index": 2, "offset": 30, "length": 9, "unit": "BYTES"},
                {"frameId": 2, "index": 0, "offset": 7, "length": 8, "unit": "BYTES"},
                {"frameId": 3, "index": 0, "offset": 15, "length": 6, "unit": "BYTES"},
                {"frameId": 3, "index": 1, "offset": 21, "length": 2, "unit": "BYTES"},
                {"frameId": 3, "index": 2, "offset": 23, "length": 7, "unit": "BYTES"}
              ],
              "messages": [],
              "runNanos": 150000
            }""";

    private static final List<Walkthrough.Stage> STAGES = List.of(
            new Walkthrough.Stage("Cl01Settings", ShapeshifterClassifyStories::settings),
            new Walkthrough.Stage("Cl02Sample", ShapeshifterClassifyStories::sample),
            new Walkthrough.Stage("Cl03RootRenamed", ShapeshifterClassifyStories::rootRenamed),
            new Walkthrough.Stage("Cl04HasError", ShapeshifterClassifyStories::hasError),
            new Walkthrough.Stage("Cl05DeadMode", ShapeshifterClassifyStories::deadMode),
            new Walkthrough.Stage("Cl06OpenTag", ShapeshifterClassifyStories::openTag),
            new Walkthrough.Stage("Cl07Dispatch", ShapeshifterClassifyStories::dispatch),
            new Walkthrough.Stage("Cl08CloseTag", ShapeshifterClassifyStories::closeTag),
            new Walkthrough.Stage("Cl09HasTimeout", ShapeshifterClassifyStories::hasTimeout),
            new Walkthrough.Stage("Cl10FirstCode", ShapeshifterClassifyStories::firstCode),
            new Walkthrough.Stage("Cl11Built", ShapeshifterClassifyStories::built));

    private ShapeshifterClassifyStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        Walkthrough.register(registry, TITLE, ShapeshifterClassifyStories.class, STAGES,
                Walkthrough.routes(Walkthrough.emptyTrace(SAMPLE_TEXT)),
                Walkthrough.routes(TRACE));
    }

    /// §6.2 step 1.
    private static void settings(final Play play, final Play screen) {
        play.waitFor(() -> play.expect(play.getByText("document",
                ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
        play.click(play.getByText("Settings", ShapeshifterEditorStories.TEMPLATE_NAME));
        play.waitFor(() -> play.expect(play.getByText("Source — the document")).toBeVisible());
        play.clear(play.getByLabelText("Project name"));
        play.type(play.getByLabelText("Project name"), "classify_alerts");
        ShapeshifterEditorStories.pickFromBox(play, screen,
                play.getByLabelText("Encoding"), "utf-8");
    }

    /// §6.2 step 2. Three lines chosen so the output can teach: one with neither flag, one with
    /// an error and a code, and one with a code that will not appear.
    private static void sample(final Play play, final Play screen) {
        ShapeshifterEditorStories.openSamplePage(play);
        play.click(play.querySelector(ACE_CONTENT));
        play.waitFor(() -> play.expect(play.querySelector(ShapeshifterEditorStories.ACE_INPUT))
                .toHaveFocus());
        play.keyboard(SAMPLE);
        play.waitFor(() -> play.expect(play.querySelector(ACE_CONTENT).textContent())
                .toMatch("code=98"));
    }

    /// §6.2 step 3, and the step its table does not have — the third rung to need it (§6.1a).
    private static void rootRenamed(final Play play, final Play screen) {
        ShapeshifterEditorStories.selectTemplate(play, "document");
        play.click(play.getByRole("button", "Edit template"));
        final Play renaming = ShapeshifterEditorStories.dialogMatching(play, screen, "template");
        play.clear(renaming.getByLabelText("Name"));
        renaming.type(renaming.getByLabelText("Name"), "source");
        play.click(renaming.getByRole("button", StroomDom.button("OK")));
        play.waitFor(() -> play.expect(play.getByText("source",
                ShapeshifterEditorStories.STRIP_NAME)).toBeInTheDocument());
    }

    /// §6.2 step 6, ahead of steps 4 and 5: the `flags` mode has to exist before the card can be
    /// pointed at it, and a template's own dialog is the only place a mode is made.
    private static void hasError(final Play play, final Play screen) {
        Walkthrough.addTemplate(play, screen, "has_error", "flags", true, "ERROR");
        Walkthrough.enter(play, "<error/>");
    }

    /// The window between the halves of the two-part edit, met for the third time.
    private static void deadMode(final Play play, final Play screen) {
        play.waitFor(() -> play.expect(play.getByText("nothing dispatches into this",
                ".template-panel__mode-note")).toBeVisible());
    }

    /// §6.2 step 4's first half: the tag that opens the region, moved above the dispatch.
    private static void openTag(final Play play, final Play screen) {
        ShapeshifterEditorStories.selectTemplate(play, "document");
        play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toBeInTheDocument());
        Walkthrough.enter(play, "<flags>");
        // The row just added is the selected one, and the toolbar acts on it
        play.click(play.getByRole("button", "Move the selected instruction up"));
    }

    /// §6.2 steps 4 and 5: the mode, and **`classify`** — every matching template runs, nothing
    /// consumes.
    ///
    /// This is the stage the rung was walked for. The panel and the card both say the strategy
    /// now (§10 W4), and until this stage was driven that branch had never been on screen.
    private static void dispatch(final Play play, final Play screen) {
        play.click(ShapeshifterEditorStories.cardLine(play, "apply-templates"));
        final Play card = ShapeshifterEditorStories.cardEditor(play);
        ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Mode"), "flags");
        ShapeshifterEditorStories.pickFromBox(play, screen,
                card.getByLabelText("Dispatch"), "classify");
        play.click(card.getByRole("button", StroomDom.button("OK")));
        play.waitFor(() -> play.expect(play.queryByText("nothing dispatches into this",
                ".template-panel__mode-note")).toBeNull());
        // The mode says how it is dispatched, and the card that chose it says so too. Under
        // `classify` the panel's ordered list means nothing at all — no priority, no tie to
        // break — so there is no order for an author to reason about, and the one thing worth
        // telling them is which rule is in force.
        play.waitFor(() -> play.expect(play.getByText("dispatched with classify",
                ".template-panel__mode-note")).toBeVisible());
        play.expect(play.getByText(TextMatch.containing("classify"), ".body-card__note"))
                .toBeVisible();
    }

    /// §6.2 step 4's second half.
    private static void closeTag(final Play play, final Play screen) {
        Walkthrough.enter(play, "</flags>\\n");
    }

    /// §6.2 step 7. A template that will match nothing in this sample — and under `classify`
    /// that costs nothing, because templates do not compete.
    private static void hasTimeout(final Play play, final Play screen) {
        Walkthrough.addTemplate(play, screen, "has_timeout", "flags", false, "timed out");
        Walkthrough.enter(play, "<timeout/>");
    }

    /// §6.2 step 8. Three cards again — `<code>`, the capture, `</code>` — the shape §10 W2
    /// counts, in a third sample that shares nothing else with the first two.
    private static void firstCode(final Play play, final Play screen) {
        Walkthrough.addTemplate(play, screen, "first_code", "flags", false, "code=([[0-9]+)");
        Walkthrough.enter(play, "<code>$1</code>");
    }

    /// §6.2 step 9, and the keystone — **and the output is the lesson** (§6.2's own finding).
    ///
    /// `<timeout/>` is absent because nothing matched it. `code=98` is absent for a quite
    /// different reason: under `classify` each template runs **once, at its leftmost match**, so
    /// `first_code` is the *first* code and not each one. An author who reads `classify` as
    /// "every match" rather than "every template" will read this output as a bug, and this
    /// fixture is where that difference is visible in four lines.
    private static void built(final Play play, final Play screen) {
        final Spy requests = play.spy(ScreenHarness.REQUEST_SPY);
        play.click(ShapeshifterEditorStories.runButton(play));
        play.waitFor(() -> play.expect(play.getByText(TextMatch.containing("<error/>")))
                .toBeInTheDocument());
        play.waitFor(() -> play.expect("the project the editor built",
                        () -> JsonValues.jsonContains(Walkthrough.projectBuilt(requests),
                                ShapeshifterEditorStories.withoutIds(PROJECT)))
                .toEqual(true));
        // Read off the pane's whole text, not a single element: the output pane marks each
        // instruction as its own span, so `<code>57</code>` is three of them and no one element
        // holds it.
        //
        // Nothing matched has_timeout...
        play.expect(play.querySelector(OUTPUT_BOX).textContent()).not().toMatch("<timeout/>");
        // ...and the second code is not there either, which is `classify` running each template
        // once, at its leftmost match, rather than at every match.
        play.expect(play.querySelector(OUTPUT_BOX).textContent()).toMatch("<code>57</code>");
        play.expect(play.querySelector(OUTPUT_BOX).textContent()).not().toMatch("98");
    }
}
