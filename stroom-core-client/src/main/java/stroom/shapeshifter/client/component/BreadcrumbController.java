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
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.TraceModel;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// The breadcrumb (design 18 §5.1): the cursor's ancestry from the document down, each segment a
/// click back up; at its end a stepper through the other matches of the cursor's template across
/// the whole input; and the run's controls — a sample to paste, Run — with its state (stale,
/// running). Before a run it is the front door: it says how a run arrives.
public class BreadcrumbController extends Controller {

    private Breadcrumb view;

    private ProjectHost host;
    private String templateId;

    @Inject
    public BreadcrumbController(final EventBus eventBus) {
        super(eventBus);
    }

    /// Attaches this to a crumb, e.g. one a template declares. The crumb is then this
    /// controller's widget.
    ///
    /// @param view The crumb.
    /// @return This, for its owner to keep.
    public BreadcrumbController attach(final Breadcrumb view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnSegment(this::onSegment);
        view.setOnSibling(this::onSibling);
        view.setOnStep(this::onStep);
        view.setOnStepTo(this::onStepTo);
        view.setOnHover(this::onHover);
        return this;
    }


    public void setHost(final ProjectHost host) {
        this.host = host;
        refresh();
    }

    /// The template the panel has selected: what the whole-input stepper steps through while
    /// the cursor is the document and so is in no match of its own (design 18 §5.3).
    public void setTemplate(final String templateId) {
        this.templateId = templateId;
        refresh();
    }

    public void refresh() {
        final TraceModel trace = host == null
                ? null
                : host.trace();

        if (trace == null) {
            view.setSegments(List.of());
            view.setStepper(null, 0, 0);
            view.setState(host != null && host.isStale()
                    ? "running…"
                    : host != null && host.getSampleSource() != null
                            ? "The last run failed — Run to try again."
                            : "No run yet — choose the sample data in the panel.");
            return;
        }
        final long cursor = host.cursor();
        final List<Segment> segments = new ArrayList<>();
        for (final long id : trace.path(cursor)) {
            final Frame frame = trace.frame(id);
            segments.add(new Segment(id, frame == null
                    ? null
                    : frame.getTemplateId(), trace.label(id), frame == null
                    ? null
                    : host.colour(frame.getTemplateId()), siblingIndex(trace, frame), siblingCount(trace, frame)));
        }
        view.setSegments(segments);
        final Frame frame = trace.frame(cursor);
        final Stepping stepping = steppable(trace, frame);
        if (stepping.frames.isEmpty()) {
            // Shown all the same, with its buttons dead: a control that comes and goes reads as
            // one that is not there, and a run that matched nothing is worth saying plainly.
            view.setStepper(stepping.label, 0, 0);
        } else {
            // Where the cursor is in no match of its own - the document - the stepper reads
            // 0 of n and its forward arrow is the way in to the first, which is what stepping
            // the matches means before any has been chosen.
            view.setStepper(stepping.label, frame == null
                    ? 0
                    : stepping.frames.indexOf(frame) + 1, stepping.frames.size());
        }
        view.setState(host.isStale()
                ? "stale — the definition has moved on; running…"
                : !trace.isSampleRead()
                        ? "the sample could not be read — see the messages"
                        : !trace.isCompiled()
                                ? "the project did not compile — see the messages"
                                : trace.matchedNothing()
                                        ? "ran, and nothing matched — no template applied to the document"
                                        : null);
    }

    /// What the stepper steps (design 18 §5.3's whole-input scope): the matches of the cursor's
    /// own template, or - the cursor being the document - of the template the panel has selected.
    /// Where that template matched nothing, and where nothing is selected, there is still
    /// something worth stepping and it is the matches themselves: the document's children, in the
    /// order they were found, whatever template each is of. So a run with any match at all has a
    /// stepper, which is the point of it being the one always-visible stepping control.
    private Stepping steppable(final TraceModel trace, final Frame frame) {
        final String stepping = frame != null
                ? frame.getTemplateId()
                : templateId;
        if (stepping != null) {
            final List<Frame> matches = trace.matches(stepping);
            if (!matches.isEmpty()) {
                return new Stepping("matches of " + matches.get(0).getTemplateName(), matches);
            }
        }
        return frame == null
                ? new Stepping("matches", trace.children(TraceModel.ROOT))
                : new Stepping("matches", List.of());
    }

    /// What the stepper steps, and what it is called: the two cannot disagree.
    private static final class Stepping {

        private final String label;
        private final List<Frame> frames;

        private Stepping(final String label, final List<Frame> frames) {
            this.label = label;
            this.frames = frames;
        }
    }

    private static int siblingIndex(final TraceModel trace, final Frame frame) {
        return frame == null
                ? 0
                : trace.children(frame.getParentId()).indexOf(frame) + 1;
    }

    private static int siblingCount(final TraceModel trace, final Frame frame) {
        return frame == null
                ? 0
                : trace.children(frame.getParentId()).size();
    }

    public void onSegment(final long frameId) {
        host.setCursor(frameId);
    }

    public void onHover(final Hot hot) {
        host.hover(hot);
    }

    /// A frame or a capture names its frame: the segment that is it lights (an outer-scope row points here).
    public void setHot(final Hot hot) {
        final boolean ownCapture = hot != null && hot.getKind() == Hot.Kind.CAPTURE
                                   && hot.getFrameId() == host.cursor();
        final boolean lights = hot != null && !ownCapture
                               && (hot.getKind() == Hot.Kind.FRAME || hot.getKind() == Hot.Kind.CAPTURE);
        view.setHot(lights
                ? hot.getFrameId()
                : -1);
    }

    /// Step within the parent: the cursor's previous or next sibling.
    public void onSibling(final long frameId, final int delta) {
        final TraceModel trace = host.trace();
        final Frame frame = trace == null
                ? null
                : trace.frame(frameId);
        if (frame == null) {
            return;
        }
        final List<Frame> siblings = trace.children(frame.getParentId());
        final int at = siblings.indexOf(frame) + delta;
        if (at >= 0 && at < siblings.size()) {
            host.setCursor(siblings.get(at).getId());
        }
    }

    /// Step across the whole input: the previous or next match of the cursor's template.
    public void onStep(final int delta) {
        final TraceModel trace = host.trace();
        if (trace == null) {
            return;
        }
        final Frame frame = trace.frame(host.cursor());
        final List<Frame> matches = steppable(trace, frame).frames;
        // From the document, forward is the first match; there is nothing before it.
        final int at = frame == null
                ? delta - 1
                : matches.indexOf(frame) + delta;
        if (at >= 0 && at < matches.size()) {
            host.setCursor(matches.get(at).getId());
        }
    }

    public void onStepTo(final int which) {
        final TraceModel trace = host.trace();
        if (trace == null) {
            return;
        }
        final List<Frame> matches = steppable(trace, trace.frame(host.cursor())).frames;
        if (!matches.isEmpty()) {
            host.setCursor((which == 0
                    ? matches.get(0)
                    : matches.get(matches.size() - 1)).getId());
        }
    }

    /// One segment of the crumb: a frame, its label, its template's colour, its place among its siblings.
    public static final class Segment {

        private final long frameId;
        private final String templateId;
        private final String label;
        private final String colour;
        private final int index;
        private final int count;

        public Segment(final long frameId, final String templateId, final String label, final String colour,
                       final int index, final int count) {
            this.frameId = frameId;
            this.templateId = templateId;
            this.label = label;
            this.colour = colour;
            this.index = index;
            this.count = count;
        }

        public long getFrameId() {
            return frameId;
        }

        /// The frame's template; null for the document.
        public String getTemplateId() {
            return templateId;
        }

        public String getLabel() {
            return label;
        }

        public String getColour() {
            return colour;
        }

        /// One-based among the parent's children; 0 for the document.
        public int getIndex() {
            return index;
        }

        public int getCount() {
            return count;
        }
    }

}
