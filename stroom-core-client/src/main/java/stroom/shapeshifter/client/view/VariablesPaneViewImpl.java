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

package stroom.shapeshifter.client.view;

import stroom.shapeshifter.client.presenter.Hot;
import stroom.shapeshifter.client.presenter.VariablesPanePresenter.Row;
import stroom.shapeshifter.client.presenter.VariablesPanePresenter.Section;
import stroom.shapeshifter.client.presenter.VariablesPanePresenter.VariablesPaneView;
import stroom.shapeshifter.client.presenter.VariablesUiHandlers;

import com.google.gwt.event.dom.client.MouseOutEvent;
import com.google.gwt.event.dom.client.MouseOverEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Anchor;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.InlineLabel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A debugger's variables pane: sections with quiet headings, rows of swatch, name, type badge, value. */
public class VariablesPaneViewImpl extends ViewWithUiHandlers<VariablesUiHandlers> implements VariablesPaneView {

    private final Widget widget;

    @UiField
    FlowPanel body;
    @UiField
    Label empty;

    private final Map<Hot, FlowPanel> lines = new HashMap<>();
    private FlowPanel lit;

    @Inject
    public VariablesPaneViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setSections(final List<Section> sections, final String emptyText) {
        body.clear();
        lines.clear();
        empty.setText(emptyText == null
                ? ""
                : emptyText);
        empty.setVisible(emptyText != null);
        for (final Section section : sections) {
            final FlowPanel panel = new FlowPanel();
            panel.addStyleName("ss-var-section");
            if (section.isLink()) {
                final Anchor heading = new Anchor(section.getHeading());
                heading.addStyleName("ss-var-heading ss-var-heading--link");
                heading.setTitle("Go to the frame that bound these");
                heading.addClickHandler(event -> getUiHandlers().onFrame(section.getFrameId()));
                panel.add(heading);
            } else {
                final Label heading = new Label(section.getHeading());
                heading.addStyleName("ss-var-heading");
                panel.add(heading);
            }
            if (section.getRows().isEmpty()) {
                final Label none = new Label(section.getNoneText());
                none.addStyleName("ss-none");
                panel.add(none);
            }
            for (final Row row : section.getRows()) {
                final FlowPanel line = new FlowPanel();
                line.addStyleName("ss-var-row");
                final Hot hot = Hot.capture(row.getFrameId(), row.getIndex());
                lines.put(hot, line);
                line.addDomHandler(event -> getUiHandlers().onHover(hot), MouseOverEvent.getType());
                line.addDomHandler(event -> getUiHandlers().onHover(null), MouseOutEvent.getType());
                final InlineLabel swatch = new InlineLabel();
                swatch.addStyleName("ss-swatch ss-swatch--small");
                swatch.getElement().getStyle().setBackgroundColor(Colours.safe(row.getColour()));
                final InlineLabel name = new InlineLabel(row.getName());
                name.addStyleName("ss-var-name");
                final InlineLabel type = new InlineLabel(row.getType() == null
                        ? "string"
                        : row.getType());
                type.addStyleName("ss-type");
                final InlineLabel value = new InlineLabel(row.getValue() == null
                        ? "—"
                        : row.getValue());
                value.addStyleName("ss-var-value mono");
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

    @Override
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

    public interface Binder extends UiBinder<Widget, VariablesPaneViewImpl> {

    }
}
