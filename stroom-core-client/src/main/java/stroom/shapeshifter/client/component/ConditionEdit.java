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

import stroom.widget.component.client.Component;
import stroom.widget.layout.client.ScrollPane;
import stroom.widget.text.client.Label;

import com.google.gwt.user.client.ui.FlowPanel;
import com.google.inject.Inject;

public class ConditionEdit extends Component {

    private final ScrollPane widget;
    private final ClauseListPanel clauses = new ClauseListPanel();

    @Inject
    public ConditionEdit() {
        final FlowPanel panel = new FlowPanel();
        panel.setStyleName("workbench-form__section");
        final Label heading = new Label("Condition");
        heading.setStyleName("workbench-form__heading");
        panel.add(heading);
        clauses.setEmptyText("(no clauses yet)");
        panel.add(clauses);
        widget = new ScrollPane();
        widget.setWidget(panel);
        widget.setStyleName("max scroll-region");
        widget.setFocusable(true);
        widget.setRole("region");
        widget.setAriaLabel("Condition");
        initWidget(widget);
    }


    public ClauseListPanel getClauses() {
        return clauses;
    }
}
