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

import stroom.shapeshifter.client.component.VariablesPaneController.Row;
import stroom.shapeshifter.client.component.VariablesPaneController.Section;
import stroom.shapeshifter.client.model.Hot;
import stroom.widget.button.client.LinkButton;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.InlineLabel;
import stroom.widget.text.client.Label;
import stroom.widget.util.client.CssProperties;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.MouseOutEvent;
import com.google.gwt.event.dom.client.MouseOverEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/// A debugger's variables pane: sections with quiet headings, rows of swatch, name, type badge, value.
public class VariablesPane extends Component {

    private Consumer<Long> onFrame = frameId -> {
    };
    private Consumer<Hot> onHover = hot -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    FlowPanel body;
    @UiField
    Label empty;

    private final Map<Hot, FlowPanel> lines = new HashMap<>();
    private FlowPanel lit;

    public VariablesPane() {
        initWidget(BINDER.createAndBindUi(this));
    }


    public void setSections(final List<Section> sections, final String emptyText) {
        body.clear();
        lines.clear();
        empty.setText(emptyText == null
                ? ""
                : emptyText);
        empty.setVisible(emptyText != null);
        for (final Section section : sections) {
            final FlowPanel panel = new FlowPanel();
            panel.addStyleName("variables-pane__section");
            if (section.isLink()) {
                final LinkButton heading = new LinkButton(section.getHeading());
                heading.addStyleName("variables-pane__heading variables-pane__heading--link");
                heading.setTitle("Go to the match that bound these");
                heading.addClickHandler(event -> onFrame.accept(section.getFrameId()));
                panel.add(heading);
            } else {
                final Label heading = new Label(section.getHeading());
                heading.addStyleName("variables-pane__heading");
                panel.add(heading);
            }
            if (section.getRows().isEmpty()) {
                final Label none = new Label(section.getNoneText());
                none.addStyleName("placeholder");
                panel.add(none);
            }
            for (final Row row : section.getRows()) {
                final FlowPanel line = new FlowPanel();
                line.addStyleName("variables-pane__row");
                final Hot hot = Hot.capture(row.getFrameId(), row.getIndex(), row.getVariable());
                lines.put(hot, line);
                line.addDomHandler(event -> onHover.accept(hot), MouseOverEvent.getType());
                line.addDomHandler(event -> onHover.accept(null), MouseOutEvent.getType());
                final InlineLabel swatch = new InlineLabel();
                swatch.addStyleName("swatch swatch--small");
                CssProperties.set(swatch.getElement(), "--swatch__colour",
                        Colours.safe(row.getColour()));
                final InlineLabel name = new InlineLabel(row.getName());
                name.addStyleName("variables-pane__name");
                final InlineLabel type = new InlineLabel(row.getType() == null
                        ? "string"
                        : row.getType());
                type.addStyleName("variables-pane__type");
                final InlineLabel value = new InlineLabel(row.getValue() == null
                        ? "—"
                        : row.getValue());
                value.addStyleName("variables-pane__value mono");
                value.setTitle(row.getValue() == null
                        ? ""
                        : row.getValue());
                line.add(swatch);
                line.add(name);
                line.add(type);
                line.add(value);
                panel.add(line);
            }
            body.add(panel);
        }
    }

    public void setHot(final Hot hot) {
        if (lit != null) {
            lit.removeStyleName(Marks.HOT_CLASS);
            lit = null;
        }
        if (hot != null && hot.getKind() == Hot.Kind.CAPTURE) {
            lit = lines.get(hot);
            if (lit != null) {
                lit.addStyleName(Marks.HOT_CLASS);
            }
        }
    }


    /// @param onFrame Told when frame.
    public void setOnFrame(final Consumer<Long> onFrame) {
        this.onFrame = onFrame;
    }

    /// @param onHover Told when hover.
    public void setOnHover(final Consumer<Hot> onHover) {
        this.onHover = onHover;
    }

    interface Binder extends UiBinder<Widget, VariablesPane> {

    }
}
