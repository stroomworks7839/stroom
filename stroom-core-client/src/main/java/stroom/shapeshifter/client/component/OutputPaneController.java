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

/// The output pane (design 18 §5.4): the whole output, with what the cursor frame wrote at full
/// strength and the rest dimmed, and what each of its child matches wrote marked in the child's
/// colour — a click on a child's span descends, as in the content pane. Output counted in events
/// rather than characters (an XML sink) has no character spans; the pane shows the text plain
/// and says so.
public class OutputPaneController extends Controller {

    private final OutputPane view;

    private ProjectHost host;

    @Inject
    public OutputPaneController(final EventBus eventBus, final OutputPane view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        view.setOnDescend(this::onDescend);
        view.setOnHover(this::onHover);
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
        // Coloured by the body card that wrote it (design 18 §5.5): each top-level instruction's
        // output in its card's hue, the children's matches inside.
        for (final Instruction instruction : trace.instructions(cursor)) {
            if ("EVENTS".equals(instruction.getUnit())) {
                events = true;
                continue;
            }
            marks.add(new Mark(Kind.INSTRUCTION, cursor, instruction.getIndex(), null, (int) instruction.getOffset(),
                    (int) (instruction.getOffset() + instruction.getLength()),
                    BodyController.cardHue(instruction.getIndex()),
                    "written by instruction " + (instruction.getIndex() + 1) + " of " + trace.label(cursor)));
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

}
