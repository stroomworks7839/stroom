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
import stroom.shapeshifter.shared.ShapeshifterTrace.Capture;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// The variables pane (design 18 §5.2): everything in scope at the cursor, innermost first —
/// this match's own captures, then what each ancestor bound, each row naming the frame that
/// bound it. A row of an outer frame is a click to that frame, where its span can be seen.
public class VariablesPaneController extends Controller {

    private VariablesPane view;

    private ProjectHost host;

    @Inject
    public VariablesPaneController(final EventBus eventBus) {
        super(eventBus);
    }

    /// Attaches this to a pane, e.g. one a template declares. The pane is then this
    /// controller's widget.
    ///
    /// @param view The pane.
    /// @return This, for its owner to keep.
    public VariablesPaneController attach(final VariablesPane view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnFrame(this::onFrame);
        view.setOnHover(this::onHover);
        return this;
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        refresh();
    }

    public void onFrame(final long frameId) {
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
            view.setSections(List.of(),
                    "Every name in scope at the selected frame, innermost first, after a run.");
            return;
        }
        final List<Section> sections = new ArrayList<>();
        // The pane owns a capture's colour and the content pane reads it from here, so both build
        // the same allocation rather than each generating one (design 18 §5.11.2, ruled
        // 2026-11-16). It is numbered over everything in scope, not per frame, because this pane
        // shows every frame on the path at once and a per-frame index gave two of them the same
        // hue.
        // onPath rather than inScope: this pane shows the path and nothing below it, and the path
        // is numbered first, so the subtree walk would allocate the same colours for these rows
        // and then be thrown away. With the cursor at the document that walk is the whole tree.
        final CaptureHues hues = CaptureHues.onPath(trace, host.cursor());
        final List<Long> path = trace.path(host.cursor());
        for (int i = path.size() - 1; i >= 0; i--) {
            final long id = path.get(i);
            final Frame frame = trace.frame(id);
            final List<Row> rows = new ArrayList<>();
            final List<Capture> captures = trace.captures(id);
            for (int c = 0; c < captures.size(); c++) {
                final Capture capture = captures.get(c);
                rows.add(new Row(id, c, "$" + capture.getName(), capture.getType(), capture.getValue(),
                        hues.hue(id, c)));
            }
            final boolean own = id == host.cursor();
            sections.add(new Section(id, own
                    ? "this match"
                    : "outer scope · " + trace.label(id), rows, frame == null
                    ? "the document binds nothing"
                    : "this pattern bound nothing", !own));
        }
        view.setSections(sections, null);
    }

    public static final class Section {

        private final long frameId;
        private final String heading;
        private final List<Row> rows;
        private final String noneText;
        private final boolean link;

        public Section(final long frameId, final String heading, final List<Row> rows, final String noneText,
                       final boolean link) {
            this.frameId = frameId;
            this.heading = heading;
            this.rows = rows;
            this.noneText = noneText;
            this.link = link;
        }

        public long getFrameId() {
            return frameId;
        }

        public String getHeading() {
            return heading;
        }

        public List<Row> getRows() {
            return rows;
        }

        public String getNoneText() {
            return noneText;
        }

        /// Whether the heading is a click to the frame.
        public boolean isLink() {
            return link;
        }
    }

    public static final class Row {

        private final long frameId;
        private final int index;
        private final String name;
        private final String type;
        private final String value;
        private final String colour;

        public Row(final long frameId, final int index, final String name, final String type, final String value,
                   final String colour) {
            this.frameId = frameId;
            this.index = index;
            this.name = name;
            this.type = type;
            this.value = value;
            this.colour = colour;
        }

        public long getFrameId() {
            return frameId;
        }

        public int getIndex() {
            return index;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getValue() {
            return value;
        }

        public String getColour() {
            return colour;
        }
    }

}
