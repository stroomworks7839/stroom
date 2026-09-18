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

import stroom.shapeshifter.client.presenter.ContentPanePresenter.ContentPaneView;
import stroom.shapeshifter.client.presenter.ContentPanePresenter.Loose;
import stroom.shapeshifter.client.presenter.ContentPaneUiHandlers;
import stroom.shapeshifter.client.presenter.Mark;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.List;

/**
 * The content as one HTML block ({@link Marks}); a click anywhere finds the innermost match
 * span above it and descends.
 */
public class ContentPaneViewImpl extends ViewWithUiHandlers<ContentPaneUiHandlers> implements ContentPaneView {

    private final Widget widget;

    @UiField
    FlowPanel editor;
    @UiField
    TextArea sample;
    @UiField
    Button run;
    @UiField
    Button cancel;
    @UiField
    FlowPanel reader;
    @UiField
    Label note;
    @UiField
    HTML content;
    @UiField
    FlowPanel loose;
    @UiField
    Label empty;

    @Inject
    public ContentPaneViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        run.addClickHandler(event -> getUiHandlers().onRun(sample.getValue()));
        cancel.addClickHandler(event -> getUiHandlers().onCancel());
        sample.addKeyDownHandler(event -> {
            if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER && event.isControlKeyDown()) {
                event.preventDefault();
                getUiHandlers().onRun(sample.getValue());
            }
        });
        content.addClickHandler(event -> {
            final EventTarget target = event.getNativeEvent().getEventTarget();
            if (!Element.is(target)) {
                return;
            }
            Element at = Element.as(target);
            while (at != null && at != content.getElement()) {
                final String id = at.getAttribute(Marks.FRAME_ATTR);
                if (id != null && !id.isEmpty()) {
                    getUiHandlers().onDescend(Long.parseLong(id));
                    return;
                }
                at = at.getParentElement();
            }
        });
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void showEditor(final String text, final boolean cancellable) {
        sample.setValue(text);
        cancel.setVisible(cancellable);
        show(editor);
        sample.setFocus(true);
    }

    @Override
    public void showEmpty(final String text) {
        empty.setText(text);
        show(empty);
    }

    @Override
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
            row.addDomHandler(event -> getUiHandlers().onDescend(match.getFrameId()), ClickEvent.getType());
            loose.add(row);
        }
        loose.setVisible(!looseMatches.isEmpty());
        note.setText(noteText == null
                ? ""
                : noteText);
        note.setVisible(noteText != null);
        show(reader);
    }

    private void show(final Widget which) {
        editor.setVisible(which == editor);
        reader.setVisible(which == reader);
        empty.setVisible(which == empty);
    }

    public interface Binder extends UiBinder<Widget, ContentPaneViewImpl> {

    }
}
