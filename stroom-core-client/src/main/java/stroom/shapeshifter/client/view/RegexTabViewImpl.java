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

import stroom.shapeshifter.client.presenter.RegexTabPresenter.RegexTabView;
import stroom.shapeshifter.client.presenter.RegexTabUiHandlers;
import stroom.shapeshifter.shared.ShapeshifterPatternInfo.Group;
import stroom.widget.button.client.Button;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyUpEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.List;

public class RegexTabViewImpl
        extends ViewWithUiHandlers<RegexTabUiHandlers>
        implements RegexTabView {

    private final Widget widget;

    @UiField
    TextArea pattern;
    @UiField
    CustomCheckBox caseInsensitive;
    @UiField
    CustomCheckBox dotAll;
    @UiField
    TextBox advance;
    @UiField
    Button explode;
    @UiField
    Label error;
    @UiField
    HTML groups;
    @UiField
    Label explain;

    @Inject
    public RegexTabViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        setInfo(null, List.of(), null);
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
        if (getUiHandlers() != null) {
            getUiHandlers().onExplode();
        }
    }

    private void changed() {
        if (getUiHandlers() != null) {
            getUiHandlers().onChange();
        }
    }

    @Override
    public void setEnabled(final boolean enabled) {
        pattern.setEnabled(enabled);
        caseInsensitive.setEnabled(enabled);
        dotAll.setEnabled(enabled);
        advance.setEnabled(enabled);
        explode.setEnabled(enabled);
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
    public void setInfo(final String errorText, final List<Group> groupList, final String explainText) {
        error.setText(errorText == null
                ? ""
                : errorText);
        error.setVisible(errorText != null);
        final SafeHtmlBuilder sb = new SafeHtmlBuilder();
        if (groupList.isEmpty()) {
            sb.appendHtmlConstant("<div class=\"shapeshifter-hint\">No capture groups</div>");
        } else {
            sb.appendHtmlConstant("<table class=\"shapeshifter-groups\">");
            for (final Group group : groupList) {
                sb.appendHtmlConstant("<tr><td class=\"shapeshifter-group-index\">$")
                        .append(group.getIndex())
                        .appendHtmlConstant("</td><td>")
                        .appendEscaped(group.getName() == null
                                ? "(unnamed)"
                                : group.getName())
                        .appendHtmlConstant("</td></tr>");
            }
            sb.appendHtmlConstant("</table>");
        }
        groups.setHTML(sb.toSafeHtml());
        explain.setText(explainText == null
                ? ""
                : explainText);
    }

    public interface Binder extends UiBinder<Widget, RegexTabViewImpl> {

    }
}
