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

import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.Spy;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;

import com.google.gwt.dom.client.Element;

import java.util.Arrays;

/// A card edited where it is (design 47 D): clicked, a card's line gives way to its editor — the
/// kind first, then that kind's fields — in the list, with no dialog over it. What the editor
/// holds is a draft until it is taken.
///
/// These stories are about that draft: how it is taken and dropped, what else takes it, that a
/// run landing in the middle of it does not lose it, and that a new card held as a draft does
/// not send the next click to the wrong card. The forms themselves — what each kind's fields
/// are — are [ShapeshifterEditorStories]'s.
public final class ShapeshifterCardStories {

    private static final String TITLE = "App/Shapeshifter/ShapeshifterCard";

    private static final String SUMMARY = ".body-card__literal";

    // The toolbar over the rows, which acts on the selected one.
    private static final String ABOVE = "Insert an instruction above the selected one";
    private static final String UP = "Move the selected instruction up";
    private static final String DOWN = "Move the selected instruction down";
    private static final String REMOVE = "Remove the selected instruction";

    private ShapeshifterCardStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component(TITLE, ShapeshifterCardStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // A card, open. The pair's first value-of: its kind, and the one field that
                // kind has, with the keyboard already in it. Left open, for the screenshot
                .story("OpensInPlace", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    final Play card = open(play, "value-of", 0);
                    play.expect(card.getByLabelText("Instruction")).toHaveValue("value-of");
                    play.expect(card.getByLabelText("Select")).toHaveValue("$1");
                    play.expect(card.getByLabelText("Select")).toHaveFocus();
                    // In the list, and not over it
                    play.expect(screen.queryByRole("dialog")).toBeNull();
                    // The other four cards are still there to be read
                    play.expect(play.getAllByText("text", ShapeshifterEditorStories.CARD_KIND)
                            .first()).toBeVisible();
                    // Opening a card is not an edit
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Enter in a field takes the draft, and the keyboard goes back to the card
                .story("EnterTakesTheDraft", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play card = open(play, "value-of", 0);
                    play.clear(card.getByLabelText("Select"));
                    card.type(card.getByLabelText("Select"), "$2");
                    // Typing reaches nothing: what the card holds is a draft
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    play.keyboard("{Enter}");
                    ShapeshifterEditorStories.expectNoCardOpen(play);
                    play.waitFor(() -> play.expect(play.getAllByText("$2", SUMMARY).count()).toEqual(2));
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    play.waitFor(() -> play.expect(line(play, "value-of", 0)).toHaveFocus());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Escape drops it: the card as it was, nothing to save, the keyboard on the card
                .story("EscapeDropsTheDraft", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play card = open(play, "value-of", 0);
                    card.type(card.getByLabelText("Select"), "junk");
                    play.keyboard("{Escape}");
                    ShapeshifterEditorStories.expectNoCardOpen(play);
                    play.expect(play.getAllByText("$1", SUMMARY).count()).toEqual(1);
                    play.expect(play.queryByText("$1junk", SUMMARY)).toBeNull();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    play.waitFor(() -> play.expect(line(play, "value-of", 0)).toHaveFocus());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A draft that cannot be written stays open and says why, and nothing is changed
                .story("ADraftThatCannotBeWrittenStays",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play card = open(play, "value-of", 0);
                    play.clear(card.getByLabelText("Select"));
                    play.click(card.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.ALERT_SPY))
                            .toHaveBeenCalled());
                    play.expect(play.getByLabelText("Instruction")).toBeInTheDocument();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                })
                // Clicking a card's kind opens it with the kinds offered, and choosing one
                // changes the fields there and then. Text becomes an element: the area goes,
                // a name and a namespace arrive
                .story("TheKindOffersTheKinds", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getAllByText("text",
                            ShapeshifterEditorStories.CARD_KIND).first()).toBeInTheDocument());
                    play.click(play.getAllByText("text", ShapeshifterEditorStories.CARD_KIND).nth(1));
                    // The list is open without a second click
                    play.click(screen.findByText("element", ShapeshifterEditorStories.SELECTION_ITEM));
                    final Play card = ShapeshifterEditorStories.cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
                    play.expect(card.getByLabelText("Namespace")).toBeVisible();
                    play.expect(card.queryByLabelText("Text")).toBeNull();
                    card.type(card.getByLabelText("Name"), "pair");
                    play.keyboard("{Enter}");
                    ShapeshifterEditorStories.expectNoCardOpen(play);
                    play.waitFor(() -> play.expect(play.querySelectorAll(
                            ShapeshifterEditorStories.CARD_KIND).textContents()).toEqual(
                            // An element holds a list of its own, which ends with a New
                            // row as the body does
                            Arrays.asList("text", "value-of", "element", ShapeshifterEditorStories.NEW_ROW,
                                    "value-of", "text", ShapeshifterEditorStories.NEW_ROW)));
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A list ends with its New row, which is a row like the others and not a field
                // waiting to be typed into: it opens as they do, into a card whose kind is
                // `auto` — what is typed says what it is — until one is chosen
                .story("TheNewRowOpensLikeAnyOther", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.newRow(play))
                            .toBeInTheDocument());
                    // At rest there is nothing in the list to type into
                    play.expect(play.queryByLabelText(Walkthrough.ENTRY)).toBeNull();
                    play.expect(play.queryByLabelText("Instruction")).toBeNull();
                    play.click(ShapeshifterEditorStories.newRow(play));
                    final Play card = ShapeshifterEditorStories.cardEditor(play);
                    play.expect(card.getByLabelText("Instruction")).toHaveValue("auto");
                    play.waitFor(() -> play.expect(card.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    // It has taken the row's place, and opening it is not an edit
                    play.expect(play.queryByText(ShapeshifterEditorStories.NEW_ROW,
                            ShapeshifterEditorStories.CARD_KIND)).toBeNull();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    // Enter on a line left empty is the author having finished: no card, and
                    // the keyboard back on the row
                    play.keyboard("{Enter}");
                    ShapeshifterEditorStories.expectNoCardOpen(play);
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.newRow(play)).toHaveFocus());
                    play.expect(play.querySelectorAll(ShapeshifterEditorStories.CARD_KIND).count())
                            .toEqual(6);
                    // Its kind, clicked, opens it with the kinds offered, and choosing one
                    // changes the fields there and then
                    ShapeshifterEditorStories.newCard(play, screen, "value-of");
                    final Play chosen = ShapeshifterEditorStories.cardEditor(play);
                    play.waitFor(() -> play.expect(chosen.getByLabelText("Select")).toBeVisible());
                    play.expect(chosen.getByLabelText("Instruction")).toHaveValue("value-of");
                    play.expect(chosen.queryByLabelText(Walkthrough.ENTRY)).toBeNull();
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.newRow(play)).toHaveFocus());
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A holder changed to a kind with no place for what it holds: it asks, as
                // removing a holder does, and until it is answered nothing has changed
                .story("AKindWithNoPlaceForWhatACardHoldsAsks", ShapeshifterEditorStories::renderComposite)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "branch");
                    play.waitFor(() -> play.expect(play.getByText("variable",
                            ShapeshifterEditorStories.CARD_KIND)).toBeInTheDocument());
                    play.click(play.getByText("variable", ShapeshifterEditorStories.CARD_KIND));
                    play.click(screen.findByText("text", ShapeshifterEditorStories.SELECTION_ITEM));
                    final Play card = ShapeshifterEditorStories.cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Text")).toBeVisible());
                    play.click(card.getByRole("button", StroomDom.button("OK")));
                    final Spy confirm = play.spy(ScreenHarness.CONFIRM_SPY);
                    play.waitFor(() -> play.expect("asked about the instruction it holds",
                            () -> String.valueOf(confirm.getCalls())
                                    .contains("drops the instruction it holds")).toEqual(true));
                    play.expect(play.getByLabelText("Instruction")).toBeInTheDocument();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    play.expect(play.spy(ScreenHarness.ALERT_SPY)).not().toHaveBeenCalled();
                })
                // The body is drawn again when a run's trace lands, a moment after every edit.
                // The open card is carried across as the widget it is: what was typed into it,
                // and the keyboard, are where they were
                .story("AnOpenCardSurvivesARun", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.newRow(play))
                            .toBeInTheDocument());
                    final Spy requests = play.spy(ScreenHarness.REQUEST_SPY);
                    final int[] runs = {0};
                    final Element[] before = {null};
                    // An edit, which sets a run off...
                    Walkthrough.enter(play, "tail");
                    // ...and a card opened and typed into before it lands
                    play.click(line(play, "value-of", 0));
                    final Play card = ShapeshifterEditorStories.cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Select")).toHaveFocus());
                    play.keyboard("abc");
                    play.run("note the New row and the runs so far", () -> {
                        before[0] = ShapeshifterEditorStories.newRow(play).element().get();
                        runs[0] = requests.getCalls().size();
                    });
                    play.waitFor(() -> play.expect("a run since the card was opened",
                            () -> requests.getCalls().size() > runs[0]).toEqual(true));
                    play.waitFor(() -> play.expect("the cards were drawn again",
                            () -> ShapeshifterEditorStories.newRow(play).element().get()
                                  != before[0]).toEqual(true));
                    play.expect(play.getByLabelText("Select")).toHaveValue("$1abc");
                    play.expect(play.getByLabelText("Select")).toHaveFocus();
                    play.keyboard("{Escape}");
                    ShapeshifterEditorStories.expectNoCardOpen(play);
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Opening another card takes the one that is open, if it can be written
                .story("OpeningAnotherCardTakesTheOpenOne",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play first = open(play, "value-of", 0);
                    play.clear(first.getByLabelText("Select"));
                    first.type(first.getByLabelText("Select"), "$9");
                    play.click(line(play, "text", 0));
                    play.waitFor(() -> play.expect(play.getByText("$9", SUMMARY)).toBeInTheDocument());
                    play.waitFor(() -> play.expect(play.getByLabelText("Instruction"))
                            .toHaveValue("text"));
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                    play.keyboard("{Escape}");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // The toolbar opens a new card above the selected row or below it: the same card
                // the New row opens, where it will go. It takes lines until Escape, each going
                // in where the last left off
                .story("InsertBeforeACard", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    // The second row selected — the first value-of — and a card asked for above it
                    open(play, "value-of", 0);
                    play.click(play.getByRole("button", ABOVE));
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    play.expect(play.getByLabelText("Instruction")).toHaveValue("auto");
                    // The list still ends with its New row: this is not it
                    play.expect(ShapeshifterEditorStories.newRow(play)).toBeInTheDocument();
                    play.keyboard("A{Enter}");
                    play.waitFor(() -> play.expect(play.querySelectorAll(
                            ShapeshifterEditorStories.CARD_KIND).textContents()).toEqual(
                            Arrays.asList("text", "text", "value-of", "text", "value-of", "text",
                                    ShapeshifterEditorStories.NEW_ROW)));
                    // Another is open after what was put in, with the keyboard
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    play.keyboard("$3{Enter}");
                    play.waitFor(() -> play.expect(play.querySelectorAll(SUMMARY).textContents())
                            .toSatisfy("A, then $3, then the $1 that was second", summaries ->
                                    String.valueOf(summaries).contains("\"A\", $3, $1")));
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    // Escape: no more, and the keyboard is on the row put in last
                    play.keyboard("{Escape}");
                    ShapeshifterEditorStories.expectNoCardOpen(play);
                    play.waitFor(() -> play.expect(line(play, "value-of", 0)).toHaveFocus());
                    // Below the last row — the seventh, now — is the New row's own place
                    play.click(line(play, "text", 3));
                    play.click(play.getByRole("button", "Insert an instruction below the selected one"));
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    play.expect(play.queryByText(ShapeshifterEditorStories.NEW_ROW,
                            ShapeshifterEditorStories.CARD_KIND)).toBeNull();
                    play.keyboard("{Escape}");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A new card of another kind, started from the one opened among the cards, is a draft
                // where it will go. Clicking a card below it takes the draft — which moves that
                // card one further on — and it is that card which opens, not the one that has
                // slid into its old place
                .story("ANewCardDoesNotSendTheNextClickAstray",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    open(play, "value-of", 0);
                    play.click(play.getByRole("button", ABOVE));
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    // Its kind chosen, where it is
                    final Play draft = ShapeshifterEditorStories.cardEditor(play);
                    ShapeshifterEditorStories.pickFromBox(play, screen,
                            draft.getByLabelText("Instruction"), "element");
                    play.waitFor(() -> play.expect(draft.getByLabelText("Name")).toBeVisible());
                    // The line has given way to the kind's own fields
                    play.expect(play.queryByLabelText(Walkthrough.ENTRY)).toBeNull();
                    draft.type(draft.getByLabelText("Name"), "wrap");
                    // The first value-of, which is the card after the draft
                    play.click(line(play, "value-of", 0));
                    play.waitFor(() -> play.expect(play.querySelectorAll(
                            ShapeshifterEditorStories.CARD_KIND).textContents()).toEqual(
                            // The element's own list ends with a New row, as the body does
                            Arrays.asList("text", "element", ShapeshifterEditorStories.NEW_ROW,
                                    "value-of", "text", "value-of", "text",
                                    ShapeshifterEditorStories.NEW_ROW)));
                    play.waitFor(() -> play.expect(play.getByLabelText("Instruction"))
                            .toHaveValue("value-of"));
                    play.expect(play.getByLabelText("Select")).toHaveValue("$1");
                    play.keyboard("{Escape}");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Selecting another template takes the draft if it can be written, and lets it
                // go without a word if it cannot
                .story("LeavingTheTemplateTakesTheDraft",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play kept = open(play, "value-of", 0);
                    play.clear(kept.getByLabelText("Select"));
                    kept.type(kept.getByLabelText("Select"), "$7");
                    ShapeshifterEditorStories.selectTemplate(play, "junk_line");
                    play.waitFor(() -> play.expect(play.getByRole("button", "Save"))
                            .not().toHaveAttribute("aria-disabled"));
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByText("$7", SUMMARY)).toBeInTheDocument());
                    ShapeshifterEditorStories.expectNoCardOpen(play);
                    // And one that cannot be written: an empty select
                    final Play lost = open(play, "value-of", 1);
                    play.clear(lost.getByLabelText("Select"));
                    ShapeshifterEditorStories.selectTemplate(play, "junk_line");
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByText("$2", SUMMARY)).toBeInTheDocument());
                    ShapeshifterEditorStories.expectNoCardOpen(play);
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // The rows are one list, and the keyboard is on one of them at a time. The arrows
                // move it, Enter opens the row it is on and Delete removes it. A run landing does
                // not take the keyboard off the list
                .story("TheKeyboardWalksTheRows", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    // On the second row: the way a row is reached from the keyboard is by Tab,
                    // and from the pointer it is by leaving the row it has just opened
                    open(play, "value-of", 0);
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(line(play, "value-of", 0)).toHaveFocus());
                    // One tab stop for the rows between them, and it is this one
                    play.expect(line(play, "value-of", 0)).toHaveAttribute("tabindex", "0");
                    play.expect(line(play, "text", 0)).toHaveAttribute("tabindex", "-1");
                    play.keyboard("{ArrowDown}");
                    play.expect(line(play, "text", 1)).toHaveFocus();
                    play.keyboard("{ArrowDown}");
                    play.expect(line(play, "value-of", 1)).toHaveFocus();
                    play.keyboard("{Home}");
                    play.expect(line(play, "text", 0)).toHaveFocus();
                    // The list's last row is its New row, walked to as the others are. Enter
                    // opens it as it opens them, and Escape puts the keyboard back on it
                    play.keyboard("{End}");
                    play.expect(ShapeshifterEditorStories.newRow(play)).toHaveFocus();
                    play.keyboard("{Enter}");
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.newRow(play)).toHaveFocus());
                    // and Delete, on a row that is not a card yet, removes nothing
                    play.keyboard("{Delete}");
                    play.expect(play.querySelectorAll(ShapeshifterEditorStories.CARD_KIND).count())
                            .toEqual(6);
                    play.keyboard("{ArrowUp}");
                    play.expect(line(play, "text", 2)).toHaveFocus();
                    play.keyboard("{ArrowUp}");
                    play.expect(line(play, "value-of", 1)).toHaveFocus();
                    // Enter opens the row the keyboard is on, and Escape puts it back there
                    play.keyboard("{Enter}");
                    play.waitFor(() -> play.expect(play.getByLabelText("Select")).toHaveValue("$2"));
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(line(play, "value-of", 1)).toHaveFocus());
                    // Delete removes the row, and the keyboard is on the row that came up into
                    // its place
                    final Spy requests = play.spy(ScreenHarness.REQUEST_SPY);
                    final int[] runs = {0};
                    play.run("note the runs so far", () -> runs[0] = requests.getCalls().size());
                    play.keyboard("{Delete}");
                    play.waitFor(() -> play.expect(play.querySelectorAll(
                            ShapeshifterEditorStories.CARD_KIND).textContents()).toEqual(
                            Arrays.asList("text", "value-of", "text", "text",
                                    ShapeshifterEditorStories.NEW_ROW)));
                    play.waitFor(() -> play.expect(line(play, "text", 2)).toHaveFocus());
                    // The run that edit set off draws the rows again; the keyboard stays on its row
                    play.waitFor(() -> play.expect("a run since the row was removed",
                            () -> requests.getCalls().size() > runs[0]).toEqual(true));
                    play.waitFor(() -> play.expect(play.getAllByText(TextMatch.containing("wrote"),
                            ".body-card__note").first()).toBeInTheDocument());
                    play.expect(line(play, "text", 2)).toHaveFocus();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // What is done to a row is done from one toolbar over the rows, to the selected
                // row, and not from buttons on every row. With no row selected it offers nothing;
                // it follows the row it moves; and a row just added is the selected one once
                // the author has finished adding
                .story("TheToolbarActsOnTheSelectedRow",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.newRow(play))
                            .toBeInTheDocument());
                    play.expect(play.getByRole("button", UP)).toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByRole("button", REMOVE)).toHaveAttribute("aria-disabled", "true");
                    // The second row, selected by leaving it
                    open(play, "value-of", 0);
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(play.getByRole("button", UP))
                            .not().toHaveAttribute("aria-disabled", "true"));
                    // Up: it is the first row now, the selection has gone with it, and there is
                    // no further up to go
                    play.click(play.getByRole("button", UP));
                    play.waitFor(() -> play.expect(play.querySelectorAll(
                            ShapeshifterEditorStories.CARD_KIND).textContents()).toEqual(
                            Arrays.asList("value-of", "text", "text", "value-of", "text",
                                    ShapeshifterEditorStories.NEW_ROW)));
                    play.waitFor(() -> play.expect(play.getByRole("button", UP))
                            .toHaveAttribute("aria-disabled", "true"));
                    play.click(play.getByRole("button", DOWN));
                    play.waitFor(() -> play.expect(play.querySelectorAll(
                            ShapeshifterEditorStories.CARD_KIND).textContents()).toEqual(
                            Arrays.asList("text", "value-of", "text", "value-of", "text",
                                    ShapeshifterEditorStories.NEW_ROW)));
                    // A line typed into the New row. While the card that opened after it is
                    // open no row is the selected one, and the toolbar offers nothing
                    Walkthrough.enter(play, "tail");
                    play.expect(play.getByRole("button", UP)).toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByRole("button", REMOVE)).toHaveAttribute("aria-disabled", "true");
                    // Escape: the row just added is the selected one, and Up moves it, not the
                    // row that was selected before
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(play.getByRole("button", UP))
                            .not().toHaveAttribute("aria-disabled", "true"));
                    play.click(play.getByRole("button", UP));
                    play.waitFor(() -> play.expect(play.querySelectorAll(SUMMARY).textContents())
                            .toSatisfy("tail above the closing tag", summaries ->
                                    String.valueOf(summaries).contains("\"tail\", \"</pair>")));
                    // Remove takes the selected row
                    play.click(play.getByRole("button", REMOVE));
                    play.waitFor(() -> play.expect(play.queryByText("\"tail\"", SUMMARY)).toBeNull());
                    play.expect(play.querySelectorAll(ShapeshifterEditorStories.CARD_KIND).count())
                            .toEqual(6);
                    // The New row is not a card yet. One can be put above it, which is to open
                    // it, and there is nothing of it to move, to remove or to come after
                    play.keyboard("{End}");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.newRow(play)).toHaveFocus());
                    play.expect(play.getByRole("button", UP)).toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByRole("button", REMOVE)).toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByRole("button", "Insert an instruction below the selected one"))
                            .toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByRole("button", ABOVE)).not().toHaveAttribute("aria-disabled", "true");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Read only, a card is something to read: it does not open, and offers no way
                // to add beside it
                .story("ReadOnlyCardsDoNotOpen", context -> ShapeshifterEditorStories.render(context, true))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getAllByText("value-of",
                            ShapeshifterEditorStories.CARD_KIND).first()).toBeInTheDocument());
                    play.click(line(play, "value-of", 0));
                    play.click(play.getAllByText("value-of", ShapeshifterEditorStories.CARD_KIND).first());
                    play.expect(play.queryByLabelText("Instruction")).toBeNull();
                    play.expect(play.queryByText(ShapeshifterEditorStories.NEW_ROW,
                            ShapeshifterEditorStories.CARD_KIND)).toBeNull();
                    // and there is no toolbar over the rows
                    play.expect(play.queryByRole("button", ABOVE)).toBeNull();
                    play.expect(play.queryByRole("button", "Remove the selected instruction")).toBeNull();
                    ShapeshifterEditorStories.expectNoProblems(play);
                });
    }

    // The nth card of a kind in the pair template's body, by its line. Its kind is a way in of
    // its own, which opens the card with the kinds offered.
    private static Query line(final Play play, final String kind, final int n) {
        return play.getAllByText(kind, ShapeshifterEditorStories.CARD_KIND).nth(n)
                .closest(".body-card__card");
    }

    // The pair template selected and one of its cards opened, as a Play scoped to the open card.
    private static Play open(final Play play, final String kind, final int n) {
        ShapeshifterEditorStories.selectTemplate(play, "pair");
        play.waitFor(() -> play.expect(play.getAllByText(kind, ShapeshifterEditorStories.CARD_KIND)
                .first()).toBeInTheDocument());
        play.click(line(play, kind, n));
        return ShapeshifterEditorStories.cardEditor(play);
    }
}
