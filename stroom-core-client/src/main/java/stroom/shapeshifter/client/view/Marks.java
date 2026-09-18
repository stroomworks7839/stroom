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

import stroom.shapeshifter.client.presenter.Mark;

import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Text with its marks as HTML: the text escaped, each mark a span carrying its kind as a class,
 * its frame as an attribute and its colour as a CSS variable, so the stylesheet decides how a
 * mark looks and a click handler finds the frame. Marks come outer first and are emitted as a
 * tree; one that straddles the end of the mark it is inside is clipped there.
 */
final class Marks {

    static final String FRAME_ATTR = "data-frame";

    private Marks() {
    }

    static SafeHtml render(final String text, final List<Mark> marks) {
        final SafeHtmlBuilder html = new SafeHtmlBuilder();
        final Deque<Integer> open = new ArrayDeque<>();
        int at = 0;
        for (final Mark mark : marks) {
            // Never behind the text emitted: marks come in start order, and a mark is clipped
            // to the one it is inside, so nothing closes past the next mark's start.
            final int start = Math.max(at, Math.min(mark.getStart(), text.length()));
            while (!open.isEmpty() && open.peek() <= start) {
                final int end = open.pop();
                html.appendEscaped(text.substring(at, end));
                html.appendHtmlConstant("</span>");
                at = end;
            }
            html.appendEscaped(text.substring(at, start));
            at = start;
            final int limit = open.isEmpty()
                    ? text.length()
                    : open.peek();
            final int end = Math.max(start, Math.min(mark.getEnd(), limit));
            html.appendHtmlConstant(openTag(mark));
            if (end == start) {
                html.appendHtmlConstant("</span>");
            } else {
                open.push(end);
            }
        }
        while (!open.isEmpty()) {
            final int end = open.pop();
            html.appendEscaped(text.substring(at, end));
            html.appendHtmlConstant("</span>");
            at = end;
        }
        html.appendEscaped(text.substring(at));
        return html.toSafeHtml();
    }

    private static String openTag(final Mark mark) {
        final StringBuilder tag = new StringBuilder("<span class=\"");
        switch (mark.getKind()) {
            case MATCH -> tag.append("ss-m");
            case CAPTURE -> tag.append("ss-cap");
            case GAP -> tag.append("ss-gap");
            case OWN -> tag.append("ss-o-own");
            case DIM -> tag.append("ss-o-dim");
        }
        tag.append('"');
        if (mark.getKind() == Mark.Kind.MATCH) {
            tag.append(' ').append(FRAME_ATTR).append("=\"").append(mark.getFrameId()).append('"');
        }
        if (mark.getColour() != null) {
            tag.append(" style=\"--hue:").append(Colours.safe(mark.getColour())).append('"');
        }
        if (mark.getTitle() != null) {
            tag.append(" title=\"").append(SafeHtmlUtils.htmlEscape(mark.getTitle())).append('"');
        }
        return tag.append('>').toString();
    }
}
