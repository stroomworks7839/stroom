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

package stroom.shapeshifter.client.view;

import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.Label;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/// The process a stream meets, drawn small across the top of the document (design 05 §10; A98): the
/// three stages in the order they happen, the open one highlighted, each clickable to its tab, and
/// beside each the document's own figure from the Supervisor — the rules the gate routes to, the
/// streams waiting on a session, the sessions parked for a person. The document says what should
/// happen; these say what is happening.
public class ProcessHeader extends Composite {

    private final Map<Stage, Label> figures = new EnumMap<>(Stage.class);
    private final Map<Stage, FocusPanel> boxes = new EnumMap<>(Stage.class);
    private Consumer<Stage> onStage;

    public ProcessHeader() {
        final FlowPanel panel = new FlowPanel();
        panel.addStyleName("flow-process");
        for (final Stage stage : Stage.values()) {
            if (stage.ordinal() > 0) {
                final Label arrow = new Label("→");
                arrow.addStyleName("flow-process-arrow");
                panel.add(arrow);
            }
            final FlowPanel inner = new FlowPanel();
            final Label title = new Label(stage.title);
            title.addStyleName("flow-process-title");
            final Label caption = new Label(stage.caption);
            caption.addStyleName("flow-process-caption");
            final Label figure = new Label("");
            figure.addStyleName("flow-process-figure");
            inner.add(title);
            inner.add(caption);
            inner.add(figure);
            final FocusPanel box = new FocusPanel(inner);
            box.addStyleName("flow-process-stage");
            box.setTitle("Open the " + stage.title + " tab");
            box.addClickHandler(event -> {
                if (onStage != null) {
                    onStage.accept(stage);
                }
            });
            figures.put(stage, figure);
            boxes.put(stage, box);
            panel.add(box);
        }
        initWidget(panel);
    }

    /// Who is told which stage was clicked.
    public void setOnStage(final Consumer<Stage> onStage) {
        this.onStage = onStage;
    }

    /// Highlight the open stage, or none where another tab is open.
    public void setStage(final Stage stage) {
        boxes.forEach((each, box) -> box.setStyleName("flow-process-stage--open", each == stage));
    }

    /// The document's figures from the Supervisor; null clears one.
    public void setFigures(final Long rules, final Long waiting, final Long parked) {
        figures.get(Stage.ROUTE).setText(rules == null
                ? ""
                : rules + (rules == 1
                        ? " rule"
                        : " rules"));
        figures.get(Stage.SCORE).setText(waiting == null
                ? ""
                : waiting + (waiting == 1
                        ? " stream waiting"
                        : " streams waiting"));
        figures.get(Stage.LEARN).setText(parked == null
                ? ""
                : parked + (parked == 1
                        ? " session parked"
                        : " sessions parked"));
    }


    // --------------------------------------------------------------------------------


    /// The three stages, in the order a stream meets them.
    public enum Stage {
        ROUTE("Route", "the gate: a shape to its rule"),
        SCORE("Score", "the bar, and the gate to a rule"),
        LEARN("Learn", "the flow a session walks");

        private final String title;
        private final String caption;

        Stage(final String title, final String caption) {
            this.title = title;
            this.caption = caption;
        }
    }
}
