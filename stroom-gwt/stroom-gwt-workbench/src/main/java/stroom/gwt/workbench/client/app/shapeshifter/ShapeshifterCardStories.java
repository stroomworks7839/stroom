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
import java.util.List;

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
    private static final String ADD = "Add an instruction below the selected one, or at the end";
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
                            Arrays.asList("text", "value-of", "element", "value-of", "text")));
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A list ends with `+ instruction`, the add link the declarations and the
                // captures have. Pressed, it gives way to a card whose kind is `auto` — what is
                // typed says what it is — until one is chosen
                .story("TheAddLinkOpensACard", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play))
                            .toBeInTheDocument());
                    play.expect(ShapeshifterEditorStories.addLink(play)).toHaveTextContent("+ instruction");
                    // At rest there is nothing in the list to type into, and the link is not a row
                    play.expect(play.queryByLabelText(Walkthrough.ENTRY)).toBeNull();
                    play.expect(play.queryByLabelText("Instruction")).toBeNull();
                    play.expect(play.querySelectorAll(ShapeshifterEditorStories.CARD_KIND).count())
                            .toEqual(5);
                    play.click(ShapeshifterEditorStories.addLink(play));
                    final Play card = ShapeshifterEditorStories.cardEditor(play);
                    play.expect(card.getByLabelText("Instruction")).toHaveValue("auto");
                    play.waitFor(() -> play.expect(card.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    // It has taken the link's place, and opening it is not an edit
                    play.expect(play.queryByTitle(ShapeshifterEditorStories.ADD_LINK)).toBeNull();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    // Enter on a line left empty is the author having finished: no card, and
                    // the keyboard back on the link
                    play.keyboard("{Enter}");
                    ShapeshifterEditorStories.expectNoCardOpen(play);
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toHaveFocus());
                    play.expect(play.querySelectorAll(ShapeshifterEditorStories.CARD_KIND).count())
                            .toEqual(5);
                    // Enter on the link presses it. A kind chosen changes the fields there and
                    // then, and Escape puts the link back
                    play.keyboard("{Enter}");
                    final Play chosen = ShapeshifterEditorStories.cardEditor(play);
                    ShapeshifterEditorStories.pickFromBox(play, screen,
                            chosen.getByLabelText("Instruction"), "value-of");
                    play.waitFor(() -> play.expect(chosen.getByLabelText("Select")).toBeVisible());
                    play.expect(chosen.queryByLabelText(Walkthrough.ENTRY)).toBeNull();
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toHaveFocus());
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
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play))
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
                    play.run("note the add link and the runs so far", () -> {
                        before[0] = ShapeshifterEditorStories.addLink(play).element().get();
                        runs[0] = requests.getCalls().size();
                    });
                    play.waitFor(() -> play.expect("a run since the card was opened",
                            () -> requests.getCalls().size() > runs[0]).toEqual(true));
                    play.waitFor(() -> play.expect("the cards were drawn again",
                            () -> ShapeshifterEditorStories.addLink(play).element().get()
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
                // The toolbar's Add opens a new card below the selected row: the same card the
                // add link opens, where it will go. It takes lines until Escape, each going in
                // where the last left off
                .story("AddBelowTheSelectedRow", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    // The first row selected, and a card asked for below it
                    open(play, "text", 0);
                    play.click(play.getByRole("button", ADD));
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    play.expect(play.getByLabelText("Instruction")).toHaveValue("auto");
                    // The list still ends with its add link: this is not it
                    play.expect(ShapeshifterEditorStories.addLink(play)).toBeInTheDocument();
                    play.keyboard("A{Enter}");
                    play.waitFor(() -> play.expect(play.querySelectorAll(
                            ShapeshifterEditorStories.CARD_KIND).textContents()).toEqual(
                            Arrays.asList("text", "text", "value-of", "text", "value-of", "text")));
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
                    // Below the last row — the seventh, now — is the add link's own place
                    play.click(line(play, "text", 3));
                    play.click(play.getByRole("button", ADD));
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    play.expect(play.queryByTitle(ShapeshifterEditorStories.ADD_LINK)).toBeNull();
                    // Dropped, the keyboard goes back to the row it was asked for from
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(line(play, "text", 3)).toHaveFocus());
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
                    // Below the first row, which is above the first value-of
                    open(play, "text", 0);
                    play.click(play.getByRole("button", ADD));
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
                            Arrays.asList("text", "element", "value-of", "text", "value-of", "text")));
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
                // A line left with a ${…} that is not a reference cannot be added and cannot stay
                // open, since what is open is going: it is said that it was not added, rather
                // than the line going without a word (design 47 §7.14)
                .story("ALineThatCannotBeAddedIsSaidToBeLost",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeVisible());
                    play.click(ShapeshifterEditorStories.addLink(play));
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    play.keyboard(Walkthrough.typed("<id>${id</id>"));
                    final Spy alert = play.spy(ScreenHarness.ALERT_SPY);
                    ShapeshifterEditorStories.selectTemplate(play, "junk_line");
                    play.waitFor(() -> play.expect("told the line was not added",
                            () -> String.valueOf(alert.getCalls()).contains("The line was not added")).toEqual(true));
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                })
                // A new if arrives with its first clause row open: an if with no clause is not
                // an instruction (design 47 I)
                .story("ANewIfHasItsFirstClause", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeVisible());
                    ShapeshifterEditorStories.newCard(play, screen, "if");
                    final Play card = ShapeshifterEditorStories.cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
                    play.expect(card.getAllByLabelText("Name").count()).toEqual(1);
                    play.expect(card.getByText("(no clauses yet)")).not().toBeVisible();
                })
                // A card made an if from another kind gets its first clause too, as a new one
                // does (design 47 §7.16)
                .story("ACardMadeAnIfHasItsFirstClause", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    final Play card = open(play, "value-of", 0);
                    ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Instruction"), "if");
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
                    play.expect(card.getAllByLabelText("Name").count()).toEqual(1);
                })
                // The mode a new dispatch starts on is a start: chosen otherwise, it stays chosen
                // when the kind is changed and changed back (design 47 §7.16)
                .story("ANewDispatchKeepsTheModeChosen", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    Walkthrough.addMode(play, "fields");
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeVisible());
                    ShapeshifterEditorStories.newCard(play, screen, "apply-templates");
                    final Play card = ShapeshifterEditorStories.cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Mode")).toHaveValue("fields"));
                    ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Mode"), "kv");
                    ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Instruction"),
                            "call-template");
                    ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Instruction"),
                            "apply-templates");
                    play.waitFor(() -> play.expect(card.getByLabelText("Mode")).toHaveValue("kv"));
                })
                // A Select naming a declared list is offered which entry it reads: one press, and
                // the subscript follows the name with the keyboard back in the field (design 44
                // Q14). A scalar is offered nothing, nor is a Select that is the collection taken
                // whole; and at the top of a body, where no for-each sets it, nor is [index()]
                .story("ASelectNamingAListIsOfferedWhichEntry", context -> ShapeshifterEditorStories.render(context,
                        false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.click(play.findByRole("button", "+ declaration"));
                    final Play dialog = ShapeshifterEditorStories.dialogMatching(play, screen, "New Declaration");
                    dialog.type(dialog.getByLabelText("Name"), "cells");
                    ShapeshifterEditorStories.pickFromBox(play, screen, dialog.getByLabelText("Type"), "list");
                    play.click(dialog.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.getByRole("button", StroomDom.button("cells")))
                            .toBeVisible());
                    // The collection a for-each walks, a for-each-group files and an
                    // apply-templates dispatches is the whole list
                    ShapeshifterEditorStories.newCard(play, screen, "for-each");
                    final Play card = ShapeshifterEditorStories.cardEditor(play);
                    for (final String whole : List.of("for-each", "for-each-group", "apply-templates")) {
                        ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Instruction"),
                                whole);
                        play.waitFor(() -> play.expect(card.getByLabelText("Select")).toBeVisible());
                        card.type(card.getByLabelText("Select"), "cells");
                        play.expect(card.queryByRole("button", StroomDom.button("[matchCount()]"))).toBeNull();
                    }
                    ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Instruction"),
                            "value-of");
                    // line is declared, as a scalar
                    card.type(card.getByLabelText("Select"), "line");
                    play.expect(card.queryByRole("button", StroomDom.button("[matchCount()]"))).toBeNull();
                    card.clear(card.getByLabelText("Select"));
                    card.type(card.getByLabelText("Select"), "cells");
                    play.waitFor(() -> play.expect(card.getByRole("button", StroomDom.button("[matchCount()]")))
                            .toBeVisible());
                    play.expect(card.queryByRole("button", StroomDom.button("[index()]"))).toBeNull();
                    play.click(card.getByRole("button", StroomDom.button("[matchCount()]")));
                    play.waitFor(() -> play.expect(card.getByLabelText("Select")).toHaveValue("cells[matchCount()]"));
                    play.expect(card.getByLabelText("Select")).toHaveFocus();
                    // It says which entry now, so there is nothing more to offer
                    play.expect(card.queryByRole("button", StroomDom.button("[matchCount()]"))).toBeNull();
                })
                // Inside a for-each, index() is set, so [index()] is offered too, and taken
                .story("ASelectInAForEachIsOfferedItsIndex", context -> ShapeshifterEditorStories.render(context,
                        false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.click(play.findByRole("button", "+ declaration"));
                    final Play dialog = ShapeshifterEditorStories.dialogMatching(play, screen, "New Declaration");
                    dialog.type(dialog.getByLabelText("Name"), "cells");
                    ShapeshifterEditorStories.pickFromBox(play, screen, dialog.getByLabelText("Type"), "list");
                    play.click(dialog.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.getByRole("button", StroomDom.button("cells")))
                            .toBeVisible());
                    // A for-each over the list, taken; the card that opens after it is left
                    ShapeshifterEditorStories.newCard(play, screen, "for-each");
                    final Play walk = ShapeshifterEditorStories.cardEditor(play);
                    play.waitFor(() -> play.expect(walk.getByLabelText("Select")).toBeVisible());
                    walk.type(walk.getByLabelText("Select"), "cells");
                    walk.type(walk.getByLabelText("As"), "c");
                    play.click(walk.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(walk.getByLabelText("Instruction")).toHaveValue("auto"));
                    play.keyboard("{Escape}");
                    // The for-each's own list is the first of the two that end with + instruction
                    play.click(play.getAllByTitle(ShapeshifterEditorStories.ADD_LINK).nth(0));
                    final Play card = ShapeshifterEditorStories.cardEditor(play);
                    ShapeshifterEditorStories.pickFromBox(play, screen, card.getByLabelText("Instruction"),
                            "value-of");
                    card.type(card.getByLabelText("Select"), "cells");
                    play.waitFor(() -> play.expect(card.getByRole("button", StroomDom.button("[index()]")))
                            .toBeVisible());
                    play.click(card.getByRole("button", StroomDom.button("[index()]")));
                    play.waitFor(() -> play.expect(card.getByLabelText("Select")).toHaveValue("cells[index()]"));
                })
                // The offer to declare follows what the Name holds however it got there: a
                // paste with the pointer types nothing (design 47 §7.16)
                .story("ANamePastedIsOfferedToo", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeVisible());
                    ShapeshifterEditorStories.newCard(play, screen, "variable");
                    final Play card = ShapeshifterEditorStories.cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
                    play.fireEvent().input(card.getByLabelText("Name"), "pasted_name");
                    play.waitFor(() -> play.expect(card.getByRole("button",
                            StroomDom.button("declare pasted_name here"))).toBeVisible());
                })
                // A variable naming what nothing declares offers to declare it, as a scalar on
                // the template; one press, and the card is still open on the name (design 47 I)
                .story("AVariableOffersToDeclareItsName", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toBeVisible());
                    ShapeshifterEditorStories.newCard(play, screen, "variable");
                    final Play card = ShapeshifterEditorStories.cardEditor(play);
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toBeVisible());
                    // Nothing to offer before there is a name
                    play.expect(card.queryByRole("button", TextMatch.containing("declare"))).toBeNull();
                    card.type(card.getByLabelText("Name"), "body_text");
                    play.waitFor(() -> play.expect(card.getByRole("button", StroomDom.button("declare body_text here")))
                            .toBeVisible());
                    play.click(card.getByRole("button", StroomDom.button("declare body_text here")));
                    play.waitFor(() -> play.expect(play.getByRole("button", StroomDom.button("body_text")))
                            .toBeVisible());
                    // Declared, there is nothing left to offer, and the card is where it was
                    play.expect(card.queryByRole("button", TextMatch.containing("declare"))).toBeNull();
                    play.waitFor(() -> play.expect(card.getByLabelText("Name")).toHaveFocus());
                    play.expect(card.getByLabelText("Name")).toHaveValue("body_text");
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
                })
                // A title and a card left open together are both taken. The card is taken
                // first, and the title used to be dropped without a word for finding the
                // template changed by it (design 47 §7.8, finding 1)
                .story("LeavingTakesTheTitleAndTheCardTogether",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByRole("button", ShapeshifterEditorStories.EDIT_TITLE))
                            .toBeVisible());
                    play.click(play.getByRole("button", ShapeshifterEditorStories.EDIT_TITLE));
                    play.waitFor(() -> play.expect(play.getByLabelText("Template name")).toHaveFocus());
                    play.keyboard("pairs");
                    final Play kept = open(play, "value-of", 0);
                    play.clear(kept.getByLabelText("Select"));
                    kept.type(kept.getByLabelText("Select"), "$7");
                    ShapeshifterEditorStories.selectTemplate(play, "junk_line");
                    play.waitFor(() -> play.expect(play.getByText("pairs", ShapeshifterEditorStories.TEMPLATE_NAME))
                            .toBeInTheDocument());
                    ShapeshifterEditorStories.selectTemplate(play, "pairs");
                    play.waitFor(() -> play.expect(play.getByText("$7", SUMMARY)).toBeInTheDocument());
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
                    // End is the last row. The add link under the rows is not one of them: the
                    // arrows do not reach it, and Tab does
                    play.keyboard("{End}");
                    play.expect(line(play, "text", 2)).toHaveFocus();
                    play.keyboard("{ArrowDown}");
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
                            Arrays.asList("text", "value-of", "text", "text")));
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
                // row, and not from buttons on every row: add, remove, up and down, as the
                // template panel has them. With no row selected only Add is offered, and it adds
                // at the end; it follows the row it moves; and a row just added is the selected
                // one once the author has finished adding
                .story("TheToolbarActsOnTheSelectedRow",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play))
                            .toBeInTheDocument());
                    play.expect(play.getByRole("button", UP)).toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByRole("button", REMOVE)).toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByRole("button", ADD)).not().toHaveAttribute("aria-disabled", "true");
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
                            Arrays.asList("value-of", "text", "text", "value-of", "text")));
                    play.waitFor(() -> play.expect(play.getByRole("button", UP))
                            .toHaveAttribute("aria-disabled", "true"));
                    play.click(play.getByRole("button", DOWN));
                    play.waitFor(() -> play.expect(play.querySelectorAll(
                            ShapeshifterEditorStories.CARD_KIND).textContents()).toEqual(
                            Arrays.asList("text", "value-of", "text", "value-of", "text")));
                    // A line typed into the card the add link opens. While the card that opened
                    // after it is open no row is the selected one: there is nothing to remove
                    // or to move, and Add is still there
                    Walkthrough.enter(play, "tail");
                    play.expect(play.getByRole("button", UP)).toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByRole("button", REMOVE)).toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByRole("button", ADD)).not().toHaveAttribute("aria-disabled", "true");
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
                            .toEqual(5);
                    // With no row selected, Add is the end of the body: the add link's own place
                    ShapeshifterEditorStories.selectTemplate(play, "junk_line");
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByRole("button", REMOVE))
                            .toHaveAttribute("aria-disabled", "true"));
                    play.click(play.getByRole("button", ADD));
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    play.expect(play.queryByTitle(ShapeshifterEditorStories.ADD_LINK)).toBeNull();
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(ShapeshifterEditorStories.addLink(play)).toHaveFocus());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A row dropped on a list's + instruction lands at the end of that list (design
                // 18 §5.6; design 47 §7.2, finding 17): the pair's opening text moved last
                .story("ADropOnTheAddLinkLandsAtTheEnd", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getAllByText("text", ShapeshifterEditorStories.CARD_KIND)
                            .first()).toBeInTheDocument());
                    final Query first = play.querySelector(".body-card__card");
                    final Query end = play.querySelector(".body-card__add");
                    play.run("drag the first row onto + instruction", () ->
                            dragAndDrop(first.element().get(), end.element().get()));
                    play.waitFor(() -> play.expect(play.getAllByText(TextMatch.regex("^(text|value-of)$"),
                            ShapeshifterEditorStories.CARD_KIND).first()).toHaveTextContent("value-of"));
                    play.expect(play.getAllByText("text", ShapeshifterEditorStories.CARD_KIND).count()).toEqual(3);
                    play.expect(play.getAllByText(TextMatch.regex("^(text|value-of)$"),
                            ShapeshifterEditorStories.CARD_KIND).nth(4)).toHaveTextContent("text");
                    play.expect(play.getByRole("button", "Save")).not().toHaveAttribute("aria-disabled");
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
                    play.expect(play.queryByTitle(ShapeshifterEditorStories.ADD_LINK)).toBeNull();
                    // and there is no toolbar over the rows
                    play.expect(play.queryByRole("button", ADD)).toBeNull();
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

    // A drag from one element dropped on another, as the browser fires it: the events carry one
    // DataTransfer between them and the point is the target's centre. Synthetic, because a
    // story's user events have no drag and drop of their own.
    private static native void dragAndDrop(Element source, Element target) /*-{
        var view = source.ownerDocument.defaultView;
        var transfer = new view.DataTransfer();
        var box = target.getBoundingClientRect();
        var fire = function (element, type) {
            element.dispatchEvent(new view.DragEvent(type, {
                bubbles: true, cancelable: true, dataTransfer: transfer,
                clientX: box.left + box.width / 2, clientY: box.top + box.height / 2
            }));
        };
        fire(source, "dragstart");
        fire(target, "dragenter");
        fire(target, "dragover");
        fire(target, "drop");
        fire(source, "dragend");
    }-*/;

    // The pair template selected and one of its cards opened, as a Play scoped to the open card.
    private static Play open(final Play play, final String kind, final int n) {
        ShapeshifterEditorStories.selectTemplate(play, "pair");
        play.waitFor(() -> play.expect(play.getAllByText(kind, ShapeshifterEditorStories.CARD_KIND)
                .first()).toBeInTheDocument());
        play.click(line(play, kind, n));
        return ShapeshifterEditorStories.cardEditor(play);
    }
}
