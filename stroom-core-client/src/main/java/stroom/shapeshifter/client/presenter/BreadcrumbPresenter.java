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

import stroom.shapeshifter.client.presenter.BreadcrumbPresenter.BreadcrumbView;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;
import stroom.svg.client.Preset;
import stroom.svg.client.SvgPresets;
import stroom.widget.button.client.ButtonView;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The breadcrumb (design 18 §5.1): the cursor's ancestry from the document down, each segment a
 * click back up; at its end a stepper through the other matches of the cursor's template across
 * the whole input; and the run's controls — a sample to paste, Run — with its state (stale,
 * running). Before a run it is the front door: it says how a run arrives.
 */
public class BreadcrumbPresenter extends MyPresenterWidget<BreadcrumbView> implements BreadcrumbUiHandlers {

    private final ButtonView sampleButton;
    private final ButtonView runButton;
    private ProjectHost host;
    private Runnable onSample;

    @Inject
    public BreadcrumbPresenter(final EventBus eventBus, final BreadcrumbView view) {
        super(eventBus, view);
        view.setUiHandlers(this);
        sampleButton = view.addButton(SvgPresets.EDIT.title("Paste a sample to run over"));
        runButton = view.addButton(SvgPresets.RUN.title("Run the project over the sample"));
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(sampleButton.addClickHandler(event -> {
            if (onSample != null) {
                onSample.run();
            }
        }));
        registerHandler(runButton.addClickHandler(event -> host.run()));
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        refresh();
    }

    /** What the sample button does: opens the input pane's sample editor. */
    public void setOnSample(final Runnable onSample) {
        this.onSample = onSample;
    }

    public void refresh() {
        final TraceModel trace = host == null
                ? null
                : host.trace();
        runButton.setEnabled(host != null && host.getSample() != null && host.getProject() != null);
        sampleButton.setEnabled(host != null && host.getProject() != null);
        if (trace == null) {
            getView().setSegments(List.of());
            getView().setStepper(null, 0, 0);
            getView().setState(host != null && host.isStale()
                    ? "running…"
                    : host != null && host.getSample() != null
                            ? "The last run failed — Run to try again."
                            : "No run yet — paste a sample and run, or step a record through a pipeline.");
            return;
        }
        final long cursor = host.cursor();
        final List<Segment> segments = new ArrayList<>();
        for (final long id : trace.path(cursor)) {
            final Frame frame = trace.frame(id);
            segments.add(new Segment(id, trace.label(id), frame == null
                    ? null
                    : host.colour(frame.getTemplateId()), siblingIndex(trace, frame), siblingCount(trace, frame)));
        }
        getView().setSegments(segments);
        final Frame frame = trace.frame(cursor);
        if (frame == null) {
            getView().setStepper(null, 0, 0);
        } else {
            final List<Frame> matches = trace.matches(frame.getTemplateId());
            getView().setStepper(frame.getTemplateName(), matches.indexOf(frame) + 1, matches.size());
        }
        getView().setState(host.isStale()
                ? "stale — the definition has moved on; running…"
                : trace.isCompiled()
                        ? null
                        : "the project did not compile — see the messages");
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

    @Override
    public void onSegment(final long frameId) {
        host.setCursor(frameId);
    }

    /** Step within the parent: the cursor's previous or next sibling. */
    @Override
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

    /** Step across the whole input: the previous or next match of the cursor's template. */
    @Override
    public void onStep(final int delta) {
        final TraceModel trace = host.trace();
        final Frame frame = trace == null
                ? null
                : trace.frame(host.cursor());
        if (frame == null) {
            return;
        }
        final List<Frame> matches = trace.matches(frame.getTemplateId());
        final int at = matches.indexOf(frame) + delta;
        if (at >= 0 && at < matches.size()) {
            host.setCursor(matches.get(at).getId());
        }
    }

    /** One segment of the crumb: a frame, its label, its template's colour, its place among its siblings. */
    public static final class Segment {

        private final long frameId;
        private final String label;
        private final String colour;
        private final int index;
        private final int count;

        public Segment(final long frameId, final String label, final String colour, final int index,
                       final int count) {
            this.frameId = frameId;
            this.label = label;
            this.colour = colour;
            this.index = index;
            this.count = count;
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

        /** One-based among the parent's children; 0 for the document. */
        public int getIndex() {
            return index;
        }

        public int getCount() {
            return count;
        }
    }

    public interface BreadcrumbView extends View, HasUiHandlers<BreadcrumbUiHandlers> {

        ButtonView addButton(Preset preset);

        void setSegments(List<Segment> segments);

        /** The whole-input stepper: the template's name and the cursor's place among its matches; null hides it. */
        void setStepper(String templateName, int index, int count);

        /** A note at the crumb's end — the empty state, stale, running — or null for none. */
        void setState(String text);
    }
}
