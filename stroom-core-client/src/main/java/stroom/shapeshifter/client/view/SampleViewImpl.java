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

import stroom.shapeshifter.client.presenter.Mark;
import stroom.shapeshifter.client.presenter.SamplePresenter.SampleView;
import stroom.shapeshifter.client.presenter.SampleUiHandlers;

import com.google.gwt.event.dom.client.KeyUpEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.List;

public class SampleViewImpl
        extends ViewWithUiHandlers<SampleUiHandlers>
        implements SampleView {

    private final Widget widget;

    @UiField
    TextArea sample;
    @UiField
    Label summary;
    @UiField
    HTML matches;
    @UiField
    HTML table;
    @UiField
    Label nothing;

    @Inject
    public SampleViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        showNothing("");
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @UiHandler("sample")
    void onSampleKey(final KeyUpEvent e) {
        changed();
    }

    @UiHandler("sample")
    void onSampleChange(final ValueChangeEvent<String> e) {
        changed();
    }

    private void changed() {
        if (getUiHandlers() != null) {
            getUiHandlers().onSample();
        }
    }

    @Override
    public String getSample() {
        return sample.getText();
    }

    @Override
    public void setSample(final String text) {
        sample.setText(text);
    }

    @Override
    public void showMatches(final String text, final List<Mark> marks, final List<Row> rows, final String line) {
        summary.setText(line);
        matches.setHTML(Marks.render(text, marks));
        final SafeHtmlBuilder html = new SafeHtmlBuilder();
        if (!rows.isEmpty()) {
            html.appendHtmlConstant("<table class=\"ss-smp-table\">");
            for (final Row row : rows) {
                html.appendHtmlConstant("<tr><td class=\"ss-smp-n\">")
                        .appendEscaped(String.valueOf(row.index()))
                        .appendHtmlConstant("</td><td class=\"ss-smp-at\">")
                        .appendEscaped(row.offset() + " +" + row.length())
                        .appendHtmlConstant("</td><td class=\"ss-smp-groups\">");
                for (final Cell cell : row.cells()) {
                    html.appendHtmlConstant("<span class=\"ss-smp-g" + (cell.isolated()
                            ? " ss-smp-g--on"
                            : "") + "\" style=\"--hue:" + Colours.safe(cell.hue()) + "\"><span class=\"ss-smp-gname\">")
                            .appendEscaped(cell.name() == null
                                    ? "$" + cell.group()
                                    : cell.name())
                            .appendHtmlConstant("</span>");
                    if (cell.value() == null) {
                        html.appendHtmlConstant("<span class=\"ss-smp-gnone\">—</span>");
                    } else {
                        html.appendHtmlConstant("<span class=\"ss-smp-gval mono\">").appendEscaped(cell.value())
                                .appendHtmlConstant("</span>");
                    }
                    html.appendHtmlConstant("</span>");
                }
                html.appendHtmlConstant("</td></tr>");
            }
            html.appendHtmlConstant("</table>");
        }
        table.setHTML(html.toSafeHtml());
        nothing.setVisible(false);
        summary.setVisible(true);
        matches.setVisible(true);
        table.setVisible(!rows.isEmpty());
    }

    @Override
    public void showNothing(final String why) {
        nothing.setText(why);
        nothing.setVisible(!why.isEmpty());
        summary.setVisible(false);
        matches.setVisible(false);
        table.setVisible(false);
    }

    public interface Binder extends UiBinder<Widget, SampleViewImpl> {

    }
}
