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
import stroom.shapeshifter.config.OutputNode;
import stroom.widget.text.client.Label;
import stroom.widget.util.client.CssProperties;

import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.List;

/// A card list at a list path — the body, or a branch — ending with the New row that adds to it.
public class CardList extends Composite {

    private final List<BodyCard> cards = new ArrayList<>();

    /// Notes are per card by position; a nested list has none (the run annotates top-level cards).
    public CardList(final int[] listPath, final List<OutputNode> cards, final List<CardNote> notes,
                    final Body.CardActions handlers, final boolean enabled, final boolean nested) {
        final FlowPanel panel = new FlowPanel();
        // How deep the list is, for its rows to indent by: they are flush to the pane's edges,
        // so that a row's hover and selection run the pane's width, and it is what is in them
        // that steps in (body-card.css). A list path is an index and a branch per level.
        CssProperties.set(panel.getElement(), "--body-card__depth", String.valueOf(listPath.length / 2));
        final String list = Bodies.path(listPath);
        for (int i = 0; i < cards.size(); i++) {
            // A new card asked for among the cards (the toolbar's insert above or below) is
            // open where it will go, before the card it will come before.
            final Widget editor = enabled
                    ? handlers.newEditorAt(list, i)
                    : null;
            if (editor != null) {
                panel.add(editor);
            }
            final BodyCard card = new BodyCard(listPath, i, cards.get(i), cards.size(), i < notes.size()
                    ? notes.get(i)
                    : null, handlers, enabled, !nested);
            this.cards.add(card);
            panel.add(card);
        }
        if (enabled) {
            // The list ends with its New row, which is where a card is added. A new card's
            // editor takes its place while one is open there.
            final Widget editor = handlers.newEditorAt(list, cards.size());
            panel.add(editor != null
                    ? editor
                    : BodyCard.newRow(listPath, cards.size(), handlers));
        } else if (cards.isEmpty()) {
            final Label none = new Label("(no instructions)");
            none.setStyleName("placeholder");
            panel.add(none);
        }
        initWidget(panel);
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
