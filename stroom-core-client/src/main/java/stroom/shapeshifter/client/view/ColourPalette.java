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

import stroom.shapeshifter.client.presenter.Templates;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The editor's palette as swatches to pick from, behind an <i>auto</i> tick (design 18 §5.6:
 * colour is editor presentation, auto-assigned and user-overridable). Ticked, the swatches are
 * greyed and the one auto resolves to is marked; unticked, that colour is the choice until
 * another swatch is clicked, so unticking changes nothing until the author does.
 */
public class ColourPalette extends Composite {

    private final CustomCheckBox auto = new CustomCheckBox();
    private final FlowPanel swatches = new FlowPanel();
    private final List<Label> labels = new ArrayList<>();
    private String autoColour;
    private String chosen;

    public ColourPalette() {
        final FlowPanel panel = new FlowPanel();
        panel.setStyleName("ss-palette");
        auto.setLabel("Auto");
        auto.setTitle("The palette by position");
        auto.addValueChangeHandler(e -> render());
        panel.add(auto);
        swatches.setStyleName("ss-palette-swatches");
        for (int i = 0; i < Templates.paletteSize(); i++) {
            final String colour = Templates.colour(i);
            final Label swatch = new Label();
            swatch.setStyleName("ss-pal-swatch");
            swatch.setTitle(colour);
            swatch.getElement().getStyle().setBackgroundColor(colour);
            swatch.addClickHandler((ClickEvent e) -> {
                if (!auto.getValue()) {
                    chosen = colour;
                    render();
                }
            });
            labels.add(swatch);
            swatches.add(swatch);
        }
        panel.add(swatches);
        initWidget(panel);
        setValue(null, Templates.colour(0));
    }

    /** The chosen colour, or null for auto. */
    public String getValue() {
        return auto.getValue()
                ? null
                : chosen;
    }

    /** The author's colour or null for auto, and the colour auto resolves to. */
    public void setValue(final String override, final String autoColour) {
        this.autoColour = autoColour;
        this.chosen = override == null
                ? autoColour
                : override;
        auto.setValue(override == null);
        render();
    }

    private void render() {
        final boolean isAuto = auto.getValue();
        final String marked = isAuto
                ? autoColour
                : chosen;
        swatches.setStyleDependentName("off", isAuto);
        for (int i = 0; i < labels.size(); i++) {
            labels.get(i).setStyleDependentName("sel", Objects.equals(Templates.colour(i), marked));
        }
    }
}
