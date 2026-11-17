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

import stroom.shapeshifter.client.model.Mark;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextArea;
import stroom.widget.text.client.Html;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.event.dom.client.KeyUpEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

public class Sample extends Component {

    private Runnable onSample = () -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    TextArea sample;
    @UiField
    Label summary;
    @UiField
    Html matches;
    @UiField
    Html table;
    @UiField
    Label nothing;

    public Sample() {
        initWidget(BINDER.createAndBindUi(this));
        sample.addKeyUpHandler(event -> onSampleKey(event));
        sample.addValueChangeHandler(event -> onSampleChange(event));
        // Pointing at a match or a group lights it and quietens the rest, as it does in the
        // panes (design 18 §5.12.3). Lit from here rather than from a host: the workbench is
        // editing a pattern with no run behind it, so there is no cursor and no second surface
        // to tell — the pane answers its own pointer, which is the whole of what it can mean
        // here. A delegated handler rather than `:hover`, for the reasons mark.css records.
        matches.addMouseOverHandler(event -> {
            final EventTarget target = event.getNativeEvent().getEventTarget();
            Marks.light(matches.getElement(), Element.is(target)
                    ? Marks.hotOf(matches.getElement(), Element.as(target))
                    : null);
        });
        matches.addMouseOutHandler(event -> {
            // Leaving one span for another inside the block is not leaving the block.
            final EventTarget to = event.getRelatedTarget();
            if (to == null || !Element.is(to) || !matches.getElement().isOrHasChild(Element.as(to))) {
                Marks.light(matches.getElement(), null);
            }
        });
        showNothing("");
    }


    private void onSampleKey(final KeyUpEvent e) {
        changed();
    }

    private void onSampleChange(final ValueChangeEvent<String> e) {
        changed();
    }

    private void changed() {
        onSample.run();
    }

    public String getSample() {
        return sample.getText();
    }

    public void setSample(final String text) {
        sample.setText(text);
    }

    public void showMatches(final String text, final List<Mark> marks, final List<Row> rows, final String line) {
        summary.setText(line);
        matches.setHTML(Marks.render(text, marks));
        Marks.rest(matches.getElement());
        final SafeHtmlBuilder html = new SafeHtmlBuilder();
        if (!rows.isEmpty()) {
            html.appendHtmlConstant("<table class=\"sample__table\">");
            for (final Row row : rows) {
                html.appendHtmlConstant("<tr><td class=\"sample__number\">")
                        .appendEscaped(String.valueOf(row.index()))
                        .appendHtmlConstant("</td><td class=\"sample__at\">")
                        .appendEscaped(row.offset() + " +" + row.length())
                        .appendHtmlConstant("</td><td>");
                for (final Cell cell : row.cells()) {
                    html.appendHtmlConstant("<span class=\"sample__group" + (cell.isolated()
                            ? " sample__group--isolated"
                            : "") + "\" style=\"--hue:" + Colours.safe(cell.hue()) + "\">")
                            .appendHtmlConstant("<span class=\"sample__group-name\">")
                            .appendEscaped(cell.name() == null
                                    ? "$" + cell.group()
                                    : cell.name())
                            .appendHtmlConstant("</span>");
                    if (cell.value() == null) {
                        html.appendHtmlConstant("<span class=\"sample__group-none\">—</span>");
                    } else {
                        html.appendHtmlConstant("<span class=\"sample__group-value mono\">").appendEscaped(cell.value())
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

    public void showNothing(final String why) {
        nothing.setText(why);
        nothing.setVisible(!why.isEmpty());
        summary.setVisible(false);
        matches.setVisible(false);
        table.setVisible(false);
    }


    /// @param onSample Told when sample.
    public void setOnSample(final Runnable onSample) {
        this.onSample = onSample;
    }

    interface Binder extends UiBinder<Widget, Sample> {

    }

    /// One match: which, where, and its groups.
    ///
    /// @param index  Which match this is.
    /// @param offset Where it starts in the sample.
    /// @param length How long it is.
    /// @param cells  Its groups.
    public record Row(int index, int offset, int length, List<Cell> cells) {

    }

    /// One group of a match.
    ///
    /// @param group    Its number.
    /// @param name     Its name, or null if it has none.
    /// @param value    What it matched, or null where it took no part.
    /// @param hue      The colour it is painted in.
    /// @param isolated Whether it is shown on its own.
    public record Cell(int group, String name, String value, String hue, boolean isolated) {

    }

}
