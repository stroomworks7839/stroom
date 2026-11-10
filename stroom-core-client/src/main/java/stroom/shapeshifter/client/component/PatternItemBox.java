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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.client.model.PatternItem;
import stroom.widget.htree.client.Box;

import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

/**
 * One node of the pattern tree as a box, the shape Stroom's expression editor uses: absolutely
 * positioned by the tree layout, selectable, and wearing the same classes so the two trees read
 * as one idiom.
 */
public class PatternItemBox extends Box<PatternItem> {

    private final SimplePanel background = new SimplePanel();
    private final PatternItem item;

    public PatternItemBox(final PatternItem item) {
        this.item = item;
        background.setStyleName("expression-item");
        initWidget(background);
    }

    public void setInnerWidget(final Widget innerWidget) {
        background.setWidget(innerWidget);
    }

    @Override
    public void setSelected(final boolean selected) {
        if (selected) {
            getElement().setAttribute("aria-selected", "true");
        } else {
            getElement().removeAttribute("aria-selected");
        }
    }

    @Override
    public void showHotspot(final boolean show) {
        if (show) {
            getElement().addClassName("expression-item--target");
        } else {
            getElement().removeClassName("expression-item--target");
        }
    }

    @Override
    public PatternItem getItem() {
        return item;
    }
}
