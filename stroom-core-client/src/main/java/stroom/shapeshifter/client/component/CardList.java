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

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;

import java.util.ArrayList;
import java.util.List;

/// A card list at a list path — the body, or a branch — with its *+ instruction* line.
public class CardList extends Composite {

    private final List<BodyCard> cards = new ArrayList<>();

    /// Notes are per card by position; a nested list has none (the run annotates top-level cards).
    public CardList(final int[] listPath, final List<OutputNode> cards, final List<CardNote> notes,
                    final Body.CardActions handlers, final boolean enabled, final boolean nested) {
        final FlowPanel panel = new FlowPanel();
        panel.setStyleName(nested
                ? "card-list card-list--nested"
                : "card-list");
        for (int i = 0; i < cards.size(); i++) {
            final BodyCard card = new BodyCard(listPath, i, cards.get(i), cards.size(), i < notes.size()
                    ? notes.get(i)
                    : null, handlers, enabled, !nested);
            this.cards.add(card);
            panel.add(card);
        }
        if (enabled) {
            final Label add = new Label("+ instruction");
            add.setStyleName("add-button card-list__add-line");
            add.setTitle("Add an instruction at the end");
            final int at = cards.size();
            add.addClickHandler((ClickEvent e) -> handlers.onAdd(Bodies.path(listPath), at, e.getClientX(),
                    e.getClientY()));
            panel.add(add);
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
