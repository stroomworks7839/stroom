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

import stroom.shapeshifter.client.presenter.GroupRowData;
import stroom.shapeshifter.client.presenter.RegexPresenter.RegexView;
import stroom.shapeshifter.client.presenter.RegexUiHandlers;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyUpEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.List;

public class RegexViewImpl
        extends ViewWithUiHandlers<RegexUiHandlers>
        implements RegexView, GroupRow.Listener {

    private static final String GROUP = "data-group";

    private final Widget widget;
    private boolean enabled = true;

    @UiField
    TextArea pattern;
    @UiField
    HTML patternMap;
    @UiField
    Label error;
    @UiField
    Label explode;
    @UiField
    CustomCheckBox dotAll;
    @UiField
    CustomCheckBox caseInsensitive;
    @UiField
    TextBox advance;
    @UiField
    FlowPanel groups;
    @UiField
    Label noGroups;
    @UiField
    Label explain;

    @Inject
    public RegexViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        setError(null);
        setExplain(null);
        patternMap.addDomHandler(event -> {
            if (!Element.is(event.getNativeEvent().getEventTarget())) {
                return;
            }
            Element element = Element.as(event.getNativeEvent().getEventTarget());
            while (element != null && element != patternMap.getElement()) {
                if (element.hasAttribute(GROUP)) {
                    if (getUiHandlers() != null) {
                        getUiHandlers().onGroupSelect(Integer.parseInt(element.getAttribute(GROUP)));
                    }
                    return;
                }
                element = element.getParentElement();
            }
        }, ClickEvent.getType());
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @UiHandler("pattern")
    void onPatternKey(final KeyUpEvent e) {
        changed();
    }

    @UiHandler("pattern")
    void onPattern(final ValueChangeEvent<String> e) {
        changed();
    }

    @UiHandler("caseInsensitive")
    void onCaseInsensitive(final ValueChangeEvent<Boolean> e) {
        changed();
    }

    @UiHandler("dotAll")
    void onDotAll(final ValueChangeEvent<Boolean> e) {
        changed();
    }

    @UiHandler("advance")
    void onAdvance(final ValueChangeEvent<String> e) {
        changed();
    }

    @UiHandler("explode")
    void onExplode(final ClickEvent e) {
        if (enabled && getUiHandlers() != null) {
            getUiHandlers().onExplode();
        }
    }

    private void changed() {
        if (getUiHandlers() != null) {
            getUiHandlers().onChange();
        }
    }

    @Override
    public void onGroupName(final int index, final String name) {
        if (getUiHandlers() != null) {
            getUiHandlers().onGroupName(index, name);
        }
    }

    @Override
    public void onGroupSelect(final int index) {
        if (getUiHandlers() != null) {
            getUiHandlers().onGroupSelect(index);
        }
    }

    @Override
    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
        pattern.setEnabled(enabled);
        caseInsensitive.setEnabled(enabled);
        dotAll.setEnabled(enabled);
        advance.setEnabled(enabled);
        explode.setStyleDependentName("disabled", !enabled);
    }

    @Override
    public String getPattern() {
        return pattern.getText();
    }

    @Override
    public void setPattern(final String text) {
        pattern.setText(text);
    }

    @Override
    public boolean isCaseInsensitive() {
        return caseInsensitive.getValue();
    }

    @Override
    public void setCaseInsensitive(final boolean value) {
        caseInsensitive.setValue(value);
    }

    @Override
    public boolean isDotAll() {
        return dotAll.getValue();
    }

    @Override
    public void setDotAll(final boolean value) {
        dotAll.setValue(value);
    }

    @Override
    public int getAdvance() {
        try {
            return Integer.parseInt(advance.getText().trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void setAdvance(final int value) {
        advance.setText(String.valueOf(value));
    }

    @Override
    public void setError(final String text) {
        error.setText(text == null
                ? ""
                : text);
    }

    @Override
    public void setPatternMap(final SafeHtml html) {
        patternMap.setHTML(html);
    }

    @Override
    public void setGroups(final List<GroupRowData> data) {
        groups.clear();
        noGroups.setVisible(data.isEmpty());
        for (final GroupRowData row : data) {
            groups.add(new GroupRow(row, this, enabled));
        }
    }

    @Override
    public void setExplain(final String text) {
        explain.setText(text == null
                ? "—"
                : text);
    }

    public interface Binder extends UiBinder<Widget, RegexViewImpl> {

    }
}
