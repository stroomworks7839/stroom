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

import stroom.shapeshifter.client.presenter.Hot;
import stroom.shapeshifter.client.presenter.Mark;
import stroom.shapeshifter.client.presenter.OutputPanePresenter.OutputPaneView;
import stroom.shapeshifter.client.presenter.OutputUiHandlers;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.ScrollPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.List;

/**
 * The output as one preformatted block ({@link Marks}): the cursor's own extent is the
 * enclosing mark, the children's are marks inside it in their colours, and what is outside the
 * cursor's is marked dim, so the eye lands on what this frame wrote.
 */
public class OutputPaneViewImpl extends ViewWithUiHandlers<OutputUiHandlers> implements OutputPaneView {

    private final Widget widget;

    @UiField
    Label note;
    @UiField
    ScrollPanel body;
    @UiField
    HTML output;
    @UiField
    Label empty;

    @Inject
    public OutputPaneViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        output.getElement().setTabIndex(-1);
        output.addClickHandler(event -> {
            final EventTarget target = event.getNativeEvent().getEventTarget();
            if (Element.is(target)) {
                final long frame = Marks.frameOf(output.getElement(), Element.as(target));
                if (frame >= 0) {
                    getUiHandlers().onDescend(frame);
                }
            }
        });
        output.addMouseOverHandler(event -> {
            final EventTarget target = event.getNativeEvent().getEventTarget();
            getUiHandlers().onHover(Element.is(target)
                    ? Marks.hotOf(output.getElement(), Element.as(target))
                    : null);
        });
        output.addMouseOutHandler(event -> {
            // Leaving one span for another inside the block is not leaving the block.
            final EventTarget to = event.getRelatedTarget();
            if (to == null || !Element.is(to) || !output.getElement().isOrHasChild(Element.as(to))) {
                getUiHandlers().onHover(null);
            }
        });
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void showEmpty(final String text) {
        empty.setText(text);
        empty.setVisible(true);
        output.setVisible(false);
        note.setVisible(false);
    }

    @Override
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

    @Override
    public void setHot(final Hot hot) {
        Marks.light(output.getElement(), hot);
    }

    public interface Binder extends UiBinder<Widget, OutputPaneViewImpl> {

    }
}
