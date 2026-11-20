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

import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.model.Mark;
import stroom.shapeshifter.client.model.Patterns;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.ProjectText;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.client.model.TraceModel;
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
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/// The workbench's sample (design 44 §2, design 18 §5.6): text to try the match against, seeded
/// from the cursor's frame and freely edited, with every match and every group of it painted
/// beneath as the engine found them. The sample is the author's experiment, not the document's
/// run: it goes to `preview` as a project of one template — the subject's match, its
/// encoding and consumption, nothing else — and the trace that comes back is read for that
/// template's frames, their groups and the places it was tried. A group picked in the pattern
/// map, or a labelled node in the tree, isolates its spans.
public class SampleController extends Controller {

    private final Sample view;

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final RestFactory restFactory;
    private final DelayedUpdate rerun;

    private ProjectHost host;
    private String subjectKey;
    private Template experiment;
    private String colour;
    private String seed;
    // The charset the seed was shown in, and so the one the box's text is sent back in: a binary
    // record's text is its bytes a byte to a character (design 46 §6.5). Null is UTF-8.
    private String seedEncoding;
    // The charset of what seedText last chose
    private String chosenEncoding;
    private String requested;
    private boolean running;
    private boolean runAgain;
    private ShapeshifterTrace trace;
    private int isolatedGroup = RegexController.NO_GROUP;
    private String isolatedLabel;
    private int pointedGroup = RegexController.NO_GROUP;
    private IntConsumer onGroupPoint = group -> {
    };

    @Inject
    public SampleController(final EventBus eventBus, final Sample view, final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        view.setOnSample(this::onSample);
        view.setOnGroupPoint(group -> {
            pointGroup(group);
            onGroupPoint.accept(group);
        });
        this.restFactory = restFactory;
        this.rerun = new DelayedUpdate(400, this::run);
        onDispose(rerun::reset);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    /// Shown for a template, and again after every edit of it: the match may have changed.
    public void setTemplate(final String id) {
        subject(id, host.template(id), host.colour(id), id);
    }

    /// A part of the library as the subject (design 44 §3): tried as a template whose match is a
    /// ref to it, with the library beside, so its labels are the groups.
    public void setPattern(final String name) {
        final String key = Patterns.rowId(name);
        if (!key.equals(subjectKey)) {
            experiment = Templates.withMatch(Templates.create(name, null, false),
                    new MatchExpression.Pattern(new PatternNode.Ref(name)));
        }
        subject(key, experiment, Templates.colour(0), null);
    }

    private void subject(final String key, final Template template, final String colour, final String templateId) {
        final boolean changed = !Objects.equals(key, subjectKey);
        if (changed) {
            subjectKey = key;
            isolatedGroup = RegexController.NO_GROUP;
            isolatedLabel = null;
        }
        final String current = view.getSample();
        // A new subject is seeded from the run, and so is an empty box - the first run after the
        // workbench opened has data the opening did not. What the author typed is the
        // experiment: it is left alone, and follows them to the next subject.
        if (changed || current.isEmpty()) {
            if (current.isEmpty() || current.equals(seed)) {
                final String next = seedText(templateId);
                if (!next.equals(current)) {
                    seed = next;
                    seedEncoding = chosenEncoding;
                    view.setSample(next);
                }
            }
        }
        experiment = template;
        this.colour = colour;
        run();
    }

    /// What the subject is tried against: the content of a frame that dispatched it — the
    /// parent's, not what the subject itself matched, which is the answer rather than the
    /// question. The cursor's frame when the subject was tried there, else where it first
    /// matched, else where it was first tried at all (a template that never matches is exactly
    /// the one worth experimenting with); failing all of it, the cursor's content, then the
    /// document's sample.
    private String seedText(final String templateId) {
        final TraceModel trace = host.trace();
        if (trace != null && templateId != null) {
            for (final Attempt attempt : trace.attempts(host.cursor())) {
                if (attempt.getTemplateId().equals(templateId)) {
                    return contentOr(trace, host.cursor(), null);
                }
            }
            final List<Frame> matches = trace.matches(templateId);
            if (!matches.isEmpty()) {
                return contentOr(trace, matches.get(0).getParentId(), () -> fallback(trace));
            }
            final long tried = trace.firstTriedIn(templateId);
            if (tried >= 0) {
                return contentOr(trace, tried, () -> fallback(trace));
            }
        }
        return fallback(trace);
    }

    /// A frame's content, or what the supplier says when it has none.
    private String contentOr(final TraceModel trace, final long frameId, final Supplier<String> otherwise) {
        final String content = trace.has(frameId)
                ? trace.content(frameId)
                : null;
        if (content != null && !content.isEmpty()) {
            chosenEncoding = trace.encodingOf(frameId);
            return content;
        }
        return otherwise == null
                ? fallback(trace)
                : otherwise.get();
    }

    /// The cursor's content - the whole input at the document - then a pasted sample.
    private String fallback(final TraceModel trace) {
        if (trace != null && trace.has(host.cursor())) {
            final String content = trace.content(host.cursor());
            if (content != null && !content.isEmpty()) {
                chosenEncoding = trace.encodingOf(host.cursor());
                return content;
            }
        }
        chosenEncoding = null;
        return host.getSample() == null
                ? ""
                : host.getSample();
    }

    public void onSample() {
        rerun.update();
    }

    /// Keep a regex's group shown alone, by number, or [RegexController#NO_GROUP] for none: a
    /// click on it in the pattern or its row.
    public void isolate(final int group) {
        isolatedGroup = group;
        isolatedLabel = null;
        paint();
    }

    /// Show a regex's group alone while the pointer is on it in the pattern or its row, or with
    /// [RegexController#NO_GROUP], the group a click keeps, if any, else every group.
    ///
    /// @param group The group.
    public void pointGroup(final int group) {
        pointedGroup = group;
        light();
    }

    /// @param onGroupPoint Told the group the pointer is on in the sample, `0` for a match
    ///                     outside any capturing group, and [RegexController#NO_GROUP] when it
    ///                     leaves.
    public void setOnGroupPoint(final IntConsumer onGroupPoint) {
        this.onGroupPoint = onGroupPoint;
    }

    private void light() {
        view.lightGroup(pointedGroup != RegexController.NO_GROUP
                ? pointedGroup
                : isolatedGroup);
    }

    /// Isolate a group by its label (null for none): the tree's selection.
    public void isolate(final String label) {
        isolatedLabel = label;
        isolatedGroup = RegexController.NO_GROUP;
        paint();
    }

    private void run() {
        final Template template = experiment;
        if (template == null) {
            trace = null;
            requested = null;
            view.showNothing("");
            return;
        }
        final MatchExpression match = template.match();
        if (!(match instanceof MatchExpression.Regex) && !(match instanceof MatchExpression.Pattern)
            && !(match instanceof MatchExpression.Parts) && !(match instanceof MatchExpression.Delimiter)) {
            trace = null;
            requested = null;
            view.showNothing("A " + Templates.kind(match) + " match has no pattern to try against a sample.");
            return;
        }
        final String sample = view.getSample();
        final String project = ProjectText.print(Templates.experiment(host.getProject(), template));
        final String key = project + '\u0000' + sample + '\u0000' + seedEncoding;
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
                .method(res -> res.preview(new ShapeshifterPreviewRequest(project, sample, null, seedEncoding)))
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
                        view.showNothing("The sample could not be tried: " + error.getMessage());
                    }
                })
                .owner(this)
                .exec();
    }

    /// The sample as the trace read it: matches, their groups, and where the template was tried.
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
            view.showNothing("The match does not compile: " + why);
            return;
        }
        final String text = trace.getInput();
        final List<Mark> marks = new ArrayList<>();
        final List<Sample.Row> rows = new ArrayList<>();
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
            // Ruled, as $0: the whole match is a group here, with its colour in the groups list
            // (ruled 2026-11-20), where the Design tab draws a match only when it is pointed at
            marks.add(new Mark(Mark.Kind.MATCH, frame.getId(), -1, frame.getTemplateId(), from,
                    from + frame.getContentLength(), colour, "Match " + frame.getMatchIndex(), true));
            final List<Sample.Cell> cells = new ArrayList<>();
            for (final Group group : groupsOf(frame.getId())) {
                final boolean placed = group.getContentOffset() != ShapeshifterTrace.NOT_A_SLICE;
                final int start = from + group.getContentOffset();
                final String value = placed
                        ? text.substring(Math.min(start, text.length()),
                        Math.min(start + group.getContentLength(), text.length()))
                        : null;
                if (group.getIndex() > 0) {
                    cells.add(new Sample.Cell(group.getIndex(), group.getName(), value,
                            Colours.hue(group.getIndex()), isolated(group)));
                }
                // A regex's group is shown alone by lighting it (Sample#lightGroup), and every
                // capture is drawn; a tree's label by drawing only its spans
                if (placed && group.getIndex() > 0
                    && (isolatedLabel == null || isolatedLabel.equals(group.getName()))) {
                    // Ruled, because in this pane every capture is the current level's: there is
                    // one pattern and no hierarchy to be below (design 18 §5.11.2).
                    marks.add(new Mark(Mark.Kind.CAPTURE, frame.getId(), group.getIndex(), null, start,
                            start + group.getContentLength(), Colours.hue(group.getIndex()),
                            group.getName() == null
                                    ? "$" + group.getIndex()
                                    : group.getName(), true));
                }
            }
            rows.add(new Sample.Row(frame.getMatchIndex(), from, frame.getContentLength(), cells));
        }
        marks.sort(Mark.OUTER_FIRST);
        view.showMatches(text, marks, rows, matches == 0
                ? "No matches · tried at " + tried + (tried == 1
                        ? " place"
                        : " places")
                : matches + (matches == 1
                        ? " match"
                        : " matches") + " · tried at " + tried + (tried == 1
                        ? " place"
                        : " places"));
        light();
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

    private boolean isolated(final Group group) {
        return isolatedGroup != RegexController.NO_GROUP
                ? group.getIndex() == isolatedGroup
                : isolatedLabel != null && isolatedLabel.equals(group.getName());
    }

}
