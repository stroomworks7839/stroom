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
import stroom.shapeshifter.client.model.Mark.Kind;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.TraceModel;
import stroom.shapeshifter.shared.ShapeshifterMessage;
import stroom.shapeshifter.shared.ShapeshifterTrace.Attempt;
import stroom.shapeshifter.shared.ShapeshifterTrace.Capture;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

/// The content pane (design 18 §5.1, §5.3, §5.11): the cursor frame's content, with the matches
/// its body dispatched marked in their templates' colours — a click descends into the match
/// under it — and the captures tinted in their own colours so that an IP or a timestamp is
/// visibly a matched piece of text rather than a run of characters. Content a match holds that
/// is not a slice of this frame's — a value from a variable — is listed under the text, since it
/// has no place in it.
///
/// Two channels, each answering one question (design 18 §5.11, ruled 2026-11-16): **the colour
/// says "this is a capture", the rule says "this one is yours"**. Neither says how deep, which
/// is what three attempts at drawing depth — weight, then texture, then tiers — each failed to
/// do without taking the other channel's meaning with it.
///
/// Without data the pane is an empty state pointing at the one door there is (design 18 §5.7,
/// design 44 §5): the crumb's sample chooser, a stream or text pasted. The pane itself never
/// edits the sample - it shows the cursor's content and nothing else.
public class ContentPaneController extends Controller {

    private ContentPane view;

    /// More than this and the browser would be laying out spans for minutes; the sample is for looking at.
    private static final int RENDER_CAP = 200_000;

    private ProjectHost host;

    @Inject
    public ContentPaneController(final EventBus eventBus) {
        super(eventBus);
    }

    /// Attaches this to a pane, e.g. one a template declares. The pane is then this
    /// controller's widget.
    ///
    /// @param view The pane.
    /// @return This, for its owner to keep.
    public ContentPaneController attach(final ContentPane view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnDescend(this::onDescend);
        view.setOnHover(this::onHover);
        return this;
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        refresh();
    }

    public void onDescend(final long frameId) {
        host.setCursor(frameId);
    }

    public void onHover(final Hot hot) {
        host.hover(hot);
    }

    public void setHot(final Hot hot) {
        view.setHot(hot);
    }

    public void refresh() {
        if (host == null) {
            return;
        }
        final TraceModel trace = host.trace();
        if (host.getSampleSource() == null) {
            view.showEmpty("No data yet — choose a sample on the crumb above: a stream, "
                                + "or text pasted. Nothing is kept with the document.");
            return;
        }
        if (trace == null) {
            view.showEmpty(host.isStale()
                    ? "running…"
                    : "The last run failed — Run on the crumb to try again.");
            return;
        }
        final long cursor = host.cursor();
        final String content = trace.content(cursor);
        final List<Mark> marks = new ArrayList<>();
        final List<Loose> loose = new ArrayList<>();
        for (final Frame child : trace.children(cursor)) {
            final String colour = host.colour(child.getTemplateId());
            final int worst = trace.worstBelow(child.getId());
            final String title = child.getTemplateName() + " #" + child.getMatchIndex() + " — click to descend"
                                 + (worst == 0
                    ? ""
                    : " · " + messageNote(trace, child.getId()));
            if (child.getContentOffset() < 0) {
                loose.add(new Loose(child.getId(), trace.label(child.getId()), colour, trace.content(child.getId())));
            } else {
                marks.add(new Mark(Kind.MATCH, child.getId(), -1, child.getTemplateId(), child.getContentOffset(),
                        child.getContentOffset() + child.getContentLength(), colour, title));
            }
        }
        marks.addAll(captureMarks(trace, cursor, CaptureHues.inScope(trace, cursor)));
        // Where the dispatch tried and nothing matched: the gaps, marked so an unmatched line is a
        // thing to see, not an absence to infer (design 18 §5.3). One mark per place, however
        // many templates were tried there, and none inside a match.
        final NavigableSet<Integer> tried = new TreeSet<>();
        for (final Attempt attempt : trace.attempts(cursor)) {
            if (!attempt.isMatched() && attempt.getContentOffset() >= 0) {
                tried.add(attempt.getContentOffset());
            }
        }
        for (final Frame child : trace.children(cursor)) {
            if (child.getContentOffset() >= 0) {
                tried.subSet(child.getContentOffset(), child.getContentOffset() + child.getContentLength()).clear();
            }
        }
        for (final int at : tried) {
            marks.add(new Mark(Kind.GAP, -1, -1, null, at, at, null, "no template matched here"));
        }
        marks.sort(Mark.OUTER_FIRST);
        view.showContent(content.length() > RENDER_CAP
                ? content.substring(0, RENDER_CAP)
                : content, marks, loose, content.length() > RENDER_CAP
                ? "showing the first " + RENDER_CAP + " of " + content.length() + " characters"
                : null);
    }

    /// What the messages under a frame say, for a title: the first one's text, and how many more.
    private static String messageNote(final TraceModel trace, final long frameId) {
        final List<ShapeshifterMessage> own = trace.messages(frameId);
        if (!own.isEmpty()) {
            return own.get(0).getText() + (own.size() > 1
                    ? " (+" + (own.size() - 1) + ")"
                    : "");
        }
        return "a message beneath — descend to see it";
    }

    /// The captures the pane tints, placed in the cursor's content (design 18 §5.11.1, §5.11.2,
    /// ruled 2026-11-16).
    ///
    /// **The tint identifies captures, and says nothing about depth.** A capture bound beneath
    /// the cursor is coloured exactly as one bound at it; what stops the pane filling with
    /// colour is a cap on how many generations contribute, not a fade applied to the deeper
    /// ones. Without the cap an IP address bound as a capture, with each octet bound by a
    /// template beneath it, would be coloured as an address and then immediately recoloured as
    /// four numbers — which tells the reader nothing the first colouring did not, and costs the
    /// first its meaning. [CaptureHues#reached] settles which frames are in reach.
    ///
    /// **The rule says which captures are the current level's**, so only the cursor's own are
    /// ruled. That is the one place the two channels are allowed to disagree over a run of text,
    /// and the disagreement is the information: tinted but not ruled means *a capture, but not
    /// one of yours*.
    ///
    /// A frame's offset in the cursor's content is its parent's plus its own; a frame whose
    /// content is no slice ends the descent, since nothing under it has a place here.
    ///
    /// @param trace The run.
    /// @param cursor The selected frame.
    /// @param hues The colour of each capture in scope, shared with the variables pane.
    /// @return The marks to draw.
    private static List<Mark> captureMarks(final TraceModel trace, final long cursor, final CaptureHues hues) {
        final List<Mark> marks = new ArrayList<>();
        place(trace, cursor, cursor, 0, hues, marks);
        return marks;
    }

    private static void place(final TraceModel trace, final long cursor, final long frameId, final int base,
                              final CaptureHues hues, final List<Mark> marks) {
        if (hues.inReach(frameId)) {
            final List<Capture> captures = trace.captures(frameId);
            final Frame frame = trace.frame(frameId);
            for (int i = 0; i < captures.size(); i++) {
                final Capture capture = captures.get(i);
                if (capture.getContentOffset() >= 0) {
                    marks.add(new Mark(Kind.CAPTURE, frameId, i, null, base + capture.getContentOffset(),
                            base + capture.getContentOffset() + capture.getContentLength(),
                            hues.hue(frameId, i),
                            "$" + capture.getName() + " = " + shortValue(capture.getValue()) + (frame == null
                                    ? ""
                                    : " — " + frame.getTemplateName() + " #" + frame.getMatchIndex()),
                            frameId == cursor));
                }
            }
        }
        for (final Frame child : trace.children(frameId)) {
            if (child.getContentOffset() >= 0) {
                place(trace, cursor, child.getId(), base + child.getContentOffset(), hues, marks);
            }
        }
    }

    private static String shortValue(final String value) {
        return value == null
                ? "—"
                : value.length() > 80
                        ? value.substring(0, 77) + "…"
                        : value;
    }

    /// A child match whose content is not a slice of this frame's.
    public static final class Loose {

        private final long frameId;
        private final String label;
        private final String colour;
        private final String content;

        public Loose(final long frameId, final String label, final String colour, final String content) {
            this.frameId = frameId;
            this.label = label;
            this.colour = colour;
            this.content = content;
        }

        public long getFrameId() {
            return frameId;
        }

        public String getLabel() {
            return label;
        }

        public String getColour() {
            return colour;
        }

        public String getContent() {
            return content;
        }
    }

}
