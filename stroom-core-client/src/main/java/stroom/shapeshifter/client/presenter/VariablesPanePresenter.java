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

import stroom.shapeshifter.client.presenter.VariablesPanePresenter.VariablesPaneView;
import stroom.shapeshifter.shared.ShapeshifterTrace.Capture;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The variables pane (design 18 §5.2): everything in scope at the cursor, innermost first —
 * this match's own captures, then what each ancestor bound, each row naming the frame that
 * bound it. A row of an outer frame is a click to that frame, where its span can be seen.
 */
public class VariablesPanePresenter extends MyPresenterWidget<VariablesPaneView> implements VariablesUiHandlers {

    private ProjectHost host;

    @Inject
    public VariablesPanePresenter(final EventBus eventBus, final VariablesPaneView view) {
        super(eventBus, view);
        view.setUiHandlers(this);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        refresh();
    }

    @Override
    public void onFrame(final long frameId) {
        host.setCursor(frameId);
    }

    public void refresh() {
        final TraceModel trace = host == null
                ? null
                : host.trace();
        if (trace == null) {
            getView().setSections(List.of(),
                    "Every name in scope at the selected frame, innermost first, after a run.");
            return;
        }
        final List<Section> sections = new ArrayList<>();
        final List<Long> path = trace.path(host.cursor());
        for (int i = path.size() - 1; i >= 0; i--) {
            final long id = path.get(i);
            final Frame frame = trace.frame(id);
            final List<Row> rows = new ArrayList<>();
            int hue = 0;
            for (final Capture capture : trace.captures(id)) {
                rows.add(new Row("$" + capture.getName(), capture.getType(), capture.getValue(),
                        RegexTabPresenter.hue(hue++)));
            }
            final boolean own = id == host.cursor();
            sections.add(new Section(id, own
                    ? "this match"
                    : "outer scope · " + trace.label(id), rows, frame == null
                    ? "the document binds nothing"
                    : "this pattern bound nothing", !own));
        }
        getView().setSections(sections, null);
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

        /** Whether the heading is a click to the frame. */
        public boolean isLink() {
            return link;
        }
    }

    public static final class Row {

        private final String name;
        private final String type;
        private final String value;
        private final String colour;

        public Row(final String name, final String type, final String value, final String colour) {
            this.name = name;
            this.type = type;
            this.value = value;
            this.colour = colour;
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

    public interface VariablesPaneView extends View, HasUiHandlers<VariablesUiHandlers> {

        /** The sections innermost first; or, with none, the empty text. */
        void setSections(List<Section> sections, String emptyText);
    }
}
