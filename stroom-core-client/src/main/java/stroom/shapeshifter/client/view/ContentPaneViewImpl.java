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
import stroom.shapeshifter.client.presenter.ContentPanePresenter.Span;
import stroom.shapeshifter.client.presenter.ContentPaneUiHandlers;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
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
 * The content as one HTML block: text escaped, each child match a span carrying its frame id
 * and its template's colour as a CSS variable, so the stylesheet decides how a match looks. A
 * click anywhere finds the innermost span above it and descends. Nesting is not drawn — only
 * the cursor's own children are marked — so the spans never overlap and can be emitted in
 * order.
 */
public class ContentPaneViewImpl extends ViewWithUiHandlers<ContentPaneUiHandlers> implements ContentPaneView {

    private static final String FRAME_ATTR = "data-frame";

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
                final String id = at.getAttribute(FRAME_ATTR);
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
    public void showContent(final String text, final List<Span> spans, final List<Loose> looseMatches,
                            final String noteText) {
        final SafeHtmlBuilder html = new SafeHtmlBuilder();
        int at = 0;
        for (final Span span : spans) {
            final int start = Math.max(at, Math.min(span.getStart(), text.length()));
            final int end = Math.max(start, Math.min(span.getEnd(), text.length()));
            if (span.getStart() < at) {
                // Overlaps the previous span: nested content is not drawn at this level.
                continue;
            }
            html.appendEscaped(text.substring(at, start));
            if (span.getFrameId() < 0) {
                html.appendHtmlConstant("<span class=\"ss-gap\" title=\""
                        + SafeHtmlUtils.htmlEscape(span.getTitle()) + "\"></span>");
            } else {
                html.appendHtmlConstant("<span class=\"ss-m\" " + FRAME_ATTR + "=\"" + span.getFrameId()
                        + "\" style=\"--hue:" + Colours.safe(span.getColour()) + "\" title=\""
                        + SafeHtmlUtils.htmlEscape(span.getTitle()) + "\">");
                html.appendEscaped(text.substring(start, end));
                html.appendHtmlConstant("</span>");
            }
            at = end;
        }
        html.appendEscaped(text.substring(at));
        content.setHTML(html.toSafeHtml());
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
