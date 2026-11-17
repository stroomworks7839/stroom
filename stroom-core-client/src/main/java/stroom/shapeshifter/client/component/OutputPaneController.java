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
        for (final Long frameId : hues.frames()) {
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
        view.showOutput(output, marks, events
                ? "this output is counted in events, so its spans cannot be marked"
                : null);
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
