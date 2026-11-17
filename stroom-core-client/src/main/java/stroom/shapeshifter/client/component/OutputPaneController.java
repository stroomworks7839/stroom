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
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;
import stroom.shapeshifter.shared.ShapeshifterTrace.Instruction;
import stroom.shapeshifter.shared.ShapeshifterTrace.OutputSpan;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// The output pane (design 18 §5.4): the whole output, with what the cursor frame wrote at full
/// strength and the rest dimmed, and what each of its child matches wrote marked in the child's
/// colour — a click on a child's span descends, as in the content pane. Output counted in events
/// rather than characters (an XML sink) has no character spans; the pane shows the text plain
/// and says so.
public class OutputPaneController extends Controller {

    private OutputPane view;

    /// How many cards the pane will mark before it stops marking the deeper ones (design 18
    /// §5.13.8, ruled 2026-11-17).
    ///
    /// **The cap is on the marks, not on the text**: the whole output is always shown, and past
    /// this the deeper generation simply stops being tinted. That is where this pane and the
    /// content pane are allowed to differ, and the difference has a reason — the content pane's
    /// `RENDER_CAP` withholds a sample that is still on disk and still reachable from the crumb,
    /// where the output exists nowhere else. The tint is a reading aid and can be given up; the
    /// bytes cannot.
    ///
    /// The number is where an ordinary large sample lands: a four-hundred-match run of a
    /// five-card template is about two thousand spans, and `Marks.light` walks every span in
    /// the block on each `mouseover`.
    private static final int MARK_CAP = 2_000;

    private ProjectHost host;

    @Inject
    public OutputPaneController(final EventBus eventBus) {
        super(eventBus);
    }

    /// Attaches this to a pane, e.g. one a template declares. The pane is then this controller's
    /// widget.
    ///
    /// @param view The pane.
    /// @return This, for its owner to keep.
    public OutputPaneController attach(final OutputPane view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnClick(this::onClick);
        view.setOnHover(this::onHover);
        return this;
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        refresh();
    }

    /// What a click on a mark means (design 18 §5.13.3, ruled 2026-11-17). Two different
    /// things sit under a mark here, and they cannot mean the same thing.
    ///
    /// - A **match** is output written by a child frame, so a click descends to it, as in the
    ///   content pane.
    /// - A **card of the cursor's own body** has no frame beneath it to descend to, so a click
    ///   brings that card into view in the body panel and lights it. Scrolling is the whole of
    ///   what the click adds — pointing already lights the card, but only if it is on screen.
    /// - A **card of a frame below the cursor** descends, like a match: it is the frame that
    ///   ran it that the reader is asking to stand in, and once there the card is one of their
    ///   own and clicking it reveals it.
    ///
    /// The descent is **one level**, as everywhere (§5.12.4). A clicked card can be more than
    /// one frame down — from the document, whose own body writes nothing and so spends no
    /// generation, the cards in reach belong to its grandchildren — so this descends to the
    /// child of the cursor on the way to it rather than jumping to it.
    ///
    /// What §5.13.3 first ruled, and does not do: descend into the first match an
    /// `apply-templates` card dispatched. It is self-defeating — descending changes the body
    /// panel to the child's template, so the card just revealed is gone — and it was already
    /// built, on the card's own note. It is also unreachable: such a card's span is covered
    /// entirely by the spans of the cards its matches ran, so it has no pixels of its own left
    /// to click.
    ///
    /// @param hot What was clicked, or null for unmarked text.
    public void onClick(final Hot hot) {
        if (hot == null) {
            return;
        }
        final long cursor = host.cursor();
        if (hot.getKind() == Hot.Kind.INSTRUCTION && hot.getFrameId() == cursor) {
            host.revealCard(hot.getIndex());
            return;
        }
        final long into = toward(host.trace(), cursor, hot.getFrameId());
        if (into >= 0) {
            host.setCursor(into);
        }
    }

    /// The child of the cursor on the path to a frame, or -1 where there is none: the one level
    /// a click descends, however far below the cursor the thing clicked turned out to be.
    private static long toward(final TraceModel trace, final long cursor, final long frameId) {
        if (trace == null || frameId == cursor) {
            return -1;
        }
        final List<Long> path = trace.path(frameId);
        final int at = path.indexOf(cursor);
        return at < 0 || at + 1 >= path.size()
                ? -1
                : path.get(at + 1);
    }

    public void onHover(final Hot hot) {
        host.hover(hot);
    }

    public void setHot(final Hot hot) {
        view.setHot(hot);
    }

    public void refresh() {
        final TraceModel trace = host == null
                ? null
                : host.trace();
        if (trace == null) {
            view.showEmpty("What the selected frame wrote, and what its matches wrote, after a run.");
            return;
        }
        final String output = trace.trace().getOutput() == null
                ? ""
                : trace.trace().getOutput();
        final long cursor = host.cursor();
        final List<Mark> marks = new ArrayList<>();
        boolean events = false;
        final OutputSpan own = trace.output(cursor);
        if (own != null && !"EVENTS".equals(own.getUnit())) {
            final int from = (int) own.getOffset();
            final int to = (int) (own.getOffset() + own.getLength());
            marks.add(new Mark(Kind.OWN, cursor, -1, null, from, to, null, "written by " + trace.label(cursor)));
            // Dimmed as marks of its own: an opacity on the pane could not be undone inside it.
            marks.add(new Mark(Kind.DIM, cursor, -1, null, 0, from, null, null));
            marks.add(new Mark(Kind.DIM, cursor, -1, null, to, output.length(), null, null));
        }
        // Coloured by the body card that wrote it (design 18 §5.5, §5.13.1): each top-level
        // instruction's output in its card's hue, for the cursor's own cards and the cards of
        // the matches one generation below it, and ruled where the card is one of the cursor's.
        final InstructionHues hues = InstructionHues.inScope(trace, cursor);
        // The cursor's own cards first and always, whatever the cap: they are bounded by the
        // size of one body, and they are the ones the rule is about. A pane that stopped ruling
        // the current level would be answering a different question from the one it was asked.
        boolean capped = false;
        for (final Long frameId : hues.frames()) {
            if (frameId != cursor && marks.size() >= MARK_CAP) {
                capped = true;
                break;
            }
            events |= place(trace, cursor, frameId, hues, marks);
        }
        for (final Frame child : trace.children(cursor)) {
            final OutputSpan span = trace.output(child.getId());
            if (span == null) {
                continue;
            }
            if ("EVENTS".equals(span.getUnit())) {
                events = true;
                continue;
            }
            marks.add(new Mark(Kind.MATCH, child.getId(), -1, child.getTemplateId(), (int) span.getOffset(),
                    (int) (span.getOffset() + span.getLength()), host.colour(child.getTemplateId()),
                    child.getTemplateName() + " #" + child.getMatchIndex() + " — click to descend"));
        }
        marks.sort(Mark.OUTER_FIRST);
        view.showOutput(output, marks, note(events, capped));
    }

    /// What the pane says about what it could not mark. The whole output is shown either way.
    private static String note(final boolean events, final boolean capped) {
        if (events) {
            return "this output is counted in events, so its spans cannot be marked";
        }
        return capped
                ? "showing the first " + MARK_CAP + " marked cards; the rest of the output is "
                  + "here but not tinted"
                : null;
    }

    /// The cards of one frame, marked where they wrote characters (design 18 §5.13.1, §5.13.2).
    ///
    /// Output offsets are absolute positions in the sink rather than offsets within the parent,
    /// so unlike the content pane there is no base to accumulate down the walk: a card's span is
    /// already in the pane's own coordinates.
    ///
    /// **The colour says which card wrote these bytes; the rule says the card is one of yours.**
    /// An `apply-templates` card's span covers everything its matches wrote, so the generation
    /// below subdivides all of it and the parent card's colour often survives nowhere — but the
    /// two channels are independent, so that card's extent is still readable as its underline.
    /// Cards that write bytes themselves are never subdivided and keep their colour outright.
    ///
    /// A card that wrote nothing is not marked: an empty span would be a mark the reader cannot
    /// see and cannot point at, and the card is already shown in the body panel.
    ///
    /// @param trace The run.
    /// @param cursor The selected frame, whose own cards are the ruled ones.
    /// @param frameId The frame whose body ran these cards.
    /// @param hues The colour of each card in reach.
    /// @param marks The marks to add to.
    /// @return Whether a card of this frame was counted in events rather than characters, which
    /// the pane says in a note because such a card has no span in the text to mark.
    private static boolean place(final TraceModel trace, final long cursor, final long frameId,
                                 final InstructionHues hues, final List<Mark> marks) {
        boolean events = false;
        final Frame frame = trace.frame(frameId);
        for (final Instruction instruction : trace.instructions(frameId)) {
            if ("EVENTS".equals(instruction.getUnit())) {
                events = true;
                continue;
            }
            if (instruction.getLength() == 0) {
                continue;
            }
            marks.add(new Mark(Kind.INSTRUCTION, frameId, instruction.getIndex(), null,
                    (int) instruction.getOffset(),
                    (int) (instruction.getOffset() + instruction.getLength()),
                    hues.hue(frameId, instruction.getIndex()),
                    "written by instruction " + (instruction.getIndex() + 1) + " of " + (frame == null
                            ? trace.label(frameId)
                            : frame.getTemplateName() + " #" + frame.getMatchIndex()),
                    frameId == cursor));
        }
        return events;
    }

}
