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
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;

/// A template and a mode made where they are listed, and named where they are shown (design 47
/// B; the owner, 2026-11-18). Each mode's list in the panel ends with `+ template`, and the
/// modes with `+ mode`. Pressed, a link adds — a template called `new template`, a mode called
/// `new mode` — and what was added is shown with its name open in its own title, to be typed
/// over. A template's settings are under its title while the title is open. From a new
/// template's name Enter goes on to its match line, where a regex is typed in place. No dialog
/// is involved, and nothing is named in the list.
///
/// These stories are about those links, that title and that line: what adds, what takes a name
/// and a pattern and what leaves them, and where the keyboard goes next.
public final class ShapeshifterTemplateStories {

    private static final String TITLE = "App/Shapeshifter/ShapeshifterTemplate";

    private static final String NAME = "Template name";
    private static final String MODE = "Mode name";
    private static final String PATTERN = "Match pattern";
    private static final String KV_ROW = "Click to add a template to mode kv";
    private static final String KV_MODE = "mode: kv";
    private static final String PAIR_PATTERN = "([a-z]+)=([^\\n]*)\\n";
    private static final String MATCH_ERROR = ".template-strip__match-error";
    // A group left open, and what the engine says of it: the stub answers for this one pattern
    private static final String UNCLOSED = "([a-z]+=";
    private static final String UNCLOSED_REASON = "Unclosed group near index 8";
    private static final String NOT_COMPILED = "{\"valid\": false, \"error\": \"" + UNCLOSED_REASON
                                               + "\", \"groups\": [], \"explain\": null}";
    private static final String EDIT_NAME = "Edit the name";
    private static final String PART_NAME = "Pattern part name";
    private static final TextMatch ADD_PART = TextMatch.regex("^Add a pattern part");
    private static final String PART_TITLE = ".pattern-workbench__title .name-edit__name";
    private static final String JUNK_PATTERN = "[^\\n]*\\n?";
    private static final String MATCH_PATTERN = ".template-strip__match-pattern";

    private ShapeshifterTemplateStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component(TITLE, ShapeshifterTemplateStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // A mode's `+ template`, pressed: a template is there at once, in that mode,
                // called what a new one is called, with its name open in its title. Typed over
                // and taken with Enter, its match opens for its pattern; Enter there goes on
                // into its body. Left there, for the screenshot
                .story("NewTemplate", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByTitle(KV_ROW)).toBeInTheDocument());
                    play.click(play.getByTitle(KV_ROW));
                    // It is the last of its mode, where the link is, and the selected one
                    play.waitFor(() -> play.expect(names(play)).toEqual(
                            Arrays.asList("pair", "junk_line", "new template")));
                    play.expect(play.getByText("mode kv", ".template-strip__mode")).toBeInTheDocument();
                    // In the list and the pane, and not over them; and nothing to type into in
                    // the list
                    play.expect(screen.queryByRole("dialog")).toBeNull();
                    play.waitFor(() -> play.expect(play.getByLabelText(NAME)).toHaveFocus());
                    play.expect(play.getByLabelText(NAME)).toHaveValue("new template");
                    // Adding is an edit: the template exists before it is named
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    // What is typed replaces the name it arrived with
                    play.keyboard("line{Enter}");
                    play.waitFor(() -> play.expect(names(play)).toEqual(
                            Arrays.asList("pair", "junk_line", "line")));
                    play.expect(play.getByText("line", ShapeshifterEditorStories.STRIP_NAME)).toBeVisible();
                    // What it matches is the next thing to say: the match is open, and empty
                    play.waitFor(() -> play.expect(play.getByLabelText(PATTERN)).toHaveFocus());
                    play.expect(play.getByLabelText(PATTERN)).toHaveValue("");
                    play.keyboard("x=(.*){Enter}");
                    play.waitFor(() -> play.expect(play.getByText("x=(.*)", ".template-strip__match-pattern"))
                            .toBeVisible());
                    // and then what it writes: its body has nothing in it, so a new card is open
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // The toolbar's Add is the same way in: a template in the mode the selected
                // one is in. Escape leaves the template with the name it arrived with — it was
                // made when it was asked for — and a second is numbered, since a call names a
                // template and two of a name could not be told apart
                .story("ANewTemplateIsThereBeforeItIsNamed",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.click(play.getByRole("button", "Add template"));
                    play.waitFor(() -> play.expect(play.getByLabelText(NAME)).toHaveFocus());
                    play.keyboard("junk{Escape}");
                    play.waitFor(() -> play.expect(play.getByRole("button",
                            ShapeshifterEditorStories.EDIT_TITLE)).toHaveFocus());
                    play.expect(names(play)).toEqual(Arrays.asList("pair", "junk_line", "new template"));
                    play.expect(play.getByText("new template", ShapeshifterEditorStories.STRIP_NAME))
                            .toBeVisible();
                    // Escape was the end of naming it: the match has not been opened
                    play.expect(play.getByLabelText(PATTERN)).not().toBeVisible();
                    play.click(play.getByRole("button", "Add template"));
                    play.waitFor(() -> play.expect(names(play)).toEqual(
                            Arrays.asList("pair", "junk_line", "new template", "new template 2")));
                    play.waitFor(() -> play.expect(play.getByLabelText(NAME)).toHaveValue("new template 2"));
                    play.keyboard("{Escape}");
                    // Remove is how one that was not wanted goes
                    play.click(play.getByRole("button", "Remove template"));
                    final Spy confirm = play.spy(ScreenHarness.CONFIRM_SPY);
                    play.waitFor(() -> play.expect(confirm).toHaveBeenCalled());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A template's title, opened: its name is a field where it read, and its other
                // settings are under it. They are a draft, taken together with Enter in the
                // name or OK, and dropped with Escape or Cancel
                .story("TheTitleEditsTheNameAndSettings",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByRole("button",
                            ShapeshifterEditorStories.EDIT_TITLE)).toBeInTheDocument());
                    // At rest there are no settings on show
                    play.expect(play.queryByLabelText("Encoding")).toBeNull();
                    play.click(play.getByRole("button", ShapeshifterEditorStories.EDIT_TITLE));
                    play.waitFor(() -> play.expect(play.getByLabelText(NAME)).toHaveFocus());
                    play.expect(play.getByLabelText(NAME)).toHaveValue("pair");
                    play.expect(play.getByLabelText("Mode")).toHaveValue("kv");
                    play.expect(play.getByLabelText("Encoding")).toBeInTheDocument();
                    play.expect(play.getByLabelText("Params")).toBeVisible();
                    play.expect(screen.queryByRole("dialog")).toBeNull();
                    // Escape: as it was, and nothing to save
                    play.keyboard("junk{Escape}");
                    play.waitFor(() -> play.expect(play.queryByLabelText("Encoding")).toBeNull());
                    play.expect(play.getByText("pair", ShapeshifterEditorStories.STRIP_NAME)).toBeVisible();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    // The name itself opens it too. A setting and the name, taken with OK
                    play.click(play.getByText("pair", ShapeshifterEditorStories.STRIP_NAME));
                    play.waitFor(() -> play.expect(play.getByLabelText(NAME)).toHaveFocus());
                    play.keyboard("kv_pair");
                    play.type(play.getByLabelText("Params"), "sep = :");
                    play.click(play.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.getByText("kv_pair",
                            ShapeshifterEditorStories.STRIP_NAME)).toBeVisible());
                    play.expect(play.getByText("kv_pair", ShapeshifterEditorStories.TEMPLATE_NAME))
                            .toBeInTheDocument();
                    play.expect(play.queryByLabelText("Encoding")).toBeNull();
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    // It was not a template just made, so the match has not been opened
                    play.expect(play.getByLabelText(PATTERN)).not().toBeVisible();
                    // What was taken is what opens next time
                    play.click(play.getByRole("button", ShapeshifterEditorStories.EDIT_TITLE));
                    play.waitFor(() -> play.expect(play.getByLabelText("Params")).toHaveValue("sep = :\n"));
                    // The edit button pressed again takes it, as Enter does
                    play.click(play.getByRole("button", ShapeshifterEditorStories.EDIT_TITLE));
                    play.waitFor(() -> play.expect(play.queryByLabelText("Encoding")).toBeNull());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A template moved to another mode from its settings is listed under that mode
                .story("ATemplateChangesMode", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "junk_line");
                    play.click(play.getByRole("button", ShapeshifterEditorStories.EDIT_TITLE));
                    play.waitFor(() -> play.expect(play.getByLabelText(NAME)).toHaveFocus());
                    ShapeshifterEditorStories.pickFromBox(play, screen, play.getByLabelText("Mode"), "root");
                    play.click(play.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.getByText("root", ".template-strip__mode"))
                            .toBeInTheDocument());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // The modes end with `+ mode`. Pressed, a mode is there, called what a new one
                // is called, and it is the mode that is shown: its name open in its title, and
                // its templates — none yet — listed under it. Named, the keyboard is on its own
                // `+ template`, since a mode is only ever the mode of a template
                .story("NewMode", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByTitle(Walkthrough.ADD_MODE)).toBeInTheDocument());
                    // The same links the declarations and the captures are added with
                    play.expect(play.getByTitle(Walkthrough.ADD_MODE)).toHaveTextContent("+ mode");
                    play.expect(play.getByTitle(KV_ROW)).toHaveTextContent("+ template");
                    play.click(play.getByTitle(Walkthrough.ADD_MODE));
                    play.waitFor(() -> play.expect(play.getByLabelText(MODE)).toHaveFocus());
                    play.expect(play.getByLabelText(MODE)).toHaveValue("new mode");
                    play.expect(play.getByText("mode: new mode")).toBeVisible();
                    // Naming a mode makes nothing to save: there is no template in it yet
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    play.keyboard("tokens{Enter}");
                    play.waitFor(() -> play.expect(play.getByText("mode: tokens")).toBeVisible());
                    play.expect(play.queryByText("mode: new mode")).toBeNull();
                    play.expect(play.getByText("tokens", ShapeshifterEditorStories.STRIP_NAME)).toBeVisible();
                    play.expect(play.getByText("0 templates", ".template-strip__mode")).toBeInTheDocument();
                    play.waitFor(() -> play.expect(Walkthrough.modeAddLink(play)).toHaveFocus());
                    // Enter presses it: the first template, in this mode, with its name open
                    play.keyboard("{Enter}");
                    play.waitFor(() -> play.expect(play.getByLabelText(NAME)).toHaveFocus());
                    play.keyboard("word{Enter}");
                    play.waitFor(() -> play.expect(names(play)).toEqual(
                            Arrays.asList("pair", "junk_line", "word")));
                    play.expect(play.getByText("mode tokens", ".template-strip__mode")).toBeInTheDocument();
                    play.waitFor(() -> play.expect(play.getByLabelText(PATTERN)).toHaveFocus());
                    play.keyboard("{Escape}");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A mode's heading in the panel selects the mode, which is shown where a
                // template would be: its name, and the templates in it, each the way to that
                // template. Renamed there, it is renamed on its templates and on the dispatch
                // that points at it
                .story("AModeIsShownAndRenamed", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByText(KV_MODE)).toBeVisible());
                    play.click(play.getByText(KV_MODE));
                    play.waitFor(() -> play.expect(play.getByText("kv", ShapeshifterEditorStories.STRIP_NAME))
                            .toBeVisible());
                    play.expect(play.getByText("2 templates", ".template-strip__mode")).toBeInTheDocument();
                    final Play pane = play.within(play.querySelector(".template-strip"));
                    play.expect(pane.getByRole("button", StroomDom.button("pair"))).toBeVisible();
                    play.expect(pane.getByRole("button", StroomDom.button("junk_line"))).toBeVisible();
                    // A mode has a name and nothing else to set
                    play.click(play.getByRole("button", EDIT_NAME));
                    play.waitFor(() -> play.expect(play.getByLabelText(MODE)).toHaveFocus());
                    play.expect(play.queryByLabelText("Encoding")).toBeNull();
                    play.keyboard("pairs{Enter}");
                    play.waitFor(() -> play.expect(play.getByText("mode: pairs")).toBeVisible());
                    play.expect(play.queryByText(KV_MODE)).toBeNull();
                    play.expect(play.getByText("pairs", ShapeshifterEditorStories.STRIP_NAME)).toBeVisible();
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    // It was not a mode just made: the keyboard stays on its title
                    play.waitFor(() -> play.expect(play.getByRole("button", EDIT_NAME)).toHaveFocus());
                    // One of its templates, pressed, is gone to — and it is in the mode as renamed
                    play.click(pane.getByRole("button", StroomDom.button("pair")));
                    play.waitFor(() -> play.expect(play.getByText("pair", ShapeshifterEditorStories.STRIP_NAME))
                            .toBeVisible());
                    play.expect(play.getByText("mode pairs", ".template-strip__mode")).toBeInTheDocument();
                    // and so is the dispatch that pointed at it
                    ShapeshifterEditorStories.selectTemplate(play, "document");
                    play.waitFor(() -> play.expect(play.getByText(TextMatch.containing("pairs"),
                            ".body-card__literal")).toBeVisible());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A mode with no templates in it is removed with the toolbar's Remove; one with
                // templates is not
                .story("AModeIsRemovedWhenItIsEmpty", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByText(KV_MODE)).toBeVisible());
                    play.click(play.getByText(KV_MODE));
                    play.waitFor(() -> play.expect(play.getByText("kv", ShapeshifterEditorStories.STRIP_NAME))
                            .toBeVisible());
                    play.expect(play.getByRole("button", "Remove template")).toHaveAttribute("aria-disabled", "true");
                    play.click(play.getByTitle(Walkthrough.ADD_MODE));
                    play.waitFor(() -> play.expect(play.getByLabelText(MODE)).toHaveFocus());
                    // Escape leaves it with the name it arrived with
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(play.getByText("mode: new mode")).toBeVisible());
                    play.click(play.getByRole("button", "Remove template"));
                    play.waitFor(() -> play.expect(play.queryByText("mode: new mode")).toBeNull());
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A name another mode has is refused, and the name stays open to be changed
                .story("AModeNameAlreadyTakenIsRefused", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByTitle(Walkthrough.ADD_MODE)).toBeInTheDocument());
                    play.click(play.getByTitle(Walkthrough.ADD_MODE));
                    play.waitFor(() -> play.expect(play.getByLabelText(MODE)).toHaveFocus());
                    play.keyboard("kv{Enter}");
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.ALERT_SPY)).toHaveBeenCalled());
                    play.expect(play.getByLabelText(MODE)).toBeVisible();
                    play.expect(play.getByText("mode: new mode")).toBeVisible();
                })
                // How a mode is dispatched is said, and set, in the mode's own pane (design 47 C):
                // the strategy its dispatch sites share. Set there it is set on the card, which
                // is where a strategy lives, and the list says it too
                .story("AModeSaysHowItIsDispatched", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByText(KV_MODE)).toBeVisible());
                    play.click(play.getByText(KV_MODE));
                    play.waitFor(() -> play.expect(play.getByText("dispatched from 1 site",
                            ".template-strip__role-note")).toBeVisible());
                    // It is dispatched already, so there is nothing to offer
                    play.expect(play.queryByRole("button", StroomDom.button("dispatch from the document")))
                            .toBeNull();
                    ShapeshifterEditorStories.pickFromBox(play, screen,
                            play.getByLabelText(Walkthrough.MODE_DISPATCH), "lexer");
                    play.waitFor(() -> play.expect(play.getByText("dispatched with lexer",
                            ".template-panel__mode-note")).toBeVisible());
                    play.expect(play.getByLabelText(Walkthrough.MODE_DISPATCH)).toHaveValue("lexer");
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    // The card that dispatches into the mode has it
                    ShapeshifterEditorStories.selectTemplate(play, "document");
                    play.waitFor(() -> play.expect(play.getByText(TextMatch.containing("lexer"),
                            ".body-card__note")).toBeVisible());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A mode nothing dispatches into says so in its pane. Where the document already
                // dispatches somewhere else, pointing it here would be a decision and not a
                // completion: nothing is offered, and the pane says why and what to do instead.
                // The strategy has no site to be set on
                .story("ASecondModeIsNotDispatchedForYou", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByTitle(Walkthrough.ADD_MODE)).toBeInTheDocument());
                    play.click(play.getByTitle(Walkthrough.ADD_MODE));
                    play.waitFor(() -> play.expect(play.getByLabelText(MODE)).toHaveFocus());
                    play.keyboard("fields{Enter}");
                    play.waitFor(() -> play.expect(play.getByText(TextMatch.containing(
                            "nothing dispatches into this — the document dispatches into kv"),
                            ".template-strip__role-note")).toBeVisible());
                    play.expect(play.queryByRole("button", StroomDom.button("dispatch from the document")))
                            .toBeNull();
                    play.expect(play.getByLabelText(Walkthrough.MODE_DISPATCH)).toBeDisabled();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A mode with no template in it yet is one a card can dispatch into: the card's
                // Mode offers every mode the list shows
                .story("ACardCanDispatchIntoAModeWithNoTemplatesYet",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.waitFor(() -> play.expect(play.getByTitle(Walkthrough.ADD_MODE)).toBeInTheDocument());
                    play.click(play.getByTitle(Walkthrough.ADD_MODE));
                    play.waitFor(() -> play.expect(play.getByLabelText(MODE)).toHaveFocus());
                    play.keyboard("fields{Enter}");
                    play.waitFor(() -> play.expect(play.getByText("mode: fields")).toBeVisible());
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeInTheDocument());
                    ShapeshifterEditorStories.newCard(play, screen, "apply-templates");
                    final Play card = ShapeshifterEditorStories.cardEditor(play);
                    ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Mode"), "fields");
                    play.click(card.getByRole("button", StroomDom.button("OK")));
                    // The mode is dispatched now, and says so
                    play.waitFor(() -> play.expect(play.queryByText("nothing dispatches into this",
                            ".template-panel__mode-note")).toBeNull());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A pattern part's name is edited in the workbench's title, which is where a
                // part is shown
                .story("APartIsRenamedWhereItIsShown", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByText("pair",
                            ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
                    // Added there and then, and shown with its name open and all of it
                    // selected, so what is typed replaces it (design 47 §7.2, finding 15)
                    play.click(play.getByRole("button", TextMatch.regex("^Add a pattern part")));
                    play.waitFor(() -> play.expect(play.getByLabelText("Pattern part name")).toHaveFocus());
                    play.expect(play.getByLabelText("Pattern part name")).toHaveValue("new part");
                    play.expect(play.getByText("new part", ShapeshifterEditorStories.TEMPLATE_NAME))
                            .toBeInTheDocument();
                    play.keyboard("ipv4{Enter}");
                    play.waitFor(() -> play.expect(play.getByText("ipv4",
                            ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
                    play.expect(play.queryByText("new part", ShapeshifterEditorStories.TEMPLATE_NAME)).toBeNull();
                    // And renamed again from its title's edit button
                    play.click(play.getByRole("button", EDIT_NAME));
                    play.waitFor(() -> play.expect(play.getByLabelText("Pattern part name")).toHaveFocus());
                    play.expect(play.getByLabelText("Pattern part name")).toHaveValue("ipv4");
                    play.keyboard("address{Enter}");
                    play.waitFor(() -> play.expect(play.getByText("address",
                            ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
                    play.expect(play.queryByText("ipv4", ShapeshifterEditorStories.TEMPLATE_NAME)).toBeNull();
                    play.expect(play.getByText("address", ".pattern-workbench__title .name-edit__name"))
                            .toBeVisible();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A regex's pattern is changed where it reads. Escape puts it back; Enter takes
                // it, and in a template that has a body the keyboard stays on the match
                .story("MatchOpensInPlace", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(matchOpen(play)).toBeVisible());
                    // At rest it reads
                    play.expect(play.getByLabelText(PATTERN)).not().toBeVisible();
                    play.click(matchOpen(play));
                    play.waitFor(() -> play.expect(play.getByLabelText(PATTERN)).toHaveFocus());
                    play.expect(play.getByLabelText(PATTERN)).toHaveValue(PAIR_PATTERN);
                    // Not the workbench
                    play.expect(play.queryByRole("button", TextMatch.regex("close the workbench", "i")))
                            .toBeNull();
                    play.keyboard("junk{Escape}");
                    play.waitFor(() -> play.expect(matchOpen(play)).toHaveFocus());
                    play.expect(play.getByText(PAIR_PATTERN, ".template-strip__match-pattern")).toBeVisible();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    // Enter on the match opens it, as a click does; Enter in it takes it
                    play.keyboard("{Enter}");
                    play.waitFor(() -> play.expect(play.getByLabelText(PATTERN)).toHaveFocus());
                    play.clear(play.getByLabelText(PATTERN));
                    play.keyboard("(.+)=(.*){Enter}");
                    play.waitFor(() -> play.expect(play.getByText("(.+)=(.*)", ".template-strip__match-pattern"))
                            .toBeVisible());
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    play.waitFor(() -> play.expect(matchOpen(play)).toHaveFocus());
                    // The body has cards, so nothing has been opened in it
                    play.expect(play.queryByLabelText(Walkthrough.ENTRY)).toBeNull();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // While the line is open the engine is asked whether what is typed compiles,
                // and its reason is shown under the line (design 47 §7.2, finding 14)
                .story("AMatchThatDoesNotCompileSaysSo", context -> ShapeshifterEditorStories.render(context,
                        RestFixtures.builder()
                                .post("/shapeshifter/v1/patternInfo", request -> RestReply.json(
                                        UNCLOSED.equals(patternOf(request))
                                                ? NOT_COMPILED
                                                : ShapeshifterEditorStories.PATTERN_INFO))
                                .build()))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(matchOpen(play)).toBeVisible());
                    play.click(matchOpen(play));
                    play.waitFor(() -> play.expect(play.getByLabelText(PATTERN)).toHaveFocus());
                    // The pattern it opens with compiles, so nothing is said
                    play.expect(play.querySelector(MATCH_ERROR)).not().toBeVisible();
                    play.clear(play.getByLabelText(PATTERN));
                    play.keyboard(Walkthrough.typed(UNCLOSED));
                    play.waitFor(() -> play.expect(play.querySelector(MATCH_ERROR)).toHaveTextContent(UNCLOSED_REASON));
                    play.expect(play.getByLabelText(PATTERN)).toHaveAttribute("aria-invalid", "true");
                    // Closed, it compiles, and the line says nothing again
                    play.keyboard(")");
                    play.waitFor(() -> play.expect(play.querySelector(MATCH_ERROR)).not().toBeVisible());
                    play.expect(play.getByLabelText(PATTERN)).not().toHaveAttribute("aria-invalid");
                    // Open again, then put back with Escape: nothing is open, so nothing is said
                    play.keyboard("{Backspace}");
                    play.waitFor(() -> play.expect(play.querySelector(MATCH_ERROR)).toBeVisible());
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(matchOpen(play)).toHaveFocus());
                    play.expect(play.querySelector(MATCH_ERROR)).not().toBeVisible();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Leaving the field takes what it holds, as doing anything else to a body takes
                // the card open in it: the workbench opened from it has the pattern as typed
                .story("LeavingTheMatchTakesIt", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(matchOpen(play)).toBeVisible());
                    play.click(matchOpen(play));
                    play.waitFor(() -> play.expect(play.getByLabelText(PATTERN)).toHaveFocus());
                    play.clear(play.getByLabelText(PATTERN));
                    play.keyboard("(.+)=(.*)");
                    play.click(ShapeshifterEditorStories.workbenchButton(play));
                    play.waitFor(() -> play.expect(play.getByLabelText("Pattern")).toHaveValue("(.+)=(.*)"));
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A match with no one-line spelling is the workbench's: clicking it opens the
                // workbench, as it always did
                .story("AMatchThatIsNotARegexOpensTheWorkbench", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "framed");
                    play.waitFor(() -> play.expect(matchOpen(play)).toBeVisible());
                    play.click(matchOpen(play));
                    play.waitFor(() -> play.expect(play.getByRole("button",
                            TextMatch.regex("close the workbench", "i"))).toBeInTheDocument());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Read only, the panel has no add links, no title has an edit button, and the
                // match opens the workbench to be read rather than a field to be typed in
                .story("ReadOnlyHasNoNewRows", context -> ShapeshifterEditorStories.render(context, true))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(matchOpen(play)).toBeVisible());
                    play.expect(play.queryByTitle(KV_ROW)).toBeNull();
                    play.expect(play.queryByTitle(Walkthrough.ADD_MODE)).toBeNull();
                    play.expect(play.queryByRole("button", ShapeshifterEditorStories.EDIT_TITLE)).toBeNull();
                    play.click(play.getByText("pair", ShapeshifterEditorStories.STRIP_NAME));
                    play.expect(play.getByText("pair", ShapeshifterEditorStories.STRIP_NAME)).toBeVisible();
                    play.expect(play.queryByLabelText("Encoding")).toBeNull();
                    play.click(matchOpen(play));
                    play.waitFor(() -> play.expect(play.getByRole("button",
                            TextMatch.regex("close the workbench", "i"))).toBeInTheDocument());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A name another template has is refused, as a mode's is: a call names a
                // template, and two of a name could not be told apart (design 47 §7.8,
                // finding 4). The name stays open to be changed
                .story("ATemplateNameAlreadyTakenIsRefused", context -> ShapeshifterEditorStories.render(context,
                        false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(editTitle(play)).toBeVisible());
                    play.click(editTitle(play));
                    play.waitFor(() -> play.expect(play.getByLabelText(NAME)).toHaveFocus());
                    play.keyboard("junk_line{Enter}");
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.ALERT_SPY)).toHaveBeenCalled());
                    play.expect(play.getByLabelText(NAME)).toBeVisible();
                    play.expect(play.getByText("pair", ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                })
                // A part's name being typed is that part's: another part shown closes it, and
                // nothing is renamed (design 47 §7.8, finding 2). It used to stay open over the
                // part shown next, and Enter renamed that one
                .story("APartsNameGoesWithThePart", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByText("pair",
                            ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
                    play.click(play.getByRole("button", ADD_PART));
                    play.waitFor(() -> play.expect(play.getByLabelText(PART_NAME)).toHaveFocus());
                    play.keyboard("{Enter}");
                    play.click(play.getByRole("button", ADD_PART));
                    play.waitFor(() -> play.expect(play.getByLabelText(PART_NAME)).toHaveValue("new part 2"));
                    play.keyboard("ipv4");
                    // The other part, chosen with the name still open
                    play.click(play.getByText("new part", ShapeshifterEditorStories.TEMPLATE_NAME));
                    play.waitFor(() -> play.expect(play.getByText("new part", PART_TITLE)).toBeVisible());
                    play.expect(play.getByLabelText(PART_NAME)).not().toBeVisible();
                    play.expect(play.queryByText("ipv4", ShapeshifterEditorStories.TEMPLATE_NAME)).toBeNull();
                    play.expect(play.getByText("new part 2", ShapeshifterEditorStories.TEMPLATE_NAME))
                            .toBeInTheDocument();
                    // Opened again, it holds this part's name
                    play.click(play.getByRole("button", EDIT_NAME));
                    play.waitFor(() -> play.expect(play.getByLabelText(PART_NAME)).toHaveValue("new part"));
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A pattern being typed is its template's. A key that moves the cursor to another
                // template — here Ctrl+Alt+→, to the next sibling — leaves the keyboard where it
                // is, so nothing blurs the field: it is taken for the template it was typed for
                // and closed, rather than left open over the next one's match to be written
                // there (design 47 §7.8, finding 3)
                .story("AMatchBeingTypedStaysWithItsTemplate", context -> ShapeshifterEditorStories.render(context,
                        false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.runButton(play)).toBeVisible());
                    play.click(ShapeshifterEditorStories.runButton(play));
                    Walkthrough.expectTried(play, "pair", "2");
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(matchOpen(play)).toBeVisible());
                    play.click(matchOpen(play));
                    play.waitFor(() -> play.expect(play.getByLabelText(PATTERN)).toHaveFocus());
                    play.clear(play.getByLabelText(PATTERN));
                    play.keyboard("(x)=");
                    play.keyboard("{Control>}{Alt>}{ArrowRight}{/Alt}{/Control}");
                    play.waitFor(() -> play.expect(play.getByText("junk_line", ShapeshifterEditorStories.STRIP_NAME))
                            .toBeVisible());
                    play.expect(play.getByLabelText(PATTERN)).not().toBeVisible();
                    play.expect(play.getByText(JUNK_PATTERN, MATCH_PATTERN)).toBeVisible();
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByText("(x)=", MATCH_PATTERN)).toBeVisible());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A mode renamed where it is shown keeps the selection, and so the cursor: the
                // selection used to be lost while the rows were redrawn and found again, and
                // losing it moved the cursor to the document (design 47 §7.8, finding 5)
                .story("RenamingAModeLeavesTheCursorWhereItWas", context -> ShapeshifterEditorStories.render(context,
                        false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.runButton(play)).toBeVisible());
                    play.click(ShapeshifterEditorStories.runButton(play));
                    Walkthrough.expectTried(play, "pair", "2");
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.querySelector(ShapeshifterEditorStories.CRUMB_CURRENT))
                            .toHaveTextContent(TextMatch.containing("pair")));
                    play.click(play.getByText(KV_MODE));
                    play.waitFor(() -> play.expect(play.getByText("kv", ShapeshifterEditorStories.STRIP_NAME))
                            .toBeVisible());
                    play.click(play.getByRole("button", EDIT_NAME));
                    play.waitFor(() -> play.expect(play.getByLabelText(MODE)).toHaveFocus());
                    play.keyboard("pairs{Enter}");
                    play.waitFor(() -> play.expect(play.getByText("mode: pairs")).toBeVisible());
                    play.expect(play.getByText("pairs", ShapeshifterEditorStories.STRIP_NAME)).toBeVisible();
                    play.expect(play.querySelector(ShapeshifterEditorStories.CRUMB_CURRENT))
                            .toHaveTextContent(TextMatch.containing("pair"));
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A mode with no templates is the editor's own until one is made, and is a row
                // only while the project can be added to. Locked with it shown, its pane goes
                // with its row, and the document is shown (design 47 §7.8, finding 6)
                .story("LockingWithAnEmptyModeShownShowsTheDocument",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByTitle(Walkthrough.ADD_MODE)).toBeInTheDocument());
                    play.click(play.getByTitle(Walkthrough.ADD_MODE));
                    play.waitFor(() -> play.expect(play.getByLabelText(MODE)).toHaveFocus());
                    play.keyboard("{Enter}");
                    play.waitFor(() -> play.expect(play.getByText("new mode", ShapeshifterEditorStories.STRIP_NAME))
                            .toBeVisible());
                    play.run("lock the document, without reading it again", () ->
                            ShapeshifterEditorStories.host().setReadOnly(true));
                    play.waitFor(() -> play.expect(play.queryByText("mode: new mode")).toBeNull());
                    play.waitFor(() -> play.expect(play.getByText("strict_kv", ShapeshifterEditorStories.STRIP_NAME))
                            .toBeVisible());
                    play.expect(play.queryByText("new mode", ShapeshifterEditorStories.STRIP_NAME)).toBeNull();
                    ShapeshifterEditorStories.expectNoProblems(play);
                });
    }

    // The title's edit button, which opens a template's name and its settings
    private static Query editTitle(final Play play) {
        return play.getByRole("button", ShapeshifterEditorStories.EDIT_TITLE);
    }

    // The pattern a patternInfo request asks about
    private static String patternOf(final RecordedRequest request) {
        final Object body = JsonValues.parse(request.getBody());
        return body instanceof Map
                ? String.valueOf(((Map<?, ?>) body).get("pattern"))
                : null;
    }

    // The match as it reads at rest
    private static Query matchOpen(final Play play) {
        return play.querySelector(ShapeshifterEditorStories.MATCH_OPEN);
    }

    // The names of the templates in the kv and later modes, in the panel's order: the rows
    // that are templates, without the document, the sections and the add links
    private static Supplier<?> names(final Play play) {
        return play.getAllByText(TextMatch.regex("^(pair|junk_line|line|word|new template( 2)?)$"),
                ShapeshifterEditorStories.TEMPLATE_NAME).textContents();
    }
}
