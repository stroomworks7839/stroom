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

import stroom.shapeshifter.client.presenter.ContentPanePresenter.Span;
import stroom.shapeshifter.client.presenter.OutputPanePresenter.OutputPaneView;
import stroom.shapeshifter.client.presenter.OutputUiHandlers;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.List;

/**
 * The output as one preformatted block. The cursor's own span is one enclosing mark; the
 * children's are marks inside it, in their colours; the rest of the output is dimmed, so the
 * eye lands on what this frame wrote.
 */
public class OutputPaneViewImpl extends ViewWithUiHandlers<OutputUiHandlers> implements OutputPaneView {

    private static final String FRAME_ATTR = "data-frame";

    private final Widget widget;

    @UiField
    Label note;
    @UiField
    HTML output;
    @UiField
    Label empty;

    @Inject
    public OutputPaneViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        output.addClickHandler(event -> {
            final EventTarget target = event.getNativeEvent().getEventTarget();
            if (!Element.is(target)) {
                return;
            }
            Element at = Element.as(target);
            while (at != null && at != output.getElement()) {
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
    public void showEmpty(final String text) {
        empty.setText(text);
        empty.setVisible(true);
        output.setVisible(false);
        note.setVisible(false);
    }

    @Override
    public void showOutput(final String text, final List<Span> spans, final String noteText) {
        final SafeHtmlBuilder html = new SafeHtmlBuilder();
        Span own = null;
        for (final Span span : spans) {
            if (span.getFrameId() < 0) {
                own = span;
            }
        }
        final int ownStart = own == null
                ? 0
                : Math.min(own.getStart(), text.length());
        final int ownEnd = own == null
                ? text.length()
                : Math.min(Math.max(ownStart, own.getEnd()), text.length());
        html.appendHtmlConstant("<span class=\"ss-o-dim\">");
        html.appendEscaped(text.substring(0, ownStart));
        html.appendHtmlConstant("</span><span class=\"ss-o-own\">");
        int at = ownStart;
        for (final Span span : spans) {
            if (span.getFrameId() < 0) {
                continue;
            }
            final int start = Math.max(at, Math.min(span.getStart(), ownEnd));
            final int end = Math.max(start, Math.min(span.getEnd(), ownEnd));
            if (span.getStart() < at) {
                continue;
            }
            html.appendEscaped(text.substring(at, start));
            html.appendHtmlConstant("<span class=\"ss-o\" " + FRAME_ATTR + "=\"" + span.getFrameId()
                    + "\" style=\"--hue:" + Colours.safe(span.getColour()) + "\" title=\""
                    + SafeHtmlUtils.htmlEscape(span.getTitle()) + "\">");
            html.appendEscaped(text.substring(start, end));
            html.appendHtmlConstant("</span>");
            at = end;
        }
        html.appendEscaped(text.substring(at, ownEnd));
        html.appendHtmlConstant("</span><span class=\"ss-o-dim\">");
        html.appendEscaped(text.substring(ownEnd));
        html.appendHtmlConstant("</span>");
        output.setHTML(html.toSafeHtml());
        output.setVisible(true);
        empty.setVisible(false);
        note.setText(noteText == null
                ? ""
                : noteText);
        note.setVisible(noteText != null);
    }

    public interface Binder extends UiBinder<Widget, OutputPaneViewImpl> {

    }
}
