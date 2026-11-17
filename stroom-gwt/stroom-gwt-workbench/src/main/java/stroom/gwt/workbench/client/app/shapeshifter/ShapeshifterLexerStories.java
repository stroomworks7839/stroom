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

/// Rung 2 of design 46's ladder — `lexer_tokens`, a step at a time (§6.1). What is new here is
/// **dispatch strategy at the dispatch site**: one mode of six templates, chosen by `lexer`,
/// which takes the longest match and uses the panel's order only to break ties.
///
/// The mechanism is [Walkthrough]'s; this file is only the steps.
///
/// **Two places the step table could not be driven as written**, both recorded in §6.1a rather
/// than smoothed over:
///
/// - **The table has no rename.** Every sample's root template is called `source` and the editor
///   calls it `document`, so rung 1 renames it at its step 8. §6.1 step 3 only opens the row.
///   Without the rename the keystone cannot match, so the rename is done here and the table is
///   short a step.
/// - **A mode cannot be made from the dispatch card.** §6.1 step 5 is `Mode` → `New…` `tokens`
///   on the document's `apply-templates`. That button exists only on the *template* dialog
///   (`TemplateEdit.ui.xml`); the card's Mode is a picker over modes that already exist
///   (`InstructionEdit.ui.xml`). So the first token template has to be made first, which is what
///   creates the mode, and only then can the card be pointed at it. The table's steps 7 and 5/6
///   swap places, and the two-part edit has exactly one order the editor allows.
public final class ShapeshifterLexerStories {

    private static final String TITLE = "App/Shapeshifter/ShapeshifterLexer";

    // The engine fixture's own input and output (fixtures/projects/lexer_tokens).
    private static final String SAMPLE_TEXT = "total => count=42 plus 7";
    private static final String SAMPLE = SAMPLE_TEXT;
    private static final String OUTPUT =
            "<w>total</w><arrow/><w>count</w><op>=</op><n>42</n><w>plus</w><n>7</n>";

    private static final String ACE_CONTENT = ".ace_content";

    // fixtures/projects/lexer_tokens/project.json, with two changes. Its "version" is 4, which is
    // when it was written, and a project built today gets today's version as rung 1's does. The
    // version is not cosmetic (§3.1) but it is not an authoring act either — no author of a new
    // project chooses an old one — so the keystone compares against the version the editor
    // writes. Every rung after this one carries a different version and needs the same note.
    //
    // And `space`'s `"body": []` is dropped, because the engine's own writer would drop it:
    // ProjectJson writes a body only `if (!template.body().isEmpty())` and reads a missing one
    // as empty. So the editor and the config writer agree, and the fixture is the odd one out —
    // round-tripping `lexer_tokens` through ProjectJson would lose that line. This is §3.1's
    // "explicit defaults" family, found from the other end: not the UI writing a default it was
    // not asked for, but a fixture spelling one its own writer does not.
    private static final String PROJECT = """
            {
             "name": "lexer_tokens",
             "version": 5,
             "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "utf-8"},
             "templates": [
              {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
               "body": [
                {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                     "mode": "tokens", "dispatch": "lexer"}}]},
              {"id": "00000000-0000-0000-0000-000000000002", "name": "assign", "mode": "tokens",
               "match": {"regex": {"pattern": "="}},
               "body": [{"text": "<op>=</op>"}]},
              {"id": "00000000-0000-0000-0000-000000000003", "name": "arrow", "mode": "tokens",
               "match": {"regex": {"pattern": "=>"}},
               "body": [{"text": "<arrow/>"}]},
              {"id": "00000000-0000-0000-0000-000000000004", "name": "word", "mode": "tokens",
               "match": {"regex": {"pattern": "[a-z]+"}},
               "body": [{"text": "<w>"}, {"value-of": {"parts": [{"capture": {"group": 0}}]}},
                        {"text": "</w>"}]},
              {"id": "00000000-0000-0000-0000-000000000005", "name": "number", "mode": "tokens",
               "match": {"regex": {"pattern": "[0-9]+"}},
               "body": [{"text": "<n>"}, {"value-of": {"parts": [{"capture": {"group": 0}}]}},
                        {"text": "</n>"}]},
              {"id": "00000000-0000-0000-0000-000000000006", "name": "space", "mode": "tokens",
               "consume": true,
               "match": {"regex": {"pattern": " +"}}}
             ]
            }""";

    // The run the last stage is served: the fixture's input and output, and a frame per match
    // that opened one. A consuming match advances the cursor and runs its body but opens no
    // frame (MatchRole), so the four spaces are not here — which is itself worth seeing in the
    // input pane, where the gaps between the marks are the spaces that were eaten.
    private static final String TRACE = """
            {
              "compiled": true, "sampleRead": true,
              "input": "total => count=42 plus 7",
              "output": "<w>total</w><arrow/><w>count</w><op>=</op><n>42</n><w>plus</w><n>7</n>",
              "frames": [
                {"id": 1, "parentId": 0, "templateId": "00000000-0000-0000-0000-000000000001",
                 "templateName": "source", "matchIndex": 0, "depth": 0,
                 "inputOffset": 0, "inputLength": 24, "contentOffset": 0, "contentLength": 24},
                {"id": 2, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000004",
                 "templateName": "word", "matchIndex": 0, "depth": 1,
                 "inputOffset": 0, "inputLength": 5, "contentOffset": 0, "contentLength": 5,
                 "content": "total"},
                {"id": 3, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000003",
                 "templateName": "arrow", "matchIndex": 0, "depth": 1,
                 "inputOffset": 6, "inputLength": 2, "contentOffset": 6, "contentLength": 2,
                 "content": "=>"},
                {"id": 4, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000004",
                 "templateName": "word", "matchIndex": 1, "depth": 1,
                 "inputOffset": 9, "inputLength": 5, "contentOffset": 9, "contentLength": 5,
                 "content": "count"},
                {"id": 5, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000002",
                 "templateName": "assign", "matchIndex": 0, "depth": 1,
                 "inputOffset": 14, "inputLength": 1, "contentOffset": 14, "contentLength": 1,
                 "content": "="},
                {"id": 6, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000005",
                 "templateName": "number", "matchIndex": 0, "depth": 1,
                 "inputOffset": 15, "inputLength": 2, "contentOffset": 15, "contentLength": 2,
                 "content": "42"},
                {"id": 7, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000004",
                 "templateName": "word", "matchIndex": 2, "depth": 1,
                 "inputOffset": 18, "inputLength": 4, "contentOffset": 18, "contentLength": 4,
                 "content": "plus"},
                {"id": 8, "parentId": 1, "templateId": "00000000-0000-0000-0000-000000000005",
                 "templateName": "number", "matchIndex": 1, "depth": 1,
                 "inputOffset": 23, "inputLength": 1, "contentOffset": 23, "contentLength": 1,
                 "content": "7"}
              ],
              "captures": [],
              "groups": [], "attempts": [], "guards": [],
              "attemptsSeen": 11, "timings": [],
              "outputs": [
                {"frameId": 1, "offset": 0, "length": 70, "unit": "BYTES"},
                {"frameId": 2, "offset": 0, "length": 12, "unit": "BYTES"},
                {"frameId": 3, "offset": 12, "length": 8, "unit": "BYTES"},
                {"frameId": 4, "offset": 20, "length": 12, "unit": "BYTES"},
                {"frameId": 5, "offset": 32, "length": 10, "unit": "BYTES"},
                {"frameId": 6, "offset": 42, "length": 9, "unit": "BYTES"},
                {"frameId": 7, "offset": 51, "length": 11, "unit": "BYTES"},
                {"frameId": 8, "offset": 62, "length": 8, "unit": "BYTES"}
              ],
              "instructions": [
                {"frameId": 1, "index": 0, "offset": 0, "length": 70, "unit": "BYTES"},
                {"frameId": 2, "index": 0, "offset": 0, "length": 3, "unit": "BYTES"},
                {"frameId": 2, "index": 1, "offset": 3, "length": 5, "unit": "BYTES"},
                {"frameId": 2, "index": 2, "offset": 8, "length": 4, "unit": "BYTES"},
                {"frameId": 3, "index": 0, "offset": 12, "length": 8, "unit": "BYTES"},
                {"frameId": 4, "index": 0, "offset": 20, "length": 3, "unit": "BYTES"},
                {"frameId": 4, "index": 1, "offset": 23, "length": 5, "unit": "BYTES"},
                {"frameId": 4, "index": 2, "offset": 28, "length": 4, "unit": "BYTES"},
                {"frameId": 5, "index": 0, "offset": 32, "length": 10, "unit": "BYTES"},
                {"frameId": 6, "index": 0, "offset": 42, "length": 3, "unit": "BYTES"},
                {"frameId": 6, "index": 1, "offset": 45, "length": 2, "unit": "BYTES"},
                {"frameId": 6, "index": 2, "offset": 47, "length": 4, "unit": "BYTES"},
                {"frameId": 7, "index": 0, "offset": 51, "length": 3, "unit": "BYTES"},
                {"frameId": 7, "index": 1, "offset": 54, "length": 4, "unit": "BYTES"},
                {"frameId": 7, "index": 2, "offset": 58, "length": 4, "unit": "BYTES"},
                {"frameId": 8, "index": 0, "offset": 62, "length": 3, "unit": "BYTES"},
                {"frameId": 8, "index": 1, "offset": 65, "length": 1, "unit": "BYTES"},
                {"frameId": 8, "index": 2, "offset": 66, "length": 4, "unit": "BYTES"}
              ],
              "messages": [],
              "runNanos": 180000
            }""";

    /// The walkthrough. Numbered sequentially so the screenshots sort into the order they
    /// happened; each stage's javadoc names the §6.1 step it drives, because two of them had to
    /// move for the UI to allow them at all.
    private static final List<Walkthrough.Stage> STAGES = List.of(
            new Walkthrough.Stage("Lx01Settings", ShapeshifterLexerStories::settings),
            new Walkthrough.Stage("Lx02Sample", ShapeshifterLexerStories::sample),
            new Walkthrough.Stage("Lx03RootRenamed", ShapeshifterLexerStories::rootRenamed),
            new Walkthrough.Stage("Lx04BlankSelect", ShapeshifterLexerStories::blankSelect),
            new Walkthrough.Stage("Lx05Assign", ShapeshifterLexerStories::assign),
            new Walkthrough.Stage("Lx06DeadMode", ShapeshifterLexerStories::deadMode),
            new Walkthrough.Stage("Lx07Dispatch", ShapeshifterLexerStories::dispatch),
            new Walkthrough.Stage("Lx08Arrow", ShapeshifterLexerStories::arrow),
            new Walkthrough.Stage("Lx09Word", ShapeshifterLexerStories::word),
            new Walkthrough.Stage("Lx10Number", ShapeshifterLexerStories::number),
            new Walkthrough.Stage("Lx11Space", ShapeshifterLexerStories::space),
            new Walkthrough.Stage("Lx12Built", ShapeshifterLexerStories::built));

    private ShapeshifterLexerStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        Walkthrough.register(registry, TITLE, ShapeshifterLexerStories.class, STAGES,
                Walkthrough.routes(Walkthrough.emptyTrace(SAMPLE_TEXT)),
                Walkthrough.routes(TRACE));
    }

    /// §6.1 step 1. The project's name and encoding; its buffer size and error handling are
    /// already what the fixture asks for, so only two fields are touched.
    private static void settings(final Play play, final Play screen) {
        play.waitFor(() -> play.expect(play.getByText("document",
                ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
        play.click(play.getByText("Settings", ShapeshifterEditorStories.TEMPLATE_NAME));
        play.waitFor(() -> play.expect(play.getByText("Source — the document")).toBeVisible());
        play.clear(play.getByLabelText("Project name"));
        play.type(play.getByLabelText("Project name"), "lexer_tokens");
        ShapeshifterEditorStories.pickFromBox(play, screen,
                play.getByLabelText("Encoding"), "utf-8");
    }

    /// §6.1 step 2. One line, which is the whole sample: seven tokens and four spaces.
    private static void sample(final Play play, final Play screen) {
        ShapeshifterEditorStories.openSamplePage(play);
        play.click(play.querySelector(ACE_CONTENT));
        play.waitFor(() -> play.expect(play.querySelector(ShapeshifterEditorStories.ACE_INPUT))
                .toHaveFocus());
        play.keyboard(SAMPLE);
        play.waitFor(() -> play.expect(play.querySelector(ACE_CONTENT).textContent())
                .toMatch("count=42"));
    }

    /// §6.1 step 3, **and a step the table does not have**: the root arrives named `document` and
    /// every sample calls it `source`, so it has to be renamed here as rung 1 renames it at its
    /// step 8. Without this the keystone cannot match. The panel's row still reads `document`
    /// afterwards (design 44 Q11), so the author sees no confirmation where they are looking.
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

    /// §6.1 step 4. The `apply-templates` card the root arrived with, selecting nothing — and the
    /// help saying what nothing means. The model reads blank as `$0`, which is why the fixture's
    /// spelling and a blank field are the same project (§3.1's withdrawn fourth trap).
    private static void blankSelect(final Play play, final Play screen) {
        play.click(play.getByText("apply-templates", ShapeshifterEditorStories.CARD_KIND));
        final Play card = ShapeshifterEditorStories.dialogMatching(play, screen, "apply-templates");
        play.expect(card.getByText(TextMatch.containing("this match's whole content")))
                .toBeInTheDocument();
        play.click(card.getByRole("button", StroomDom.button("Cancel")));
    }

    /// §6.1 step 7, **pulled in front of steps 5 and 6**. The `tokens` mode has to exist before
    /// anything can be pointed at it, and the only place a mode can be made is a template's own
    /// dialog — the dispatch card's Mode is a picker over modes that already exist. So the first
    /// token template is what creates the mode, and the table's order cannot be driven.
    private static void assign(final Play play, final Play screen) {
        Walkthrough.addTemplate(play, screen, "assign", "tokens", true, "=");
        Walkthrough.enter(play, "<op>=</op>");
    }

    /// The same window rung 1 meets at its step 12, reached from the other side: a mode exists
    /// with a template in it and nothing dispatches into it yet. Here the author arrived at it
    /// because the editor gave them no other order to work in.
    private static void deadMode(final Play play, final Play screen) {
        play.waitFor(() -> play.expect(play.getByText("nothing dispatches into this",
                ".template-panel__mode-note")).toBeVisible());
    }

    /// §6.1 steps 5 and 6, now that the mode exists: the document's card told which mode to
    /// dispatch into, and **how candidates in it are chosen**. `lexer` is what rung 2 is for —
    /// longest match wins, and the panel's order is only a tie-break.
    private static void dispatch(final Play play, final Play screen) {
        ShapeshifterEditorStories.selectTemplate(play, "document");
        play.click(play.getByText("apply-templates", ShapeshifterEditorStories.CARD_KIND));
        final Play card = ShapeshifterEditorStories.dialogMatching(play, screen, "apply-templates");
        ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Mode"), "tokens");
        ShapeshifterEditorStories.pickFromBox(play, screen,
                card.getByLabelText("Dispatch"), "lexer");
        play.click(card.getByRole("button", StroomDom.button("OK")));
        play.waitFor(() -> play.expect(play.queryByText("nothing dispatches into this",
                ".template-panel__mode-note")).toBeNull());
        // The card that chose it says so too, on the note line it already had. Here the fact
        // cannot be ambiguous: a site has one strategy, where a mode could in principle be
        // dispatched from several.
        play.waitFor(() -> play.expect(play.getByText(TextMatch.containing("lexer"),
                ".body-card__note")).toBeVisible());
    }

    /// §6.1 step 8. **The template this rung exists for.** `arrow` is added after `assign`, so it
    /// sits *below* it in the panel — and still wins the `=>`, because `lexer` takes the longest
    /// match and reads the order only to break ties. Under `strict` the same project would give
    /// `assign` the `=` and then fail on the `>`.
    private static void arrow(final Play play, final Play screen) {
        Walkthrough.addTemplate(play, screen, "arrow", "tokens", false, "=>");
        Walkthrough.enter(play, "<arrow/>");
        // Below assign, which is the whole point of the next run
        play.expect(play.getAllByText(TextMatch.regex("^(assign|arrow)$"),
                        ShapeshifterEditorStories.TEMPLATE_NAME).textContents())
                .toEqual(List.of("assign", "arrow"));
        // And the mode says it is dispatched with `lexer`, which is the fact an author cannot
        // otherwise see: it is chosen on a card belonging to another template, in another pane.
        // Knowing it is what stops them moving `arrow` up to fix a problem they do not have
        // (design 46 §6.1, §10 W4).
        play.waitFor(() -> play.expect(play.getByText("dispatched with lexer",
                ".template-panel__mode-note")).toBeVisible());
    }

    /// §6.1 step 9. Three cards for one token — the shape design 46 §10 W2 counts, met again in
    /// a sample that has nothing to do with `strict_kv`.
    private static void word(final Play play, final Play screen) {
        Walkthrough.addTemplate(play, screen, "word", "tokens", false, "[[a-z]+");
        // One line, typed as it looks, and split into the three cards it describes
        Walkthrough.enter(play, "<w>$0</w>");
    }

    /// §6.1 step 10. The same shape again, which is the point: four of the eleven cards in this
    /// rung are an opening tag, a `value-of` and a closing tag.
    private static void number(final Play play, final Play screen) {
        Walkthrough.addTemplate(play, screen, "number", "tokens", false, "[[0-9]+");
        Walkthrough.enter(play, "<n>$0</n>");
    }

    /// §6.1 step 11. A template with **no body at all**, whose whole purpose is to advance the
    /// cursor past the spaces. The role picker is what makes it that; its body is left empty.
    private static void space(final Play play, final Play screen) {
        Walkthrough.addTemplate(play, screen, "space", "tokens", false, " +");
        ShapeshifterEditorStories.pickFromBox(play, screen,
                play.getByLabelText("What the match is for"), "advance only");
    }

    /// §6.1 step 12, and the keystone. The run shows `=>` as `<arrow/>` rather than `<op>=</op>`
    /// followed by a stray `>`, which is `lexer` doing what the panel's order does not say.
    private static void built(final Play play, final Play screen) {
        final Spy requests = play.spy(ScreenHarness.REQUEST_SPY);
        // Run first, then read. Run puts the project as it stands on the wire, so the keystone
        // reads what the author would be running; Walkthrough.projectBuilt then skips the
        // pattern workbench's own experiments, which go to the same endpoint.
        play.click(ShapeshifterEditorStories.runButton(play));
        play.waitFor(() -> play.expect(play.getByText(TextMatch.containing("<arrow/>")))
                .toBeInTheDocument());
        play.waitFor(() -> play.expect("the project the editor built",
                        () -> JsonValues.jsonContains(Walkthrough.projectBuilt(requests),
                                ShapeshifterEditorStories.withoutIds(PROJECT)))
                .toEqual(true));
        // `=>` is one thing, not `<op>=</op>` followed by a stray `>`
        play.expect(play.queryByText(TextMatch.containing("<op>=</op>&gt;"))).toBeNull();
    }
}
