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

import stroom.shapeshifter.client.presenter.OutputPanePresenter.OutputPaneView;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;
import stroom.shapeshifter.shared.ShapeshifterTrace.OutputSpan;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The output pane (design 18 §5.4): the whole output, with what the cursor frame wrote marked
 * and what each of its child matches wrote marked in the child's colour — a click on a child's
 * span descends, as in the content pane. Output counted in events rather than characters (an
 * XML sink) has no character spans; the pane shows the text plain and says so.
 */
public class OutputPanePresenter extends MyPresenterWidget<OutputPaneView> implements OutputUiHandlers {

    private ProjectHost host;

    @Inject
    public OutputPanePresenter(final EventBus eventBus, final OutputPaneView view) {
        super(eventBus, view);
        view.setUiHandlers(this);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        refresh();
    }

    @Override
    public void onDescend(final long frameId) {
        host.setCursor(frameId);
    }

    public void refresh() {
        final TraceModel trace = host == null
                ? null
                : host.trace();
        if (trace == null) {
            getView().showEmpty("What the selected frame wrote, and what its matches wrote, after a run.");
            return;
        }
        final String output = trace.trace().getOutput() == null
                ? ""
                : trace.trace().getOutput();
        final long cursor = host.cursor();
        final List<ContentPanePresenter.Span> spans = new ArrayList<>();
        boolean events = false;
        final OutputSpan own = trace.output(cursor);
        if (own != null && !"EVENTS".equals(own.getUnit())) {
            spans.add(new ContentPanePresenter.Span(-1, (int) own.getOffset(),
                    (int) (own.getOffset() + own.getLength()), null, "written by " + trace.label(cursor)));
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
            spans.add(new ContentPanePresenter.Span(child.getId(), (int) span.getOffset(),
                    (int) (span.getOffset() + span.getLength()), host.colour(child.getTemplateId()),
                    child.getTemplateName() + " #" + child.getMatchIndex() + " — click to descend"));
        }
        spans.sort((a, b) -> Integer.compare(a.getStart(), b.getStart()));
        getView().showOutput(output, spans, events
                ? "this output is counted in events, so its spans cannot be marked"
                : null);
    }

    public interface OutputPaneView extends View, HasUiHandlers<OutputUiHandlers> {

        void showEmpty(String text);

        /** The output, the cursor's own span first (frame id -1) and the children's after, and a note or null. */
        void showOutput(String output, List<ContentPanePresenter.Span> spans, String note);
    }
}
