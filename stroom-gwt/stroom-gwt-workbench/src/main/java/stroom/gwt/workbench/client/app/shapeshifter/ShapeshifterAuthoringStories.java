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
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.client.widgets.tree.ExplorerFixture;
import stroom.gwt.workbench.client.widgets.tree.TreeFixtures;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Spy;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.ComponentStories;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.shapeshifter.shared.ShapeshifterDoc;

import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/// Design 46's ladder, walked a step at a time: a person building one of the engine's fixtures
/// from nothing, in the editor, with a screenshot of every step.
///
/// **Why a story per step.** The test runner takes exactly one screenshot per story, after the
/// play has finished (`test-runner/run.mjs`), and names it after the story. There is no way to
/// photograph the middle of a play. So each step of the walkthrough is a story of its own that
/// replays the steps before it and stops at its own — which costs about 5.4s of fixed setup per
/// story and 17ms per replayed step, measured, and buys a filmstrip a person can read.
///
/// The steps themselves are written once, in [#STAGES]; a story runs the prefix up to its own.
///
/// **What this proves, and what it cannot.** Every reply the editor gets here is canned, so a
/// walkthrough shows that the UI *can build* a configuration — it says nothing about whether
/// that configuration runs. That division is design 46 §2's: authorability here, correctness in
/// the engine's own tests. The keystone (§3) is the last stage's: the project the editor is
/// holding, read off the wire, compared with the engine's own fixture.
///
/// **The sample, and why the panes are empty until the end.** Design 46 §5.2 puts the sample in
/// early and on purpose — an author writing a pattern wants the record in front of them. But
/// [stroom.shapeshifter.client.component.ShapeshifterDesignController] runs the project over the
/// sample on a debounce after *every* edit, and this story's preview reply is canned. Running
/// mid-build would therefore photograph the finished `strict_kv` output beside a project that is
/// half written, which is worse than showing nothing.
///
/// So the walkthrough is served [#EMPTY_TRACE] until the last stage, which gets the real one.
/// The panes then read "nothing matched yet" while the project is being built, which for the
/// stages before the dispatch is wired is exactly true (§5.4: `pair` matches nothing until
/// something dispatches into `kv`) and for the later ones understates rather than overstates.
/// Showing the true intermediate marks needs a live engine, which is the other side of §2's line.
public final class ShapeshifterAuthoringStories {

    private static final String TITLE = "App/Shapeshifter/ShapeshifterAuthoring";

    // The lines design 46 §5.2 has the author paste: two that are pairs and one that is not, so
    // that the junk_line template built at §5.7 has something to catch. The same text the engine
    // fixture uses, so the last stage's canned trace is an answer to this input and not to some
    // other one. Typed rather than pasted, as userEvent.keyboard, so the line breaks are {Enter}.
    private static final String SAMPLE = "host=web01{Enter}"
                                         + "# a comment the grammar never claimed{Enter}"
                                         + "level=warn";

    private static final String SAMPLE_TEXT =
            "host=web01\n# a comment the grammar never claimed\nlevel=warn";

    // Stroom's Ace editor draws its text here; its text area (ace_text-input) is transparent, so
    // a story clicks the content to put the keyboard in it, as the documentation editor's does.
    private static final String ACE_CONTENT = ".ace_content";

    /// The walkthrough, in design 46 §5's order and carrying its step numbers — so a
    /// screenshot's filename names the step it is evidence for. There is no step 9: the
    /// numbering lost one when §5.3 was rewritten to say the root template arrives already made.
    private static final List<Walkthrough.Stage> STAGES = List.of(
            new Walkthrough.Stage("Kv01OpenBlank", ShapeshifterAuthoringStories::openBlank),
            new Walkthrough.Stage("Kv02Settings", ShapeshifterAuthoringStories::settings),
            new Walkthrough.Stage("Kv03Encoding", ShapeshifterAuthoringStories::encoding),
            new Walkthrough.Stage("Kv04DataPage", ShapeshifterAuthoringStories::dataPage),
            new Walkthrough.Stage("Kv05SamplePasted", ShapeshifterAuthoringStories::samplePasted),
            new Walkthrough.Stage("Kv06DocumentRow", ShapeshifterAuthoringStories::documentRow),
            new Walkthrough.Stage("Kv07BlankSelect", ShapeshifterAuthoringStories::blankSelect),
            new Walkthrough.Stage("Kv08Renamed", ShapeshifterAuthoringStories::renamed),
            new Walkthrough.Stage("Kv10PairTemplate", ShapeshifterAuthoringStories::pairTemplate),
            new Walkthrough.Stage("Kv11Pattern", ShapeshifterAuthoringStories::pattern),
            new Walkthrough.Stage("Kv12DeadMode", ShapeshifterAuthoringStories::deadMode),
            new Walkthrough.Stage("Kv13OpenTag", ShapeshifterAuthoringStories::openTag),
            new Walkthrough.Stage("Kv14Dispatch", ShapeshifterAuthoringStories::dispatch),
            new Walkthrough.Stage("Kv15CloseTag", ShapeshifterAuthoringStories::closeTag),
            new Walkthrough.Stage("Kv16RunReady", ShapeshifterAuthoringStories::runReady),
            new Walkthrough.Stage("Kv17PairOpen", ShapeshifterAuthoringStories::pairOpen),
            new Walkthrough.Stage("Kv18ValueOfKey", ShapeshifterAuthoringStories::valueOfKey),
            new Walkthrough.Stage("Kv19PairRest", ShapeshifterAuthoringStories::pairRest),
            new Walkthrough.Stage("Kv20RunReadyAgain", ShapeshifterAuthoringStories::runReady),
            new Walkthrough.Stage("Kv21JunkTemplate", ShapeshifterAuthoringStories::junkTemplate),
            new Walkthrough.Stage("Kv22JunkPattern", ShapeshifterAuthoringStories::junkPattern),
            new Walkthrough.Stage("Kv23Ordered", ShapeshifterAuthoringStories::ordered),
            new Walkthrough.Stage("Kv24AdvanceOnly", ShapeshifterAuthoringStories::advanceOnly),
            new Walkthrough.Stage("Kv25Warning", ShapeshifterAuthoringStories::warning),
            new Walkthrough.Stage("Kv26RunReadyLast", ShapeshifterAuthoringStories::runReady),
            new Walkthrough.Stage("Kv27Built", ShapeshifterAuthoringStories::built));

    private ShapeshifterAuthoringStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        Walkthrough.register(registry, TITLE, ShapeshifterAuthoringStories.class, STAGES,
                Walkthrough.routes(Walkthrough.emptyTrace(SAMPLE_TEXT)),
                Walkthrough.routes(ShapeshifterEditorStories.TRACE));
    }

    // ------------------------------------------------------------------------------------
    // The steps, in design 46 §5's order

    /// §5.1 step 1. The editor on a document with nothing in it. The root template is already
    /// there and named `document` in the panel, whatever the template itself is called.
    private static void openBlank(final Play play, final Play screen) {
        play.waitFor(() -> play.expect(play.getByText("document",
                ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
        play.expect(play.getByText("Settings", ShapeshifterEditorStories.TEMPLATE_NAME))
                .toBeInTheDocument();
        play.expect(play.getByText("Data", ShapeshifterEditorStories.TEMPLATE_NAME))
                .toBeInTheDocument();
        // Nothing has been edited, so there is nothing to save
        play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
    }

    /// §5.1 step 2. The project's own settings, and its name.
    private static void settings(final Play play, final Play screen) {
        play.click(play.getByText("Settings", ShapeshifterEditorStories.TEMPLATE_NAME));
        play.waitFor(() -> play.expect(play.getByText("Source — the document")).toBeVisible());
        play.clear(play.getByLabelText("Project name"));
        play.type(play.getByLabelText("Project name"), "strict_kv");
    }

    /// §5.1 step 3. The encoding, from a Stroom SelectionBox: click the box, then the item in
    /// the popup it opens, which is on the document body rather than in the story's own root.
    private static void encoding(final Play play, final Play screen) {
        ShapeshifterEditorStories.pickFromBox(play, screen, play.getByLabelText("Encoding"), "utf-8");
        // That is an edit, so Save has work to do
        play.waitFor(() -> play.expect(play.getByRole("button", "Save"))
                .not().toHaveAttribute("aria-disabled", "true"));
    }

    /// §5.2 step 4. The sample page, which the Data row opens in place of the navigator's rows.
    private static void dataPage(final Play play, final Play screen) {
        ShapeshifterEditorStories.openSamplePage(play);
        play.expect(play.querySelector(ShapeshifterEditorStories.ACE_INPUT)).not().toBeNull();
    }

    /// §5.2 step 5. The record in front of the author, before a single pattern is written —
    /// which is the whole point of this stage's place in the order.
    private static void samplePasted(final Play play, final Play screen) {
        play.click(play.querySelector(ACE_CONTENT));
        play.waitFor(() -> play.expect(play.querySelector(ShapeshifterEditorStories.ACE_INPUT))
                .toHaveFocus());
        play.keyboard(SAMPLE);
        play.waitFor(() -> play.expect(play.querySelector(ACE_CONTENT).textContent())
                .toMatch("host=web01"));
    }

    /// §5.3 step 6. The author finds the root template rather than making it.
    private static void documentRow(final Play play, final Play screen) {
        ShapeshifterEditorStories.selectTemplate(play, "document");
        play.waitFor(() -> play.expect(play.querySelector(ShapeshifterEditorStories.MATCH_CHIP))
                .not().toBeNull());
    }

    /// §5.3 step 7. The `apply-templates` card it arrived with, selecting nothing — and the help
    /// that says what nothing means. Opened and dismissed: the author is reading, not editing.
    private static void blankSelect(final Play play, final Play screen) {
        play.click(ShapeshifterEditorStories.cardLine(play, "apply-templates"));
        final Play card = ShapeshifterEditorStories.cardEditor(play);
        // In the document rather than visible: Stroom keeps a field's help behind the form
        // group's own help button (`form-group__help`), so what step 7 can be held to is that
        // the Select field carries this help — not that the author is reading it unprompted.
        play.expect(card.getByText(TextMatch.containing("this match's whole content")))
                .toBeInTheDocument();
        play.click(card.getByRole("button", StroomDom.button("Cancel")));
    }

    /// §5.3 step 8. The samples call the root template `source` and the editor calls it
    /// `document`; it cannot be removed or moved, but it can be renamed.
    private static void renamed(final Play play, final Play screen) {
        play.click(play.getByRole("button", "Edit template"));
        final Play renaming = ShapeshifterEditorStories.dialogMatching(play, screen, "template");
        play.clear(renaming.getByLabelText("Name"));
        renaming.type(renaming.getByLabelText("Name"), "source");
        play.click(renaming.getByRole("button", StroomDom.button("OK")));
        // The strip carries the new name; the panel's row does not, and that is a finding rather
        // than a wait that needs fixing (design 46 §5.9, design 44 Q11): the document row is
        // labelled with the fixed word "document", so the author sees no change there.
        play.waitFor(() -> play.expect(play.getByText("source", ShapeshifterEditorStories.STRIP_NAME))
                .toBeInTheDocument());
        play.expect(play.getByText("document", ShapeshifterEditorStories.TEMPLATE_NAME))
                .toBeInTheDocument();
    }

    /// §5.4 steps 10 and 12, which are one action in the UI: a template is given its mode in the
    /// dialog that makes it, so `pair` and the `kv` mode arrive together. Recorded as a
    /// divergence from the step table rather than split into two stages that cannot be driven
    /// apart.
    private static void pairTemplate(final Play play, final Play screen) {
        play.click(play.getByRole("button", "Add template"));
        final Play adding = ShapeshifterEditorStories.dialogMatching(play, screen, "New Template");
        adding.type(adding.getByLabelText("Name"), "pair");
        play.click(adding.getByRole("button", "New..."));
        final Play naming = ShapeshifterEditorStories.dialogMatching(play, screen, "mode");
        naming.type(naming.getByRole("textbox").first(), "kv");
        play.click(naming.getByRole("button", StroomDom.button("OK")));
        play.click(adding.getByRole("button", StroomDom.button("OK")));
        play.waitFor(() -> play.expect(play.getByText("pair",
                ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
    }

    /// §5.4 step 11. The pattern, in the workbench the match chip opens. A new template's match
    /// is already an empty regex (`Templates.create`), so the kind needs no choosing.
    private static void pattern(final Play play, final Play screen) {
        play.click(play.querySelector(ShapeshifterEditorStories.MATCH_CHIP));
        play.waitFor(() -> play.expect(play.getByLabelText("Pattern")).toBeInTheDocument());
        // "[[" types a literal "[": play.type is userEvent.type, where "[" opens a key
        // descriptor, so an unescaped "([a-z]+)" arrives as "(+)" with the character class
        // silently eaten (design 46 §5.9)
        play.type(play.getByLabelText("Pattern"), "([[a-z]+)=([[^\\n]*)\\n");
        play.click(play.getByRole("button", TextMatch.regex("close the workbench", "i")));
    }

    /// §5.4's finding, and the answer to it. Between making a template in a new mode and
    /// dispatching into that mode, the project is in a state where the template matches nothing.
    /// The author has done nothing wrong — they are halfway through a two-part edit — so the
    /// panel says so on the mode rather than leaving them to wonder.
    private static void deadMode(final Play play, final Play screen) {
        play.waitFor(() -> play.expect(play.getByText("nothing dispatches into this",
                ".template-panel__mode-note")).toBeVisible());
    }

    /// §5.5 step 13. The envelope's opening tag, moved above the dispatch it wraps: a card lands
    /// at the end of the body, so the order is made by hand afterwards.
    private static void openTag(final Play play, final Play screen) {
        ShapeshifterEditorStories.selectTemplate(play, "document");
        play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toBeInTheDocument());
        Walkthrough.enter(play, "<records>\\n");
        // The body is [apply-templates, text], and the row just added is the selected one: the
        // toolbar's Move up puts the opening before the dispatch
        play.click(play.getByRole("button", "Move the selected instruction up"));
    }

    /// §5.5 step 14. The dispatch itself — the card the document template arrived with, told
    /// which mode to dispatch into. The dead-mode note goes as the two-part edit completes.
    private static void dispatch(final Play play, final Play screen) {
        play.click(ShapeshifterEditorStories.cardLine(play, "apply-templates"));
        final Play dispatching =
                ShapeshifterEditorStories.cardEditor(play);
        ShapeshifterEditorStories.pickFromBox(play, screen, dispatching.getByLabelText("Mode"), "kv");
        play.click(dispatching.getByRole("button", StroomDom.button("OK")));
        // A note that stayed after the edit was finished would be worse than no note at all
        play.waitFor(() -> play.expect(play.queryByText("nothing dispatches into this",
                ".template-panel__mode-note")).toBeNull());
    }

    /// §5.5 step 15. The closing tag, which lands at the end where it belongs.
    private static void closeTag(final Play play, final Play screen) {
        Walkthrough.enter(play, "</records>\\n");
    }

    /// §5.5 step 16, §5.6 step 20 and §5.7 step 26 — "Runs". Asserted and not clicked: the
    /// preview reply is canned, so a run here would photograph a finished configuration beside a
    /// half-built one. What is true and worth saying is that there is now something to run.
    private static void runReady(final Play play, final Play screen) {
        play.expect(ShapeshifterEditorStories.runButton(play))
                .not().toHaveAttribute("aria-disabled", "true");
    }

    /// §5.6 step 17. The pair's output, a card at a time — five of them for one line, which is
    /// the quality-of-life question this stage exists to show.
    private static void pairOpen(final Play play, final Play screen) {
        ShapeshifterEditorStories.selectTemplate(play, "pair");
        play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toBeInTheDocument());
        Walkthrough.enter(play, " <pair key=\"");
    }

    /// §5.6 step 18. The first capture read back out.
    private static void valueOfKey(final Play play, final Play screen) {
        Walkthrough.enter(play, "$1");
    }

    /// §5.6 step 19. The rest of the line.
    private static void pairRest(final Play play, final Play screen) {
        Walkthrough.enter(play, "\">");
        Walkthrough.enter(play, "$2");
        Walkthrough.enter(play, "</pair>\\n");
    }

    /// §5.7 step 21. The catch-all, in the mode it has to share with `pair`.
    private static void junkTemplate(final Play play, final Play screen) {
        play.click(play.getByRole("button", "Add template"));
        final Play junk = ShapeshifterEditorStories.dialogMatching(play, screen, "New Template");
        junk.type(junk.getByLabelText("Name"), "junk_line");
        ShapeshifterEditorStories.pickFromBox(play, screen, junk.getByLabelText("Mode"), "kv");
        play.click(junk.getByRole("button", StroomDom.button("OK")));
        play.waitFor(() -> play.expect(play.getByText("junk_line",
                ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
    }

    /// §5.7 step 22. A pattern that matches any line at all.
    private static void junkPattern(final Play play, final Play screen) {
        play.click(play.querySelector(ShapeshifterEditorStories.MATCH_CHIP));
        play.waitFor(() -> play.expect(play.getByLabelText("Pattern")).toBeInTheDocument());
        play.type(play.getByLabelText("Pattern"), "[[^\\n]*\\n?");
        play.click(play.getByRole("button", TextMatch.regex("close the workbench", "i")));
    }

    /// §5.7 step 23. Order within a mode is dispatch priority, and this is the one place in the
    /// walkthrough where getting it wrong silently changes the output — `junk_line` above `pair`
    /// matches everything and the project emits nothing but warnings.
    ///
    /// The step table has the author move it down. A template added second already *is* second,
    /// so what this asserts is the order rather than performing a move that would undo it. The
    /// move buttons are still the only thing on screen that names the order, and they name it
    /// only in a tooltip (§5.7's amendment).
    private static void ordered(final Play play, final Play screen) {
        play.expect(play.getAllByText(TextMatch.regex("^(pair|junk_line)$"),
                ShapeshifterEditorStories.TEMPLATE_NAME).textContents())
                .toEqual(List.of("pair", "junk_line"));
        play.expect(play.getAllByTitle(TextMatch.containing("dispatch order")).first())
                .toBeInTheDocument();
        // ...and the panel says nothing about how `kv` is dispatched, because it is dispatched
        // the way everyone assumes. `strict` is the reading an author brings; only something
        // else is news, and saying it here would be chrome on most projects (design 46 §10 W4).
        play.expect(play.queryByText(TextMatch.containing("dispatched with"),
                ".template-panel__mode-note")).toBeNull();
    }

    /// §5.7 step 24. The role that makes it an eater rather than a record. The configuration
    /// calls the flag `consume`; the picker says *advance only*, because `MatchRole` tried "skip"
    /// and "eater" and found both misleading — nothing is discarded and nothing stops.
    private static void advanceOnly(final Play play, final Play screen) {
        ShapeshifterEditorStories.pickFromBox(play, screen,
                play.getByLabelText("What the match is for"), "advance only");
    }

    /// §5.7 step 25. The warning, which is the authored replacement for DS3's engine-generated
    /// skip reports (design 11 §4). Its message is a reference expression of a literal and the
    /// whole match, written as the field's own help describes.
    private static void warning(final Play play, final Play screen) {
        ShapeshifterEditorStories.selectTemplate(play, "junk_line");
        play.click(ShapeshifterEditorStories.addMenu(play));
        play.click(screen.findByText("emit-error", StroomDom.MENU_ITEM_TEXT));
        final Play message = ShapeshifterEditorStories.cardEditor(play);
        // Lowercase, as the picker lists them and as the wire spells them (JsonFields.label); the
        // default is "error", so this is a real choice
        ShapeshifterEditorStories.pickFromBox(play, screen,
                message.getByLabelText("Severity"), "warning");
        message.type(message.getByLabelText("Message"), "\"unrecognised line: \" $0");
        play.click(message.getByRole("button", StroomDom.button("OK")));
    }

    /// §5.8 step 27, the keystone (design 46 §3). The project the editor is holding, read off the
    /// wire rather than off the save: clicking Save sends nothing here, because the harness mounts
    /// the editor host without the document plugin that performs one, and no document-editor story
    /// in the workbench asserts a save. What the editor would save is what it sends.
    ///
    /// Compared by containment, not equality: the editor writes defaults the fixture leaves out
    /// (`match_limits`, `max_depth`), and containment tolerates those while still requiring every
    /// array to be the same length — so a missing template, or a body card in the wrong place,
    /// fails.
    ///
    /// This stage is also the one served the real trace, so the run it ends with is a run of the
    /// project the author has just finished building.
    private static void built(final Play play, final Play screen) {
        final Spy requests = play.spy(ScreenHarness.REQUEST_SPY);
        play.waitFor(() -> play.expect("the project the editor built",
                        () -> JsonValues.jsonContains(Walkthrough.projectBuilt(requests),
                                ShapeshifterEditorStories.withoutIds(ShapeshifterEditorStories.PROJECT)))
                .toEqual(true));
        play.click(ShapeshifterEditorStories.runButton(play));
        play.waitFor(() -> play.expect(play.getByText(TextMatch.containing("<records>")))
                .toBeInTheDocument());
    }

}
