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

    // The add line a card's own actions open among the cards, by the name it gives a screen
    // reader: the list's own line at its end is Walkthrough.ENTRY.
    private static final String INSERT = "Insert an instruction here: text, or $1 for a capture";

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
                            Arrays.asList("text", "value-of", "element", "value-of", "text")));
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
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY))
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
                    play.run("note the add line and the runs so far", () -> {
                        before[0] = play.getByLabelText(Walkthrough.ENTRY).element().get();
                        runs[0] = requests.getCalls().size();
                    });
                    play.waitFor(() -> play.expect("a run since the card was opened",
                            () -> requests.getCalls().size() > runs[0]).toEqual(true));
                    play.waitFor(() -> play.expect("the cards were drawn again",
                            () -> play.getByLabelText(Walkthrough.ENTRY).element().get()
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
                // A card's actions open an add line before it or after it. It takes lines until
                // Escape, each going in where the last left off
                .story("InsertBeforeACard", context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    // The second row selected — the first value-of — and a line asked for above it
                    open(play, "value-of", 0);
                    play.click(play.getByRole("button", ABOVE));
                    play.waitFor(() -> play.expect(play.getByLabelText(INSERT)).toHaveFocus());
                    play.keyboard("A{Enter}");
                    play.waitFor(() -> play.expect(play.querySelectorAll(
                            ShapeshifterEditorStories.CARD_KIND).textContents()).toEqual(
                            Arrays.asList("text", "text", "value-of", "text", "value-of", "text")));
                    // The line has stayed, after what it put in, with the keyboard
                    play.waitFor(() -> play.expect(play.getByLabelText(INSERT)).toHaveFocus());
                    play.keyboard("$3{Enter}");
                    play.waitFor(() -> play.expect(play.querySelectorAll(SUMMARY).textContents())
                            .toSatisfy("A, then $3, then the $1 that was second", summaries ->
                                    String.valueOf(summaries).contains("\"A\", $3, $1")));
                    play.waitFor(() -> play.expect(play.getByLabelText(INSERT)).toHaveFocus());
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(play.queryByLabelText(INSERT)).toBeNull());
                    // Below the last row — the seventh, now — is where the list's own line
                    // already is
                    play.click(line(play, "text", 3));
                    play.click(play.getByRole("button", "Insert an instruction below the selected one"));
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY)).toHaveFocus());
                    play.expect(play.queryByLabelText(INSERT)).toBeNull();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A new card of another kind, started from the line among the cards, is a draft
                // where it will go. Clicking a card below it takes the draft — which moves that
                // card one further on — and it is that card which opens, not the one that has
                // slid into its old place
                .story("ANewCardDoesNotSendTheNextClickAstray",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    open(play, "value-of", 0);
                    play.click(play.getByRole("button", ABOVE));
                    play.waitFor(() -> play.expect(play.getByLabelText(INSERT)).toHaveFocus());
                    // The inserted line's own kind: the first of the two on screen
                    play.click(play.getAllByRole("button", ShapeshifterEditorStories.ADD_MENU).first());
                    play.click(screen.findByText("element", StroomDom.MENU_ITEM_TEXT));
                    final Play draft = ShapeshifterEditorStories.cardEditor(play);
                    // The line it was started from has given way to it
                    play.expect(play.queryByLabelText(INSERT)).toBeNull();
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
                    play.keyboard("{End}");
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
                // row, and not from buttons on every row. With no row selected it offers nothing;
                // it follows the row it moves; and a row just added is the selected one
                .story("TheToolbarActsOnTheSelectedRow",
                        context -> ShapeshifterEditorStories.render(context, false))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByLabelText(Walkthrough.ENTRY))
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
                            Arrays.asList("value-of", "text", "text", "value-of", "text")));
                    play.waitFor(() -> play.expect(play.getByRole("button", UP))
                            .toHaveAttribute("aria-disabled", "true"));
                    play.click(play.getByRole("button", DOWN));
                    play.waitFor(() -> play.expect(play.querySelectorAll(
                            ShapeshifterEditorStories.CARD_KIND).textContents()).toEqual(
                            Arrays.asList("text", "value-of", "text", "value-of", "text")));
                    // A row typed into the add line is the selected one, though the keyboard
                    // stays in the line: Up moves it, not the row that was selected before
                    Walkthrough.enter(play, "tail");
                    play.click(play.getByRole("button", UP));
                    play.waitFor(() -> play.expect(play.querySelectorAll(SUMMARY).textContents())
                            .toSatisfy("tail above the closing tag", summaries ->
                                    String.valueOf(summaries).contains("\"tail\", \"</pair>")));
                    // Remove takes the selected row
                    play.click(play.getByRole("button", REMOVE));
                    play.waitFor(() -> play.expect(play.queryByText("\"tail\"", SUMMARY)).toBeNull());
                    play.expect(play.querySelectorAll(ShapeshifterEditorStories.CARD_KIND).count())
                            .toEqual(5);
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
                    play.expect(play.queryByRole("button", ShapeshifterEditorStories.ADD_MENU)).toBeNull();
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
