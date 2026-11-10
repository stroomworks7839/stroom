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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.client.model.Hot;
import stroom.shapeshifter.client.model.Mark;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.Html;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.ScrollPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.Consumer;

/**
 * The output as one preformatted block ({@link Marks}): the cursor's own extent is the
 * enclosing mark, the children's are marks inside it in their colours, and what is outside the
 * cursor's is marked dim, so the eye lands on what this frame wrote.
 */
public class OutputPane extends Component {

    private Consumer<Long> onDescend = frameId -> {
    };
    private Consumer<Hot> onHover = hot -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    Label note;
    @UiField
    ScrollPanel body;
    @UiField
    Html output;
    @UiField
    Label empty;

    public OutputPane() {
        initWidget(BINDER.createAndBindUi(this));
        output.getElement().setTabIndex(-1);
        output.addClickHandler(event -> {
            final EventTarget target = event.getNativeEvent().getEventTarget();
            if (Element.is(target)) {
                final long frame = Marks.frameOf(output.getElement(), Element.as(target));
                if (frame >= 0) {
                    onDescend.accept(frame);
                }
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
        output.setVisible(true);
        empty.setVisible(false);
        note.setText(noteText == null
                ? ""
                : noteText);
        note.setVisible(noteText != null);
        // What this frame wrote is the whole point of the pane, so it is what the pane shows.
        Marks.reveal(body, output.getElement(), "ss-o-own");
    }

    public void setHot(final Hot hot) {
        Marks.light(output.getElement(), hot);
    }


    /// @param onDescend Told when descend.
    public void setOnDescend(final Consumer<Long> onDescend) {
        this.onDescend = onDescend;
    }

    /// @param onHover Told when hover.
    public void setOnHover(final Consumer<Hot> onHover) {
        this.onHover = onHover;
    }

    interface Binder extends UiBinder<Widget, OutputPane> {

    }
}
