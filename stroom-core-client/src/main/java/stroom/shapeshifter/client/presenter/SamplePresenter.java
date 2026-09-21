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

import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.presenter.SamplePresenter.SampleView;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.shared.ShapeshifterMessage;
import stroom.shapeshifter.shared.ShapeshifterPreviewRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.shapeshifter.shared.ShapeshifterTrace;
import stroom.shapeshifter.shared.ShapeshifterTrace.Attempt;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;
import stroom.shapeshifter.shared.ShapeshifterTrace.Group;
import stroom.util.client.DelayedUpdate;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The workbench's sample (design 44 §2, design 18 §5.6): text to try the match against, seeded
 * from the cursor's frame and freely edited, with every match and every group of it painted
 * beneath as the engine found them. The sample is the author's experiment, not the document's
 * run: it goes to {@code preview} as a project of one template — the subject's match, its
 * encoding and consumption, nothing else — and the trace that comes back is read for that
 * template's frames, their groups and the places it was tried. A group picked in the pattern
 * map, or a labelled node in the tree, isolates its spans.
 */
public class SamplePresenter
        extends MyPresenterWidget<SampleView>
        implements SampleUiHandlers {

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final RestFactory restFactory;
    private final DelayedUpdate rerun;

    private ProjectHost host;
    private String subjectKey;
    private Template experiment;
    private String colour;
    private String seed;
    private String requested;
    private boolean running;
    private boolean runAgain;
    private ShapeshifterTrace trace;
    private int isolatedGroup;
    private String isolatedLabel;

    @Inject
    public SamplePresenter(final EventBus eventBus, final SampleView view, final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.rerun = new DelayedUpdate(400, this::run);
        view.setUiHandlers(this);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    /** Shown for a template, and again after every edit of it: the match may have changed. */
    public void setTemplate(final String id) {
        subject(id, host.template(id), host.colour(id));
    }

    /**
     * A part of the library as the subject (design 44 §3): tried as a template whose match is a
     * ref to it, with the library beside, so its labels are the groups.
     */
    public void setPattern(final String name) {
        final String key = Patterns.rowId(name);
        if (!key.equals(subjectKey)) {
            experiment = Templates.withMatch(Templates.create(name, null, true),
                    new MatchExpression.Pattern(new PatternNode.Ref(name)));
        }
        subject(key, experiment, Templates.colour(0));
    }

    private void subject(final String key, final Template template, final String colour) {
        if (!Objects.equals(key, subjectKey)) {
            subjectKey = key;
            isolatedGroup = 0;
            isolatedLabel = null;
            // A new subject gets the cursor's frame as its sample unless the author has typed
            // one; what was typed is the experiment, and follows the author to the next subject.
            final String current = getView().getSample();
            if (current.isEmpty() || current.equals(seed)) {
                seed = seedText();
                getView().setSample(seed);
            }
        }
        experiment = template;
        this.colour = colour;
        run();
    }

    /** The cursor's frame's content, the document's sample, or nothing. */
    private String seedText() {
        final TraceModel trace = host.trace();
        if (trace != null && trace.has(host.cursor())) {
            final String content = trace.content(host.cursor());
            if (content != null && !content.isEmpty()) {
                return content;
            }
        }
        return host.getSample() == null
                ? ""
                : host.getSample();
    }

    @Override
    public void onSample() {
        rerun.update();
    }

    /** Isolate a group by number (0 for none): the regex map's click. */
    public void isolate(final int group) {
        isolatedGroup = group;
        isolatedLabel = null;
        paint();
    }

    /** Isolate a group by its label (null for none): the tree's selection. */
    public void isolate(final String label) {
        isolatedLabel = label;
        isolatedGroup = 0;
        paint();
    }

    private void run() {
        final Template template = experiment;
        if (template == null) {
            trace = null;
            requested = null;
            getView().showNothing("");
            return;
        }
        final MatchExpression match = template.match();
        if (!(match instanceof MatchExpression.Regex) && !(match instanceof MatchExpression.Pattern)
            && !(match instanceof MatchExpression.Parts) && !(match instanceof MatchExpression.Delimiter)) {
            trace = null;
            requested = null;
            getView().showNothing("A " + Templates.kind(match) + " match has no pattern to try against a sample.");
            return;
        }
        final String sample = getView().getSample();
        final String project = ProjectText.print(Templates.experiment(host.getProject(), template));
        final String key = project + '\u0000' + sample;
        if (running) {
            // One request in flight; the edit that arrived meanwhile runs when this one lands.
            runAgain = true;
            return;
        }
        if (key.equals(requested)) {
            // Already tried: the trace held is this text's and this match's.
            paint();
            return;
        }
        requested = key;
        running = true;
        restFactory
                .create(RESOURCE)
                .method(res -> res.preview(new ShapeshifterPreviewRequest(project, sample)))
                .onSuccess(result -> {
                    running = false;
                    trace = result;
                    if (runAgain) {
                        // Something moved meanwhile - the text, or the subject: the follow-up
                        // paints, so this answer is never shown under the wrong template.
                        runAgain = false;
                        run();
                    } else {
                        paint();
                    }
                })
                .onFailure(error -> {
                    running = false;
                    requested = null;
                    trace = null;
                    if (runAgain) {
                        runAgain = false;
                        run();
                    } else {
                        getView().showNothing("The sample could not be tried: " + error.getMessage());
                    }
                })
                .taskMonitorFactory(this)
                .exec();
    }

    /** The sample as the trace read it: matches, their groups, and where the template was tried. */
    private void paint() {
        if (trace == null) {
            return;
        }
        if (!trace.isCompiled()) {
            final StringBuilder why = new StringBuilder();
            for (final ShapeshifterMessage message : trace.getMessages()) {
                why.append(why.length() == 0
                        ? ""
                        : "; ").append(message.getText());
            }
            getView().showNothing("The match does not compile: " + why);
            return;
        }
        final String text = trace.getInput();
        final List<Mark> marks = new ArrayList<>();
        final List<SampleView.Row> rows = new ArrayList<>();
        int tried = 0;
        for (final Attempt attempt : trace.getAttempts()) {
            if (attempt.getParentFrameId() == TraceModel.ROOT) {
                tried++;
                if (!attempt.isMatched() && attempt.getContentOffset() >= 0) {
                    marks.add(new Mark(Mark.Kind.GAP, -1, -1, null, attempt.getContentOffset(),
                            attempt.getContentOffset(), null, "Tried here; no match"));
                }
            }
        }
        int matches = 0;
        for (final Frame frame : trace.getFrames()) {
            if (frame.getParentId() != TraceModel.ROOT
                || frame.getContentOffset() == ShapeshifterTrace.NOT_A_SLICE) {
                continue;
            }
            matches++;
            final int from = frame.getContentOffset();
            marks.add(new Mark(Mark.Kind.MATCH, frame.getId(), -1, frame.getTemplateId(), from,
                    from + frame.getContentLength(), colour, "Match " + frame.getMatchIndex()));
            final List<SampleView.Cell> cells = new ArrayList<>();
            for (final Group group : groupsOf(frame.getId())) {
                final boolean placed = group.getContentOffset() != ShapeshifterTrace.NOT_A_SLICE;
                final int start = from + group.getContentOffset();
                final String value = placed
                        ? text.substring(Math.min(start, text.length()),
                        Math.min(start + group.getContentLength(), text.length()))
                        : null;
                if (group.getIndex() > 0) {
                    cells.add(new SampleView.Cell(group.getIndex(), group.getName(), value,
                            RegexPresenter.hue(group.getIndex()), isolated(group)));
                }
                if (placed && group.getIndex() > 0 && (nothingIsolated() || isolated(group))) {
                    marks.add(new Mark(Mark.Kind.CAPTURE, frame.getId(), group.getIndex(), null, start,
                            start + group.getContentLength(), RegexPresenter.hue(group.getIndex()),
                            group.getName() == null
                                    ? "$" + group.getIndex()
                                    : group.getName()));
                }
            }
            rows.add(new SampleView.Row(frame.getMatchIndex(), from, frame.getContentLength(), cells));
        }
        marks.sort(Mark.OUTER_FIRST);
        getView().showMatches(text, marks, rows, matches == 0
                ? "no matches · tried at " + tried + (tried == 1
                        ? " place"
                        : " places")
                : matches + (matches == 1
                        ? " match"
                        : " matches") + " · tried at " + tried + (tried == 1
                        ? " place"
                        : " places"));
    }

    private List<Group> groupsOf(final long frameId) {
        final List<Group> out = new ArrayList<>();
        for (final Group group : trace.getGroups()) {
            if (group.getFrameId() == frameId) {
                out.add(group);
            }
        }
        return out;
    }

    private boolean nothingIsolated() {
        return isolatedGroup == 0 && isolatedLabel == null;
    }

    private boolean isolated(final Group group) {
        return isolatedGroup != 0
                ? group.getIndex() == isolatedGroup
                : isolatedLabel != null && isolatedLabel.equals(group.getName());
    }

    public interface SampleView extends View, HasUiHandlers<SampleUiHandlers> {

        /** One match: which, where, and its groups. */
        record Row(int index, int offset, int length, List<Cell> cells) {

        }

        /** One group of a match: its number, name, value (null where it took no part) and hue. */
        record Cell(int group, String name, String value, String hue, boolean isolated) {

        }

        String getSample();

        void setSample(String text);

        /** The sample painted with its matches and groups, the matches tabled, and a summary line. */
        void showMatches(String text, List<Mark> marks, List<Row> rows, String summary);

        /** Nothing to paint, and why. */
        void showNothing(String why);
    }
}
