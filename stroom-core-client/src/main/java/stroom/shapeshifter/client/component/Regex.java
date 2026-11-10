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

import stroom.shapeshifter.client.model.GroupRowData;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextArea;
import stroom.widget.form.client.TextBox;
import stroom.widget.text.client.Html;
import stroom.widget.text.client.Label;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyUpEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

public class Regex
        extends Component
        implements GroupRow.Listener {

    private static final Binder BINDER = GWT.create(Binder.class);

    private Runnable onChange = () -> {
    };
    private Runnable onExplode = () -> {
    };
    private BiConsumer<Integer, String> onGroupName = (index, name) -> {
    };
    private IntConsumer onGroupSelect = index -> {
    };

    private static final String GROUP = "data-group";

    private boolean enabled = true;

    @UiField
    TextArea pattern;
    @UiField
    Html patternMap;
    @UiField
    Label error;
    @UiField
    Label explode;
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
        explode.addClickHandler(event -> onExplode(event));
        setError(null);
        setExplain(null);
        patternMap.addDomHandler(event -> {
            if (!Element.is(event.getNativeEvent().getEventTarget())) {
                return;
            }
            Element element = Element.as(event.getNativeEvent().getEventTarget());
            while (element != null && element != patternMap.getElement()) {
                if (element.hasAttribute(GROUP)) {
                    onGroupSelect.accept(Integer.parseInt(element.getAttribute(GROUP)));
                    return;
                }
                element = element.getParentElement();
            }
        }, ClickEvent.getType());
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

    private void onExplode(final ClickEvent e) {
        if (enabled) {
            onExplode.run();
        }
    }

    private void changed() {
        onChange.run();
    }

    @Override
    public void onGroupName(final int index, final String name) {
        onGroupName.accept(index, name);
    }

    @Override
    public void onGroupSelect(final int index) {
        onGroupSelect.accept(index);
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
        pattern.setEnabled(enabled);
        caseInsensitive.setEnabled(enabled);
        dotAll.setEnabled(enabled);
        advance.setEnabled(enabled);
        explode.setStyleDependentName("disabled", !enabled);
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

    public void setGroups(final List<GroupRowData> data) {
        groups.clear();
        noGroups.setVisible(data.isEmpty());
        for (final GroupRowData row : data) {
            groups.add(new GroupRow(row, this, enabled));
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

    interface Binder extends UiBinder<Widget, Regex> {

    }
}
