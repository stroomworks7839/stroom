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

import stroom.shapeshifter.client.model.GroupRowData;
import stroom.widget.button.client.LinkButton;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextArea;
import stroom.widget.form.client.TextBox;
import stroom.widget.text.client.Html;
import stroom.widget.text.client.Label;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyUpEvent;
import com.google.gwt.event.dom.client.MouseOutEvent;
import com.google.gwt.event.dom.client.MouseOverEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

public class Regex
        extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    private Runnable onChange = () -> {
    };
    private Runnable onExplode = () -> {
    };
    private BiConsumer<Integer, String> onGroupName = (index, name) -> {
    };
    private IntConsumer onGroupSelect = index -> {
    };
    private IntConsumer onGroupPoint = index -> {
    };
    private final List<GroupRow> groupRows = new ArrayList<>();

    private static final String GROUP = "data-group";
    // The pattern while one group is shown alone, and that group
    private static final String MAP_POINTING = "regex__pattern-map--pointing";
    private static final String GROUP_LIT = "regex__group--lit";

    private boolean enabled = true;

    @UiField
    TextArea pattern;
    @UiField
    Html patternMap;
    @UiField
    Label error;
    @UiField
    LinkButton explode;
    @UiField
    TickBox dotAll;
    @UiField
    TickBox caseInsensitive;
    @UiField
    TextBox advance;
    @UiField
    FlowPanel groups;
    @UiField
    Label noGroups;
    @UiField
    Label explain;

    public Regex() {
        initWidget(BINDER.createAndBindUi(this));
        pattern.addKeyUpHandler(event -> onPatternKey(event));
        pattern.addValueChangeHandler(event -> onPattern(event));
        caseInsensitive.addValueChangeHandler(event -> onCaseInsensitive(event));
        dotAll.addValueChangeHandler(event -> onDotAll(event));
        advance.addValueChangeHandler(event -> onAdvance(event));
        explode.setOnClick(this::onExplode);
        setError(null);
        setExplain(null);
        patternMap.addDomHandler(event -> {
            final int group = groupAt(event.getNativeEvent().getEventTarget());
            if (group != RegexController.NO_GROUP) {
                onGroupSelect.accept(group);
            }
        }, ClickEvent.getType());
        // Pointing at a group in the pattern shows it alone, as pointing at its row does
        patternMap.addDomHandler(event -> onGroupPoint.accept(groupAt(event.getNativeEvent().getEventTarget())),
                MouseOverEvent.getType());
        patternMap.addDomHandler(event -> {
            final EventTarget to = event.getRelatedTarget();
            if (to == null || !Element.is(to) || !patternMap.getElement().isOrHasChild(Element.as(to))) {
                onGroupPoint.accept(RegexController.NO_GROUP);
            }
        }, MouseOutEvent.getType());
    }

    // The innermost group the element is in, in the pattern, or NO_GROUP
    private int groupAt(final EventTarget target) {
        if (!Element.is(target)) {
            return RegexController.NO_GROUP;
        }
        Element element = Element.as(target);
        while (element != null && element != patternMap.getElement()) {
            if (element.hasAttribute(GROUP)) {
                return Integer.parseInt(element.getAttribute(GROUP));
            }
            element = element.getParentElement();
        }
        return RegexController.NO_GROUP;
    }

    /// Shows one group alone, in the pattern and the rows: its rule over its whole extent and
    /// the others' taken away, and its row lit; or every group, as at rest.
    ///
    /// @param group The group, or [RegexController#NO_GROUP] for every group.
    public void lightGroup(final int group) {
        final Element map = patternMap.getElement();
        map.removeClassName(MAP_POINTING);
        final NodeList<Element> spans = map.getElementsByTagName("span");
        for (int i = 0; i < spans.getLength(); i++) {
            final Element span = spans.getItem(i);
            final boolean lit = String.valueOf(group).equals(span.getAttribute(GROUP));
            if (lit) {
                span.addClassName(GROUP_LIT);
            } else {
                span.removeClassName(GROUP_LIT);
            }
        }
        if (group != RegexController.NO_GROUP) {
            map.addClassName(MAP_POINTING);
        }
        for (final GroupRow row : groupRows) {
            row.setLit(row.getIndex() == group);
        }
    }


    private void onPatternKey(final KeyUpEvent e) {
        changed();
    }

    private void onPattern(final ValueChangeEvent<String> e) {
        changed();
    }

    private void onCaseInsensitive(final ValueChangeEvent<Boolean> e) {
        changed();
    }

    private void onDotAll(final ValueChangeEvent<Boolean> e) {
        changed();
    }

    private void onAdvance(final ValueChangeEvent<String> e) {
        changed();
    }

    private void onExplode() {
        if (enabled) {
            onExplode.run();
        }
    }

    private void changed() {
        onChange.run();
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        this.enabled = !readOnly;
        pattern.setEnabled(!readOnly);
        caseInsensitive.setEnabled(!readOnly);
        dotAll.setEnabled(!readOnly);
        advance.setEnabled(!readOnly);
        explode.setEnabled(!readOnly);
    }

    public String getPattern() {
        return pattern.getText();
    }

    public void setPattern(final String text) {
        pattern.setText(text);
    }

    public boolean isCaseInsensitive() {
        return caseInsensitive.getValue();
    }

    public void setCaseInsensitive(final boolean value) {
        caseInsensitive.setValue(value);
    }

    public boolean isDotAll() {
        return dotAll.getValue();
    }

    public void setDotAll(final boolean value) {
        dotAll.setValue(value);
    }

    public int getAdvance() {
        try {
            return Integer.parseInt(advance.getText().trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    public void setAdvance(final int value) {
        advance.setText(String.valueOf(value));
    }

    public void setError(final String text) {
        error.setText(text == null
                ? ""
                : text);
    }

    public void setPatternMap(final SafeHtml html) {
        patternMap.setHTML(html);
    }

    /// Colours the groups' rows again, in place, leaving any name being typed where it is.
    ///
    /// @param hueOf A group's colour by its number.
    public void recolourGroups(final IntFunction<String> hueOf) {
        for (final GroupRow row : groupRows) {
            row.setHue(hueOf.apply(row.getIndex()));
        }
    }

    /// @param data The groups, `$0` first; with no other, the pattern has no capturing group.
    public void setGroups(final List<GroupRowData> data) {
        groups.clear();
        groupRows.clear();
        noGroups.setVisible(data.size() <= 1);
        for (final GroupRowData row : data) {
            final GroupRow groupRow = new GroupRow(row, enabled);
            groupRow.setOnName((index, name) -> onGroupName.accept(index, name));
            groupRow.setOnSelect(index -> onGroupSelect.accept(index));
            groupRow.setOnPoint(index -> onGroupPoint.accept(index));
            groups.add(groupRow);
            groupRows.add(groupRow);
        }
    }

    public void setExplain(final String text) {
        explain.setText(text == null
                ? "—"
                : text);
    }


    /// @param onChange Told when change.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    /// @param onExplode Told when explode.
    public void setOnExplode(final Runnable onExplode) {
        this.onExplode = onExplode;
    }

    /// @param onGroupName Told when groupname.
    public void setOnGroupName(final BiConsumer<Integer, String> onGroupName) {
        this.onGroupName = onGroupName;
    }

    /// @param onGroupSelect Told when groupselect.
    public void setOnGroupSelect(final IntConsumer onGroupSelect) {
        this.onGroupSelect = onGroupSelect;
    }

    /// @param onGroupPoint Told when the pointer comes onto a group, in the pattern or its row,
    ///                     with its index, and when it leaves, with [RegexController#NO_GROUP].
    public void setOnGroupPoint(final IntConsumer onGroupPoint) {
        this.onGroupPoint = onGroupPoint;
    }

    interface Binder extends UiBinder<Widget, Regex> {

    }
}
