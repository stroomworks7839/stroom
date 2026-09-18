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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.client.presenter.ContentPanePresenter.ContentPaneView;
import stroom.shapeshifter.shared.ShapeshifterTrace.Attempt;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * The content pane (design 18 §5.1, §5.3): the cursor frame's content, with the matches its
 * body dispatched marked in their templates' colours; a click descends into the match under
 * it. Content a match holds that is not a slice of this frame's — a value from a variable — is
 * listed under the text, since it has no place in it.
 *
 * <p>Before a run, and whenever asked for, the pane is the sample's editor (design 18 Q2's first
 * door): a box to paste into and a Run.
 */
public class ContentPanePresenter extends MyPresenterWidget<ContentPaneView> implements ContentPaneUiHandlers {

    /** More than this and the browser would be laying out spans for minutes; the sample is for looking at. */
    private static final int RENDER_CAP = 200_000;

    private ProjectHost host;
    private boolean editing;

    @Inject
    public ContentPanePresenter(final EventBus eventBus, final ContentPaneView view) {
        super(eventBus, view);
        view.setUiHandlers(this);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        refresh();
    }

    /** Show the sample editor, with the sample as it stands. */
    public void editSample() {
        editing = true;
        final String sample = host == null
                ? null
                : host.getSample();
        getView().showEditor(sample == null
                ? ""
                : sample, sample != null);
    }

    @Override
    public void onRun(final String sample) {
        editing = false;
        host.setSample(sample);
        refresh();
    }

    @Override
    public void onCancel() {
        editing = false;
        refresh();
    }

    @Override
    public void onDescend(final long frameId) {
        host.setCursor(frameId);
    }

    public void refresh() {
        if (host == null || editing) {
            return;
        }
        final TraceModel trace = host.trace();
        if (host.getSample() == null) {
            editing = true;
            getView().showEditor("", false);
            return;
        }
        if (trace == null) {
            getView().showEmpty(host.isStale()
                    ? "running…"
                    : "The last run failed — Run to try again.");
            return;
        }
        final long cursor = host.cursor();
        final String content = trace.content(cursor);
        final List<Span> spans = new ArrayList<>();
        final List<Loose> loose = new ArrayList<>();
        for (final Frame child : trace.children(cursor)) {
            final String colour = host.colour(child.getTemplateId());
            final String title = child.getTemplateName() + " #" + child.getMatchIndex() + " — click to descend";
            if (child.getContentOffset() < 0) {
                loose.add(new Loose(child.getId(), trace.label(child.getId()), colour, trace.content(child.getId())));
            } else {
                spans.add(new Span(child.getId(), child.getContentOffset(),
                        child.getContentOffset() + child.getContentLength(), colour, title));
            }
        }
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
            spans.add(new Span(-1, at, at, null, "no template matched here"));
        }
        spans.sort((a, b) -> a.getStart() != b.getStart()
                ? Integer.compare(a.getStart(), b.getStart())
                : Integer.compare(b.getEnd(), a.getEnd()));
        getView().showContent(content.length() > RENDER_CAP
                ? content.substring(0, RENDER_CAP)
                : content, spans, loose, content.length() > RENDER_CAP
                ? "showing the first " + RENDER_CAP + " of " + content.length() + " characters"
                : null);
    }

    /** A marked run of the content: a child match, or (frame id -1, zero length) a failed attempt's place. */
    public static final class Span {

        private final long frameId;
        private final int start;
        private final int end;
        private final String colour;
        private final String title;

        public Span(final long frameId, final int start, final int end, final String colour, final String title) {
            this.frameId = frameId;
            this.start = start;
            this.end = end;
            this.colour = colour;
            this.title = title;
        }

        public long getFrameId() {
            return frameId;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public String getColour() {
            return colour;
        }

        public String getTitle() {
            return title;
        }
    }

    /** A child match whose content is not a slice of this frame's. */
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

    public interface ContentPaneView extends View, HasUiHandlers<ContentPaneUiHandlers> {

        /** The sample editor; with a sample to go back to, a Cancel. */
        void showEditor(String sample, boolean cancellable);

        void showEmpty(String text);

        /** The content with its spans (sorted by start, outer first), the loose matches, and a note or null. */
        void showContent(String content, List<Span> spans, List<Loose> loose, String note);
    }
}
