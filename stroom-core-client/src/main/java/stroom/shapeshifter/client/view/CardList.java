/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client.view;

import stroom.shapeshifter.client.presenter.Bodies;
import stroom.shapeshifter.client.presenter.BodyUiHandlers;
import stroom.shapeshifter.config.OutputNode;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;

import java.util.List;

/** A card list at a list path — the body, or a branch — with its <i>+ instruction</i> line. */
public class CardList extends Composite {

    public CardList(final int[] listPath, final List<OutputNode> cards, final BodyUiHandlers handlers,
                    final boolean enabled, final boolean nested) {
        final FlowPanel panel = new FlowPanel();
        panel.setStyleName(nested
                ? "ss-cards ss-cards--nested"
                : "ss-cards");
        for (int i = 0; i < cards.size(); i++) {
            panel.add(new BodyCard(listPath, i, cards.get(i), cards.size(), handlers, enabled));
        }
        if (enabled) {
            final Label add = new Label("+ instruction");
            add.setStyleName("ss-addbtn ss-addline");
            add.setTitle("Add an instruction at the end");
            final int at = cards.size();
            add.addClickHandler((ClickEvent e) -> handlers.onAdd(Bodies.path(listPath), at, e.getClientX(),
                    e.getClientY()));
            panel.add(add);
        } else if (cards.isEmpty()) {
            final Label none = new Label("(no instructions)");
            none.setStyleName("ss-none");
            panel.add(none);
        }
        initWidget(panel);
    }
}
