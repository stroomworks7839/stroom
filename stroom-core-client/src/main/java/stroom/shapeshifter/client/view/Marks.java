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

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.ui.ScrollPanel;

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
    static final String TEMPLATE_ATTR = "data-tpl";
    static final String CAPTURE_ATTR = "data-cap";
    static final String INSTRUCTION_ATTR = "data-instr";
    static final String HOT_CLASS = "ss-hot";

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

    /**
     * What a marked element says it is of, for pointing: the request the element under the
     * pointer stands for, or null for plain text. Walks up to the block, since a capture sits
     * inside a match and the innermost is what is pointed at.
     */
    static Hot hotOf(final Element block, final Element target) {
        Element at = target;
        while (at != null && at != block) {
            final String capture = at.getAttribute(CAPTURE_ATTR);
            if (capture != null && !capture.isEmpty()) {
                return Hot.capture(Long.parseLong(capture.substring(0, capture.indexOf(':'))),
                        Integer.parseInt(capture.substring(capture.indexOf(':') + 1)));
            }
            final String instruction = at.getAttribute(INSTRUCTION_ATTR);
            if (instruction != null && !instruction.isEmpty()) {
                return Hot.instruction(Long.parseLong(instruction.substring(0, instruction.indexOf(':'))),
                        Integer.parseInt(instruction.substring(instruction.indexOf(':') + 1)));
            }
            final String frame = at.getAttribute(FRAME_ATTR);
            if (frame != null && !frame.isEmpty()) {
                final String template = at.getAttribute(TEMPLATE_ATTR);
                return Hot.frame(Long.parseLong(frame), template == null || template.isEmpty()
                        ? null
                        : template);
            }
            at = at.getParentElement();
        }
        return null;
    }

    /** The frame a click on a marked element descends to, or -1: the innermost match above it. */
    static long frameOf(final Element block, final Element target) {
        Element at = target;
        while (at != null && at != block) {
            final String frame = at.getAttribute(FRAME_ATTR);
            if (frame != null && !frame.isEmpty()) {
                return Long.parseLong(frame);
            }
            at = at.getParentElement();
        }
        return -1;
    }

    /**
     * Light the elements of a block a request names and unlight the rest: the attribute is the
     * request's kind, the value its key. A block of many spans is walked once per request,
     * which the pointer's pace can afford.
     */
    static void light(final Element block, final Hot hot) {
        final String attr = hot == null
                ? null
                : attrOf(hot.getKind());
        final NodeList<Element> spans = block.getElementsByTagName("span");
        for (int i = 0; i < spans.getLength(); i++) {
            final Element span = spans.getItem(i);
            final boolean lit = attr != null && hot.key().equals(span.getAttribute(attr));
            if (lit) {
                span.addClassName(HOT_CLASS);
            } else if (span.hasClassName(HOT_CLASS)) {
                span.removeClassName(HOT_CLASS);
            }
        }
    }

    /**
     * Bring the first mark of a class into view, if it is not there already.
     *
     * <p>Stepping moves the frame, not the pane, so the mark a step lands on can be anywhere in
     * an output long enough to scroll. This leaves a third of the pane above it, so it arrives
     * with the lines before it rather than flush against the top, and does nothing at all while
     * it is already on screen — a step within one screenful should not move the text under the
     * reader's eye. Instant rather than animated: a held-down step key outruns any easing.
     *
     * <p>Deferred because the HTML it measures was set in the same pass, and a span has no
     * position until the browser has laid it out.
     */
    static void reveal(final ScrollPanel pane, final Element block, final String className) {
        Scheduler.get().scheduleDeferred(() -> {
            final Element mark = firstOfClass(block, className);
            final int viewport = pane.getElement().getClientHeight();
            if (mark == null || viewport <= 0) {
                return;
            }
            final int shown = pane.getVerticalScrollPosition();
            final int top = mark.getAbsoluteTop() - pane.getElement().getAbsoluteTop() + shown;
            if (top >= shown && top + mark.getOffsetHeight() <= shown + viewport) {
                return;
            }
            pane.setVerticalScrollPosition(Math.max(0, top - viewport / 3));
        });
    }

    private static Element firstOfClass(final Element block, final String className) {
        final NodeList<Element> spans = block.getElementsByTagName("span");
        for (int i = 0; i < spans.getLength(); i++) {
            if (spans.getItem(i).hasClassName(className)) {
                return spans.getItem(i);
            }
        }
        return null;
    }

    private static String attrOf(final Hot.Kind kind) {
        switch (kind) {
            case FRAME:
                return FRAME_ATTR;
            case CAPTURE:
                return CAPTURE_ATTR;
            case INSTRUCTION:
                return INSTRUCTION_ATTR;
            default:
                return TEMPLATE_ATTR;
        }
    }

    private static String openTag(final Mark mark) {
        final StringBuilder tag = new StringBuilder("<span class=\"");
        switch (mark.getKind()) {
            case MATCH -> tag.append("ss-m");
            case CAPTURE -> tag.append("ss-cap");
            case GAP -> tag.append("ss-gap");
            case OWN -> tag.append("ss-o-own");
            case INSTRUCTION -> tag.append("ss-o-instr");
            case DIM -> tag.append("ss-o-dim");
        }
        if (mark.getFlag() != null) {
            tag.append(" ss-flag ss-flag--").append(mark.getFlag());
        }
        tag.append('"');
        switch (mark.getKind()) {
            case MATCH -> tag.append(' ').append(FRAME_ATTR).append("=\"").append(mark.getFrameId()).append('"')
                    .append(' ').append(TEMPLATE_ATTR).append("=\"")
                    .append(SafeHtmlUtils.htmlEscape(mark.getTemplateId() == null
                            ? ""
                            : mark.getTemplateId())).append('"');
            case CAPTURE -> tag.append(' ').append(CAPTURE_ATTR).append("=\"").append(mark.getFrameId()).append(':')
                    .append(mark.getIndex()).append('"');
            case INSTRUCTION -> tag.append(' ').append(INSTRUCTION_ATTR).append("=\"").append(mark.getFrameId())
                    .append(':').append(mark.getIndex()).append('"');
            default -> {
            }
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
