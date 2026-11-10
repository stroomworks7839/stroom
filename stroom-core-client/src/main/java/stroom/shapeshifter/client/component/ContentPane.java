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

import stroom.shapeshifter.client.component.ContentPaneController.Loose;
import stroom.shapeshifter.client.model.Hot;
import stroom.shapeshifter.client.model.Mark;
import stroom.widget.button.client.Button;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextArea;
import stroom.widget.text.client.Html;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.Consumer;

/**
 * The content as one Html block ({@link Marks}); a click anywhere finds the innermost match
 * span above it and descends.
 */
public class ContentPane extends Component {

    private Consumer<Long> onDescend = frameId -> {
    };
    private Consumer<Hot> onHover = hot -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    FlowPanel reader;
    @UiField
    Label note;
    @UiField
    Html content;
    @UiField
    FlowPanel loose;
    @UiField
    Label empty;

    public ContentPane() {
        initWidget(BINDER.createAndBindUi(this));
        content.getElement().setTabIndex(-1);
        content.addClickHandler(event -> {
            final EventTarget target = event.getNativeEvent().getEventTarget();
            if (Element.is(target)) {
                final long frame = Marks.frameOf(content.getElement(), Element.as(target));
                if (frame >= 0) {
                    onDescend.accept(frame);
                }
            }
        });
        // The pane's own delegated handler, not :hover - a wrapped span loses :hover in the
        // leading between its lines (the mockup's delegateHover).
        content.addMouseOverHandler(event -> {
            final EventTarget target = event.getNativeEvent().getEventTarget();
            onHover.accept(Element.is(target)
                    ? Marks.hotOf(content.getElement(), Element.as(target))
                    : null);
        });
        content.addMouseOutHandler(event -> {
            // Leaving one span for another inside the block is not leaving the block.
            final EventTarget to = event.getRelatedTarget();
            if (to == null || !Element.is(to) || !content.getElement().isOrHasChild(Element.as(to))) {
                onHover.accept(null);
            }
        });
    }


    public void showEmpty(final String text) {
        empty.setText(text);
        show(empty);
    }

    public void showContent(final String text, final List<Mark> marks, final List<Loose> looseMatches,
                            final String noteText) {
        content.setHTML(Marks.render(text, marks));
        loose.clear();
        for (final Loose match : looseMatches) {
            final FlowPanel row = new FlowPanel();
            row.addStyleName("ss-loose");
            // A custom property cannot be set through Style; the attribute can carry it.
            row.getElement().setAttribute("style", "--hue:" + Colours.safe(match.getColour()));
            final Label label = new Label(match.getLabel() + " — from a variable:");
            label.addStyleName("ss-loose-label");
            final Label value = new Label(match.getContent());
            value.addStyleName("ss-loose-value mono");
            row.add(label);
            row.add(value);
            row.addDomHandler(event -> onDescend.accept(match.getFrameId()), ClickEvent.getType());
            loose.add(row);
        }
        loose.setVisible(!looseMatches.isEmpty());
        note.setText(noteText == null
                ? ""
                : noteText);
        note.setVisible(noteText != null);
        show(reader);
    }

    public void setHot(final Hot hot) {
        Marks.light(content.getElement(), hot);
    }

    private void show(final Widget which) {
        reader.setVisible(which == reader);
        empty.setVisible(which == empty);
    }


    /// @param onDescend Told when descend.
    public void setOnDescend(final Consumer<Long> onDescend) {
        this.onDescend = onDescend;
    }

    /// @param onHover Told when hover.
    public void setOnHover(final Consumer<Hot> onHover) {
        this.onHover = onHover;
    }

    interface Binder extends UiBinder<Widget, ContentPane> {

    }
}
