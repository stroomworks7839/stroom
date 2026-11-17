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

import stroom.shapeshifter.client.model.Hot;
import stroom.shapeshifter.client.model.Mark;
import stroom.widget.component.client.Component;
import stroom.widget.layout.client.ScrollPane;
import stroom.widget.text.client.Html;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.Consumer;

/// The output as one preformatted block ([Marks]): the cursor's own extent is the
/// enclosing mark, the children's are marks inside it in their colours, and what is outside the
/// cursor's is marked dim, so the eye lands on what this frame wrote.
public class OutputPane extends Component {

    private Consumer<Hot> onClick = hot -> {
    };
    private Consumer<Hot> onHover = hot -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    Label note;
    @UiField
    ScrollPane body;
    @UiField
    Html output;
    @UiField
    Label empty;

    public OutputPane() {
        initWidget(BINDER.createAndBindUi(this));
        output.getElement().setTabIndex(-1);
        // What was clicked, not where to descend to: two different things sit under a mark
        // here and they cannot mean the same thing (design 18 §5.13.3). A match descends; a
        // card of the cursor's own body reveals itself in the body panel, since there is no
        // frame beneath it to descend to. The pane does not know which is which — it does not
        // know the cursor — so it says what was clicked and the controller decides.
        output.addClickHandler(event -> {
            final EventTarget target = event.getNativeEvent().getEventTarget();
            if (Element.is(target)) {
                onClick.accept(Marks.hotOf(output.getElement(), Element.as(target)));
            }
        });
        output.addMouseOverHandler(event -> {
            final EventTarget target = event.getNativeEvent().getEventTarget();
            onHover.accept(Element.is(target)
                    ? Marks.hotOf(output.getElement(), Element.as(target))
                    : null);
        });
        output.addMouseOutHandler(event -> {
            // Leaving one span for another inside the block is not leaving the block.
            final EventTarget to = event.getRelatedTarget();
            if (to == null || !Element.is(to) || !output.getElement().isOrHasChild(Element.as(to))) {
                onHover.accept(null);
            }
        });
    }


    public void showEmpty(final String text) {
        empty.setText(text);
        empty.setVisible(true);
        output.setVisible(false);
        note.setVisible(false);
    }

    public void showOutput(final String text, final List<Mark> marks, final String noteText) {
        output.setHTML(Marks.render(text, marks));
        Marks.rest(output.getElement());
        output.setVisible(true);
        empty.setVisible(false);
        note.setText(noteText == null
                ? ""
                : noteText);
        note.setVisible(noteText != null);
        // What this frame wrote is the whole point of the pane, so it is what the pane shows.
        Marks.reveal(body, output.getElement(), "mark--own");
    }

    public void setHot(final Hot hot) {
        Marks.light(output.getElement(), hot);
    }


    /// @param onClick Told what was clicked, or null for unmarked text.
    public void setOnClick(final Consumer<Hot> onClick) {
        this.onClick = onClick;
    }

    /// @param onHover Told when hover.
    public void setOnHover(final Consumer<Hot> onHover) {
        this.onHover = onHover;
    }

    interface Binder extends UiBinder<Widget, OutputPane> {

    }
}
