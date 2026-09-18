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

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The editor's palette as swatches to pick from, with <i>auto</i> — the palette by position —
 * first (design 18 §5.6: colour is editor presentation, auto-assigned and user-overridable).
 */
public class ColourPalette extends Composite {

    private final List<Label> swatches = new ArrayList<>();
    private String value;

    public ColourPalette() {
        final FlowPanel panel = new FlowPanel();
        panel.setStyleName("ss-palette");
        panel.add(swatch(null, "auto: the palette by position"));
        for (int i = 0; i < Templates.paletteSize(); i++) {
            final String colour = Templates.colour(i);
            panel.add(swatch(colour, colour));
        }
        initWidget(panel);
        setValue(null);
    }

    private Label swatch(final String colour, final String title) {
        final Label swatch = new Label();
        swatch.setStyleName("ss-pal-swatch");
        swatch.setTitle(title);
        if (colour == null) {
            swatch.addStyleName("ss-pal-swatch--auto");
            swatch.setText("auto");
        } else {
            swatch.getElement().getStyle().setBackgroundColor(colour);
        }
        swatch.addClickHandler((ClickEvent e) -> setValue(colour));
        swatches.add(swatch);
        return swatch;
    }

    /** The chosen colour, or null for auto. */
    public String getValue() {
        return value;
    }

    public void setValue(final String colour) {
        this.value = colour;
        for (int i = 0; i < swatches.size(); i++) {
            final String own = i == 0
                    ? null
                    : Templates.colour(i - 1);
            swatches.get(i).setStyleDependentName("sel", Objects.equals(own, colour));
        }
    }
}
