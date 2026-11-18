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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.client.model.Bodies;
import stroom.shapeshifter.client.model.CardNote;
import stroom.shapeshifter.client.model.Instructions;
import stroom.shapeshifter.config.OutputNode;
import stroom.widget.button.client.LinkButton;
import stroom.widget.form.client.TextBox;
import stroom.widget.text.client.Label;
import stroom.widget.util.client.CssProperties;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.List;

/// A card list at a list path — the body, or a branch — and the line that adds to it.
public class CardList extends Composite {

    private final List<BodyCard> cards = new ArrayList<>();

    /// Notes are per card by position; a nested list has none (the run annotates top-level cards).
    public CardList(final int[] listPath, final List<OutputNode> cards, final List<CardNote> notes,
                    final Body.CardActions handlers, final boolean enabled, final boolean nested) {
        final FlowPanel panel = new FlowPanel();
        panel.setStyleName("card-list");
        // How deep the list is, for its rows to indent by: they are flush to the pane's edges,
        // so that a row's hover and selection run the pane's width, and it is what is in them
        // that steps in (card-list.css). A list path is an index and a branch per level.
        CssProperties.set(panel.getElement(), "--card-list__depth", String.valueOf(listPath.length / 2));
        final String list = Bodies.path(listPath);
        for (int i = 0; i < cards.size(); i++) {
            if (enabled) {
                addAt(panel, list, i, handlers);
            }
            final BodyCard card = new BodyCard(listPath, i, cards.get(i), cards.size(), i < notes.size()
                    ? notes.get(i)
                    : null, handlers, enabled, !nested);
            this.cards.add(card);
            panel.add(card);
        }
        if (enabled) {
            // The list's own add line is where a new card of any kind starts, so the editor of
            // one that starts there takes its place until it is taken or dropped.
            final Widget editor = handlers.newEditorAt(list, cards.size());
            if (editor != null) {
                panel.add(editor);
            } else {
                panel.add(addLine(list, cards.size(), handlers, false));
            }
        } else if (cards.isEmpty()) {
            final Label none = new Label("(no instructions)");
            none.setStyleName("placeholder");
            panel.add(none);
        }
        initWidget(panel);
    }

    // What is open before a card, if anything: the editor of a new card that will go there, or
    // the add line a card's own actions open (design 47 D). The editor stands in for the line
    // it was started from, so there is never both.
    private static void addAt(final FlowPanel panel, final String list, final int index,
                              final Body.CardActions handlers) {
        final Widget editor = handlers.newEditorAt(list, index);
        if (editor != null) {
            panel.add(editor);
        } else if (handlers.insertingAt(list, index)) {
            panel.add(addLine(list, index, handlers, true));
        }
    }

    /// Where a card is added: the kind a new card is to be, and a field that takes a line.
    ///
    /// **The field** (design 47 A, ruled 2026-11-17) adds the cards a line describes. A card
    /// used to cost four actions — the add line, a kind from the menu, one value in a dialog,
    /// OK — and across the three rungs design 46 walked, 22 of the 23 cards added were `text`
    /// or `value-of`, the two kinds whose whole form is that one value. Here they cost a line
    /// and Enter. What a line means is [Instructions#cards(String)]'s to say, not this view's.
    ///
    /// **The kind** (design 47 D, ruled 2026-11-18) stands where a card's own kind stands and
    /// reads `new`, because the line has no kind until Enter decides it. Its menu makes the
    /// line any other kind's editor, in place.
    ///
    /// @param inserting Whether this is the line a card's actions opened among the cards,
    ///                  rather than the list's own at its end: Escape dismisses that one.
    private static FlowPanel addLine(final String listPath, final int at, final Body.CardActions handlers,
                                     final boolean inserting) {
        final FlowPanel line = new FlowPanel();
        line.setStyleName(inserting
                ? "card-list__add card-list__add--inserting"
                : "card-list__add");

        // A button rather than a label, so the keyboard reaches it.
        final LinkButton kind = new LinkButton("new");
        kind.addStyleName("card-list__add-kind");
        kind.setTitle("New instruction of any kind");
        kind.setAriaLabel("New instruction of any kind");
        kind.addClickHandler((ClickEvent e) -> {
            // Opened from the keyboard a click has no position, so the menu opens under the
            // button instead of at the top corner of the window.
            final boolean placed = e.getClientX() != 0 || e.getClientY() != 0;
            handlers.onAdd(listPath, at,
                    placed
                            ? e.getClientX()
                            : kind.getAbsoluteLeft(),
                    placed
                            ? e.getClientY()
                            : kind.getAbsoluteTop() + kind.getOffsetHeight());
        });
        line.add(kind);

        final TextBox entry = new TextBox();
        entry.addStyleName("card-list__entry");
        entry.setPlaceholder("text, or $1 for a capture — Enter adds it");
        entry.setAriaLabel(inserting
                ? "Insert an instruction here: text, or $1 for a capture"
                : "Add an instruction: text, or $1 for a capture");
        final boolean[] focused = {false};
        entry.addFocusHandler(event -> focused[0] = true);
        entry.addBlurHandler(event -> focused[0] = false);
        entry.addKeyDownHandler(event -> {
            if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
                event.preventDefault();
                final String typed = entry.getText();
                if (!typed.isEmpty()) {
                    // Emptied before the edit is made, not after: the edit redraws the list, and
                    // the redraw carries whatever the field still holds into its replacement.
                    entry.setText("");
                    handlers.onEntry(listPath, at, typed);
                }
            } else if (event.getNativeKeyCode() == KeyCodes.KEY_ESCAPE) {
                entry.setText("");
                if (inserting) {
                    handlers.onInsertClosed();
                }
            }
        });
        line.add(entry);
        final String key = inserting
                ? Body.INSERT_KEY
                : listPath;
        handlers.onEntryShown(key, new Body.Entry() {
            @Override
            public String getText() {
                return entry.getText();
            }

            @Override
            public void setText(final String text) {
                entry.setText(text);
            }

            @Override
            public boolean isFocused() {
                return focused[0];
            }

            @Override
            public void focus() {
                entry.setFocus(true);
            }
        });
        return line;
    }

    /// Light the top-level card at an index, or none for -1.
    public void setHot(final int index) {
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).setHot(i == index);
        }
    }

    /// Bring a card into view, if it is not there already (design 18 §5.13.3).
    ///
    /// @param index The card's position in this list.
    public void reveal(final int index) {
        if (index >= 0 && index < cards.size()) {
            cards.get(index).getElement().scrollIntoView();
        }
    }
}
