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

import stroom.document.client.editor.DocumentEditorHost;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.shapeshifter.shared.ShapeshifterDoc;

/// Undo and redo in the Design tab (design 49).
public final class ShapeshifterUndoStories {

    private static final String DISABLED = "aria-disabled";
    private static final String TRUE = "true";
    private static final String LITERAL = ".body-card__literal";

    private static final String PAIR_PATTERN = "([a-z]+)=([^\\n]*)\\n";
    // The end of the project's name, on the second line of the Source tab's text
    private static final String END_OF_NAME =
            "{Control>}{Home}{/Control}{ArrowDown}{End}{ArrowLeft}{ArrowLeft}";

    private ShapeshifterUndoStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Shapeshifter/ShapeshifterUndo", ShapeshifterUndoStories.class)
                .layout(StoryLayout.FULLSCREEN)

                // Nothing done is nothing to undo
                .story("NothingToUndo", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.expect(undo(play)).toHaveAttribute(DISABLED, TRUE);
                    play.expect(redo(play)).toHaveAttribute(DISABLED, TRUE);
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A card added is undone and redone; undone, the document is as it was saved
                .story("ACardIsUndoneAndRedone", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(hello(play)).toBeNull());
                    play.waitFor(() -> play.expect(save(play)).toHaveAttribute(DISABLED, TRUE));
                    play.expect(undo(play)).toHaveAttribute(DISABLED, TRUE);
                    play.click(redo(play));
                    play.waitFor(() -> play.expect(hello(play)).toBeVisible());
                    play.waitFor(() -> play.expect(save(play)).not().toHaveAttribute(DISABLED));
                    play.expect(redo(play)).toHaveAttribute(DISABLED, TRUE);
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // An edit after an undo drops what was undone
                .story("AnEditAfterAnUndoDropsTheRedo", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(redo(play)).not().toHaveAttribute(DISABLED));
                    addLine(play, "goodbye");
                    play.expect(redo(play)).toHaveAttribute(DISABLED, TRUE);
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A change to another template is shown as it is undone
                .story("WhatIsUndoneIsShown", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    ShapeshifterEditorStories.selectTemplate(play, "junk_line");
                    expectShown(play, "junk_line");
                    play.click(undo(play));
                    expectShown(play, "pair");
                    play.expect(hello(play)).toBeNull();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A template removed comes back, selected
                .story("ATemplateRemovedComesBack", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "junk_line");
                    expectShown(play, "junk_line");
                    play.click(play.getByRole("button", "Remove template"));
                    final Query ok = screen.queryByRole("button", StroomDom.button("OK"));
                    play.waitFor(() -> play.expect(ok).toBeVisible());
                    play.click(ok);
                    play.waitFor(() -> play.expect(row(play, "junk_line")).toBeNull());
                    play.click(undo(play));
                    expectShown(play, "junk_line");
                    play.waitFor(() -> play.expect(row(play, "junk_line")).toBeVisible());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A mode renamed is renamed back, and is still what is shown
                .story("AModeIsRenamedBack", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByText("mode: kv")).toBeVisible());
                    play.click(play.getByText("mode: kv"));
                    expectShown(play, "kv");
                    play.click(play.getByRole("button", TextMatch.regex("^Edit the name$")));
                    play.waitFor(() -> play.expect(play.getByLabelText("Mode name")).toHaveFocus());
                    play.keyboard("pairs{Enter}");
                    expectShown(play, "pairs");
                    play.click(undo(play));
                    expectShown(play, "kv");
                    play.click(redo(play));
                    expectShown(play, "pairs");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A pattern typed in the workbench is one step, the pause not waited for
                .story("APatternTypedIsOneStep", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    expectShown(play, "pair");
                    play.click(ShapeshifterEditorStories.workbenchButton(play));
                    play.waitFor(() -> play.expect(play.getByLabelText("Pattern")).toBeVisible());
                    play.clear(play.getByLabelText("Pattern"));
                    play.type(play.getByLabelText("Pattern"), "abc");
                    play.waitFor(() -> play.expect(undo(play)).not().toHaveAttribute(DISABLED));
                    play.type(play.getByLabelText("Pattern"), "def");
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(play.getByLabelText("Pattern"))
                            .toHaveValue(PAIR_PATTERN));
                    play.expect(undo(play)).toHaveAttribute(DISABLED, TRUE);
                    // and what was typed and not committed was taken first, so it is there to redo
                    play.click(redo(play));
                    play.waitFor(() -> play.expect(play.getByLabelText("Pattern")).toHaveValue("abcdef"));
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A card open is taken and then undone, so that nothing typed is lost
                .story("ADraftIsTakenThenUndone", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeInTheDocument());
                    addLine(play, "first");
                    play.click(ShapeshifterEditorStories.addLink(play));
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    play.keyboard("hello");
                    play.click(undo(play));
                    ShapeshifterEditorStories.expectNoCardOpen(play);
                    play.waitFor(() -> play.expect(redo(play)).not().toHaveAttribute(DISABLED));
                    play.expect(hello(play)).toBeNull();
                    play.expect(play.getByText("\"first\"", LITERAL)).toBeVisible();
                    play.click(redo(play));
                    play.waitFor(() -> play.expect(hello(play)).toBeVisible());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // From the keyboard, twice, with the keyboard on a row; in a field it is the field's
                .story("FromTheKeyboard", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    addLine(play, "again");
                    play.click(ShapeshifterEditorStories.addLink(play));
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    play.keyboard("{Control>}z{/Control}");
                    play.expect(play.getByText("\"again\"", LITERAL)).toBeVisible();
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toHaveFocus());
                    play.keyboard("{Control>}z{/Control}");
                    play.waitFor(() -> play.expect(play.queryByText("\"again\"", LITERAL)).toBeNull());
                    play.keyboard("{Control>}z{/Control}");
                    play.waitFor(() -> play.expect(hello(play)).toBeNull());
                    play.keyboard("{Control>}y{/Control}");
                    play.waitFor(() -> play.expect(hello(play)).toBeVisible());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // The Source tab's text has its own undo, so the document's is the Design tab's
                .story("NotOnTheSourceTab", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    play.click(play.getByText("Source", StroomDom.LINK_TAB_LABEL));
                    play.waitFor(() -> play.expect(undo(play)).toHaveAttribute(DISABLED, TRUE));
                    play.click(play.getByText("Design", StroomDom.LINK_TAB_LABEL));
                    play.waitFor(() -> play.expect(undo(play)).not().toHaveAttribute(DISABLED));
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A title's name and colour, taken together, are one step
                .story("ANameAndAColourAreOneStep", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    expectShown(play, "pair");
                    final Query chip = play.querySelector(".template-strip__head .chip");
                    final String[] was = {null};
                    play.run("note the colour", () -> was[0] = colour(chip));
                    play.click(play.getByRole("button", ShapeshifterEditorStories.EDIT_TITLE));
                    play.waitFor(() -> play.expect(play.getByLabelText("Template name")).toHaveFocus());
                    play.click(play.getByTitle("The palette by position"));
                    play.click(play.querySelector(".colour-palette__swatch:nth-child(7)"));
                    play.clear(play.getByLabelText("Template name"));
                    play.type(play.getByLabelText("Template name"), "couple{Enter}");
                    expectShown(play, "couple");
                    play.waitFor(() -> play.expect("the colour is another", () -> colour(chip).equals(was[0]))
                            .toEqual(false));
                    play.click(undo(play));
                    expectShown(play, "pair");
                    play.waitFor(() -> play.expect("the colour is back", () -> colour(chip).equals(was[0]))
                            .toEqual(true));
                    play.expect(undo(play)).toHaveAttribute(DISABLED, TRUE);
                    play.waitFor(() -> play.expect(save(play)).toHaveAttribute(DISABLED, TRUE));
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // The Source tab's text typed at two sittings is one step here
                .story("ASpellInTheSourceTabIsOneStep", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    expectProjectName(play, "strict_kv");
                    typeInSource(play, "x");
                    expectProjectName(play, "strict_kvx");
                    typeInSource(play, "y");
                    expectProjectName(play, "strict_kvxy");
                    play.click(undo(play));
                    expectProjectName(play, "strict_kv");
                    play.expect(undo(play)).toHaveAttribute(DISABLED, TRUE);
                    play.click(redo(play));
                    expectProjectName(play, "strict_kvxy");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A template whose match is not a regex, undone from its workbench, leaves alone the
                // regex that was shown there before it
                .story("AFormLeftIsNotTaken", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openWorkbench(play, "junk_line");
                    ShapeshifterEditorStories.pickFromBox(play, screen, play.getByLabelText("Match kind"),
                            "framed sequence");
                    play.waitFor(() -> play.expect(undo(play)).not().toHaveAttribute(DISABLED));
                    closeWorkbench(play);
                    openWorkbench(play, "pair");
                    play.waitFor(() -> play.expect(play.getByLabelText("Pattern")).toHaveValue(PAIR_PATTERN));
                    closeWorkbench(play);
                    play.click(play.querySelector(ShapeshifterEditorStories.MATCH_OPEN));
                    play.waitFor(() -> play.expect(play.getByLabelText("Match pattern")).toHaveFocus());
                    play.clear(play.getByLabelText("Match pattern"));
                    play.type(play.getByLabelText("Match pattern"), "abc{Enter}");
                    play.waitFor(() -> play.expect(play.querySelector(ShapeshifterEditorStories.MATCH_OPEN))
                            .toHaveTextContent("abc"));
                    openWorkbench(play, "junk_line");
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(play.getByLabelText("Pattern")).toHaveValue(PAIR_PATTERN));
                    play.expect(undo(play)).not().toHaveAttribute(DISABLED);
                    play.click(redo(play));
                    play.waitFor(() -> play.expect(play.getByLabelText("Pattern")).toHaveValue("abc"));
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A card with something typed in it stays open through a redo elsewhere, and is not
                // recorded, which would leave nothing to redo
                .story("ARedoLeavesADraftOpen", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    addLine(play, "again");
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(play.queryByText("\"again\"", LITERAL)).toBeNull());
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(hello(play)).toBeNull());
                    ShapeshifterEditorStories.selectTemplate(play, "junk_line");
                    expectShown(play, "junk_line");
                    typeLine(play, "draft");
                    play.click(redo(play));
                    play.waitFor(() -> play.expect(undo(play)).not().toHaveAttribute(DISABLED));
                    play.expect(redo(play)).not().toHaveAttribute(DISABLED);
                    play.click(redo(play));
                    play.waitFor(() -> play.expect(redo(play)).toHaveAttribute(DISABLED, TRUE));
                    expectShown(play, "junk_line");
                    play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveValue("draft");
                    play.click(play.getByLabelText(Walkthrough.ENTRY));
                    play.keyboard("{Escape}");
                    ShapeshifterEditorStories.expectNoCardOpen(play);
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(hello(play)).toBeVisible());
                    play.expect(play.getByText("\"again\"", LITERAL)).toBeVisible();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A redo of what is open under the draft is refused, and said; cancelled, it redoes
                .story("ARedoUnderADraftIsRefused", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(hello(play)).toBeNull());
                    typeLine(play, "draft");
                    play.click(redo(play));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.ALERT_SPY)).toHaveBeenCalledTimes(1));
                    dismiss(play);
                    play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveValue("draft");
                    play.expect(redo(play)).not().toHaveAttribute(DISABLED);
                    play.click(play.getByLabelText(Walkthrough.ENTRY));
                    play.keyboard("{Escape}");
                    ShapeshifterEditorStories.expectNoCardOpen(play);
                    play.click(redo(play));
                    play.waitFor(() -> play.expect(hello(play)).toBeVisible());
                })
                // With nothing to redo, the key leaves what is open as it is
                .story("NothingToRedoLeavesADraft", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    expectShown(play, "pair");
                    typeLine(play, "draft");
                    play.run("focus a button of the strip", () -> play.getByRole("button",
                            ShapeshifterEditorStories.EDIT_TITLE).element().get().focus());
                    play.keyboard("{Control>}y{/Control}");
                    play.sleep(100);
                    play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveValue("draft");
                    play.expect(undo(play)).toHaveAttribute(DISABLED, TRUE);
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A mode named and not used yet is a step of its own, and not of the edit before it
                .story("AModeNamedIsAStep", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    play.click(play.getByTitle(Walkthrough.ADD_MODE));
                    play.waitFor(() -> play.expect(play.getByLabelText("Mode name")).toHaveFocus());
                    play.keyboard("later{Enter}");
                    play.waitFor(() -> play.expect(play.getByText("mode: later")).toBeVisible());
                    play.click(play.getByRole("button", "Remove template"));
                    play.waitFor(() -> play.expect(play.queryByText("mode: later")).toBeNull());
                    // Removed, it comes back; renamed, it is renamed back; made, it goes
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(play.getByText("mode: later")).toBeVisible());
                    expectShown(play, "later");
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(play.getByText("mode: new mode")).toBeVisible());
                    play.expect(play.queryByText("mode: later")).toBeNull();
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(play.queryByText("mode: new mode")).toBeNull());
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(hello(play)).toBeVisible());
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(hello(play)).toBeNull());
                    play.expect(undo(play)).toHaveAttribute(DISABLED, TRUE);
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A mode's name being typed is taken, and then undone
                .story("AModeNameTypedIsTakenThenUndone", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    editModeName(play, "kv", "pairs");
                    play.click(undo(play));
                    expectShown(play, "kv");
                    play.waitFor(() -> play.expect(redo(play)).not().toHaveAttribute(DISABLED));
                    play.click(redo(play));
                    expectShown(play, "pairs");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // One that cannot be taken stays open, and nothing is undone
                .story("AModeNameThatCannotBeTaken", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    editModeName(play, "kv", "{Backspace}");
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.ALERT_SPY)).toHaveBeenCalledTimes(1));
                    dismiss(play);
                    play.expect(play.getByLabelText("Mode name")).toHaveValue("");
                    play.expect(redo(play)).toHaveAttribute(DISABLED, TRUE);
                    play.click(play.getByLabelText("Mode name"));
                    play.keyboard("{Escape}");
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(hello(play)).toBeVisible());
                })
                // So with a part's name, in the workbench
                .story("APartNameTypedIsTakenThenUndone", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    expectShown(play, "pair");
                    play.click(play.getByRole("button", TextMatch.regex("^Add a pattern part")));
                    play.waitFor(() -> play.expect(play.getByLabelText("Pattern part name")).toHaveFocus());
                    play.keyboard("ipv4");
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(redo(play)).not().toHaveAttribute(DISABLED));
                    play.waitFor(() -> play.expect(row(play, "new part")).toBeVisible());
                    play.click(redo(play));
                    play.waitFor(() -> play.expect(row(play, "ipv4")).toBeVisible());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A mode of one template that nothing dispatches into, renamed back, is what is shown,
                // and not the template, which is all of the project that is different
                .story("AModeOfOneTemplateIsRenamedBack", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByTitle(Walkthrough.ADD_MODE)).toBeInTheDocument());
                    play.click(play.getByTitle(Walkthrough.ADD_MODE));
                    play.waitFor(() -> play.expect(play.getByLabelText("Mode name")).toHaveFocus());
                    play.keyboard("later{Enter}");
                    play.waitFor(() -> play.expect(Walkthrough.modeAddLink(play)).toHaveFocus());
                    play.keyboard("{Enter}");
                    play.waitFor(() -> play.expect(play.getByLabelText("Template name")).toHaveFocus());
                    play.keyboard("{Escape}");
                    expectShown(play, "new template");
                    editModeName(play, "later", "pairs{Enter}");
                    expectShown(play, "pairs");
                    play.click(undo(play));
                    expectShown(play, "later");
                    play.click(redo(play));
                    expectShown(play, "pairs");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Typed in the Source tab and undone at once, it is what is undone, and is not lost
                .story("SourceTypedIsTakenOnLeaving", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    typeInSource(play, "x");
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(redo(play)).not().toHaveAttribute(DISABLED));
                    expectProjectName(play, "strict_kv");
                    play.click(redo(play));
                    expectProjectName(play, "strict_kvx");
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(hello(play)).toBeVisible());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Typed in the Source tab and saved at once, it is an edit and not another document
                .story("SourceSavedAtOnceKeepsItsHistory", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    play.click(play.getByText("Source", StroomDom.LINK_TAB_LABEL));
                    play.waitFor(() -> play.expect(play.querySelector(".ace_content")).toBeVisible());
                    play.click(play.querySelector(".ace_content"));
                    play.keyboard(END_OF_NAME);
                    play.keyboard("x");
                    play.run("save and read it back", () -> {
                        final DocumentEditorHost<ShapeshifterDoc> host = ShapeshifterEditorStories.host();
                        host.read(host.getDocRef(), host.write(host.getEntity()));
                    });
                    play.click(play.getByText("Design", StroomDom.LINK_TAB_LABEL));
                    play.waitFor(() -> play.expect(undo(play)).not().toHaveAttribute(DISABLED));
                    play.click(undo(play));
                    play.waitFor(() -> play.expect(redo(play)).not().toHaveAttribute(DISABLED));
                    expectProjectName(play, "strict_kv");
                    play.click(redo(play));
                    expectProjectName(play, "strict_kvx");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Locked, there is nothing to undo; unlocked, there is what there was
                .story("ReadOnly", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    play.run("lock the document", () -> ShapeshifterEditorStories.host().setReadOnly(true));
                    play.waitFor(() -> play.expect(undo(play)).toHaveAttribute(DISABLED, TRUE));
                    play.run("unlock the document", () -> ShapeshifterEditorStories.host().setReadOnly(false));
                    play.waitFor(() -> play.expect(undo(play)).not().toHaveAttribute(DISABLED));
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Read again as someone else has left it: another document, with no history
                .story("ReadAgainHasNoHistory", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    addLine(play, "hello");
                    play.run("read the document again", () -> {
                        final DocumentEditorHost<ShapeshifterDoc> host = ShapeshifterEditorStories.host();
                        host.read(host.getDocRef(), host.getEntity().copy()
                                .data(ShapeshifterEditorStories.PROJECT.replace("junk_line", "other_line"))
                                .build());
                    });
                    play.waitFor(() -> play.expect(undo(play)).toHaveAttribute(DISABLED, TRUE));
                    ShapeshifterEditorStories.expectNoProblems(play);
                });
    }

    // A text card added to the pair, and the empty line after it closed
    private static void addLine(final Play play, final String text) {
        ShapeshifterEditorStories.selectTemplate(play, "pair");
        expectShown(play, "pair");
        play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeInTheDocument());
        play.click(ShapeshifterEditorStories.addLink(play));
        play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
        play.keyboard(text + "{Enter}");
        play.waitFor(() -> play.expect(play.getByText("\"" + text + "\"", LITERAL)).toBeVisible());
        play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
        play.keyboard("{Escape}");
        ShapeshifterEditorStories.expectNoCardOpen(play);
        play.waitFor(() -> play.expect(undo(play)).not().toHaveAttribute(DISABLED));
    }

    // A new card in the template shown, with something typed in it and not taken
    private static void typeLine(final Play play, final String text) {
        play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeInTheDocument());
        play.click(ShapeshifterEditorStories.addLink(play));
        play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
        play.keyboard(text);
    }

    private static void editModeName(final Play play, final String mode, final String keys) {
        play.waitFor(() -> play.expect(play.getByText("mode: " + mode)).toBeVisible());
        play.click(play.getByText("mode: " + mode));
        expectShown(play, mode);
        play.click(play.getByRole("button", TextMatch.regex("^Edit the name$")));
        play.waitFor(() -> play.expect(play.getByLabelText("Mode name")).toHaveFocus());
        play.keyboard(keys);
    }

    private static void openWorkbench(final Play play, final String name) {
        ShapeshifterEditorStories.selectTemplate(play, name);
        expectShown(play, name);
        play.click(ShapeshifterEditorStories.workbenchButton(play));
        play.waitFor(() -> play.expect(play.getByLabelText("Match kind")).toBeInTheDocument());
    }

    private static void closeWorkbench(final Play play) {
        play.click(play.getByRole("button", TextMatch.regex("close the workbench", "i")));
        play.waitFor(() -> play.expect(play.querySelector(ShapeshifterEditorStories.MATCH_OPEN)).toBeVisible());
    }

    // The alert said, and closed
    private static void dismiss(final Play play) {
        final Query close = play.screen().queryByRole("button", StroomDom.button("Close"));
        play.waitFor(() -> play.expect(close).toBeVisible());
        play.click(close);
        play.waitFor(() -> play.expect(close).toBeNull());
    }

    // Typed at the end of the project's name, which is on the text's second line
    private static void typeInSource(final Play play, final String text) {
        play.click(play.getByText("Source", StroomDom.LINK_TAB_LABEL));
        play.waitFor(() -> play.expect(play.querySelector(".ace_content")).toBeVisible());
        play.click(play.querySelector(".ace_content"));
        play.keyboard(END_OF_NAME + text);
        // The text is parsed a moment after it is typed
        play.waitFor(() -> play.expect(save(play)).not().toHaveAttribute(DISABLED));
        play.click(play.getByText("Design", StroomDom.LINK_TAB_LABEL));
    }

    private static void expectProjectName(final Play play, final String name) {
        play.waitFor(() -> play.expect(play.getByLabelText("Project name")).toHaveValue(name));
    }

    private static String colour(final Query chip) {
        return chip.element().get().getAttribute("style");
    }

    private static void expectShown(final Play play, final String name) {
        play.waitFor(() -> play.expect(play.getByText(name, ShapeshifterEditorStories.STRIP_NAME)).toBeVisible());
    }

    private static Query hello(final Play play) {
        return play.queryByText("\"hello\"", LITERAL);
    }

    private static Query row(final Play play, final String name) {
        return play.queryByText(name, ShapeshifterEditorStories.TEMPLATE_NAME);
    }

    private static Query undo(final Play play) {
        return play.getByRole("button", "Undo (Ctrl+Z)");
    }

    private static Query redo(final Play play) {
        return play.getByRole("button", "Redo (Ctrl+Shift+Z)");
    }

    private static Query save(final Play play) {
        return play.getByRole("button", "Save");
    }
}
