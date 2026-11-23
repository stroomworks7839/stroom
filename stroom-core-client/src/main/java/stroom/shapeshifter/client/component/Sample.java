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
import stroom.widget.layout.client.SplitPane;
import stroom.widget.text.client.Html;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.Duration;
import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyUpEvent;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

public class Sample extends Component {

    // How soon, and how near, a second click has to be to make a double click: the common
    // desktop default, and a few pixels of a hand's tremor
    private static final double DOUBLE_CLICK_MS = 500;
    private static final int DOUBLE_CLICK_SLOP = 4;

    private Runnable onSample = () -> {
    };
    private IntConsumer onGroupPoint = group -> {
    };
    private BiConsumer<Long, Integer> onGroupPin = (frame, group) -> {
    };
    // Where and when the last click on the marked sample was, so that a second one is known for
    // the second half of a double click whatever the first did to the marks under it
    private double lastClickAt;
    private int lastClickX;
    private int lastClickY;
    // The marked sample as last set, so that the same marks are not set again
    private SafeHtml shownMarks;

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    SplitPane column;
    @UiField
    CollapsiblePane samplePane;
    @UiField
    CollapsiblePane detailPane;
    @UiField
    FocusPanel read;
    @UiField
    TextArea sample;
    @UiField
    Html matches;
    @UiField
    Label nothing;
    @UiField
    Html table;
    @UiField
    Label noDetail;

    public Sample() {
        initWidget(BINDER.createAndBindUi(this));
        new FoldingPanes(column)
                .add(samplePane, "*")
                .add(detailPane, "30%");
        sample.addKeyUpHandler(event -> onSampleKey(event));
        sample.addValueChangeHandler(event -> onSampleChange(event));
        // The sample marked, at rest, opens to be edited: double-clicked, or Enter on it, or
        // clicked while there is nothing in it. A click on a mark selects what it is of, as a
        // click on its row does everywhere else (ruled 2026-11-20), and a double click selects it
        // and then opens, as a double click on a file does. Leaving the field, or Escape in it,
        // marks it again; Escape goes no further, as it would close the workbench.
        read.getElement().setAttribute("role", "region");
        read.getElement().setAttribute("aria-label", "Sample, its matches marked: Enter to edit it");
        read.setTitle("Seeded from the selected match. Click a mark to keep its group shown; double-click, "
                      + "or Enter, to edit the sample: this never runs the document.");
        // Counted here rather than left to the browser's dblclick: the first click can select a
        // tree's node, which draws other marks, and the browser does not count a second click on
        // an element that was not there for the first
        read.addClickHandler(event -> {
            final NativeEvent click = event.getNativeEvent();
            if (secondClick(click.getClientX(), click.getClientY())) {
                edit();
                return;
            }
            final Element mark = markAt(click.getEventTarget());
            if (mark != null) {
                onGroupPin.accept(frameOf(mark), groupOf(mark));
            } else if (sample.getText().isEmpty()) {
                edit();
            }
        });
        read.addKeyDownHandler(event -> {
            if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
                event.preventDefault();
                edit();
            }
        });
        sample.addBlurHandler(event -> read());
        sample.addKeyDownHandler(event -> {
            if (event.getNativeKeyCode() == KeyCodes.KEY_ESCAPE) {
                event.preventDefault();
                event.stopPropagation();
                read();
                read.setFocus(true);
            }
        });
        // Pointing at a group's rule shows that group alone, in every match, and says which it is
        // so that the pattern and the groups list show it too (ruled 2026-11-20): by group rather
        // than by the one span, as the Design tab's panes light, because here a group is what the
        // author is asking about. A delegated handler rather than `:hover`, for the reasons
        // mark.css records.
        matches.addMouseOverHandler(event -> onGroupPoint.accept(groupAt(event.getNativeEvent().getEventTarget())));
        matches.addMouseOutHandler(event -> {
            // Leaving one span for another inside the block is not leaving the block.
            final EventTarget to = event.getRelatedTarget();
            if (to == null || !Element.is(to) || !matches.getElement().isOrHasChild(Element.as(to))) {
                onGroupPoint.accept(RegexController.NO_GROUP);
            }
        });
        showNothing("");
    }


    // The field in place of the marked sample, with the keyboard in it
    private void edit() {
        read.setVisible(false);
        sample.setVisible(true);
        sample.setFocus(true);
    }

    // The marked sample in place of the field
    private void read() {
        sample.setVisible(false);
        read.setVisible(true);
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

    /// Shows what the match found in the sample.
    ///
    /// @param text  The sample.
    /// @param marks Where the matches and their groups are in it.
    /// @param rows  Each match's place and groups.
    /// @param line  How many matched and where it was tried, said on the matches' title bar.
    public void showMatches(final String text, final List<Mark> marks, final List<Row> rows, final String line) {
        samplePane.setNote(line);
        final SafeHtml marked = Marks.render(text, marks);
        // Left alone when nothing in it moved: a group kept shown is a class on the marks, not
        // new marks, and replacing the spans under a click would cost a double click its second
        // half, which has to land on the element the first did
        if (!marked.equals(shownMarks)) {
            shownMarks = marked;
            matches.setHTML(marked);
            Marks.rest(matches.getElement());
        }
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
        showUnmarked(text);
        table.setVisible(!rows.isEmpty());
        noDetail.setText("Nothing matched.");
        noDetail.setVisible(rows.isEmpty());
    }

    /// Shows that there is nothing to try, and why: the sample unmarked, and the reason under it.
    ///
    /// @param why What to say under the sample, or nothing.
    public void showNothing(final String why) {
        samplePane.setNote(null);
        shownMarks = Marks.render(sample.getText(), List.of());
        matches.setHTML(shownMarks);
        showUnmarked(sample.getText());
        if (!why.isEmpty()) {
            nothing.setText(why);
            nothing.setVisible(true);
        }
        table.setVisible(false);
        noDetail.setVisible(false);
    }

    // The sample shown, and in place of it, if it is empty, what to do about that
    private void showUnmarked(final String text) {
        final boolean empty = text == null || text.isEmpty();
        matches.setVisible(!empty);
        nothing.setText(empty
                ? "No sample. Click to type or paste one."
                : "");
        nothing.setVisible(empty);
    }


    /// Shows one group alone, in every match: its rule, and the others' taken away from over it,
    /// so that its whole extent shows; or, with [RegexController#NO_GROUP], every group, as at
    /// rest. `0` is the whole match.
    ///
    /// @param group The group.
    public void lightGroup(final int group) {
        final Element block = matches.getElement();
        final NodeList<Element> spans = block.getElementsByTagName("span");
        boolean any = false;
        for (int i = 0; i < spans.getLength(); i++) {
            final Element span = spans.getItem(i);
            final boolean lit = group != RegexController.NO_GROUP && groupOf(span) == group;
            if (lit) {
                span.addClassName(Marks.HOT_CLASS);
                any = true;
            } else {
                span.removeClassName(Marks.HOT_CLASS);
            }
        }
        if (any) {
            block.addClassName(Marks.POINTING_CLASS);
        } else {
            block.removeClassName(Marks.POINTING_CLASS);
        }
    }

    // The group of the innermost mark the element is in: a capture's own, 0 for a match, or
    // NO_GROUP outside every mark
    private int groupAt(final EventTarget target) {
        final Element mark = markAt(target);
        return mark == null
                ? RegexController.NO_GROUP
                : groupOf(mark);
    }

    // The innermost mark of a group the element is in, or null outside every one
    private Element markAt(final EventTarget target) {
        if (!Element.is(target)) {
            return null;
        }
        Element at = Element.as(target);
        while (at != null && at != matches.getElement()) {
            if (groupOf(at) != RegexController.NO_GROUP) {
                return at;
            }
            at = at.getParentElement();
        }
        return null;
    }

    // A mark's frame: a capture's is before the colon of its frame:index, and a match's its own
    private static long frameOf(final Element mark) {
        final String capture = mark.getAttribute(Marks.CAPTURE_ATTR);
        return capture != null && !capture.isEmpty()
                ? Long.parseLong(capture.substring(0, capture.indexOf(':')))
                : Long.parseLong(mark.getAttribute(Marks.FRAME_ATTR));
    }

    // Whether a click is the second of a double click: soon after the last, and where it was
    private boolean secondClick(final int x, final int y) {
        final double now = Duration.currentTimeMillis();
        final boolean second = now - lastClickAt <= DOUBLE_CLICK_MS
                               && Math.abs(x - lastClickX) <= DOUBLE_CLICK_SLOP
                               && Math.abs(y - lastClickY) <= DOUBLE_CLICK_SLOP;
        // A third click starts again rather than opening twice
        lastClickAt = second
                ? 0
                : now;
        lastClickX = x;
        lastClickY = y;
        return second;
    }

    // A mark's group: a capture's is after the colon of its frame:index, and a match is $0
    private static int groupOf(final Element span) {
        final String capture = span.getAttribute(Marks.CAPTURE_ATTR);
        if (capture != null && !capture.isEmpty()) {
            return Integer.parseInt(capture.substring(capture.indexOf(':') + 1));
        }
        final String frame = span.getAttribute(Marks.FRAME_ATTR);
        return frame != null && !frame.isEmpty()
                ? 0
                : RegexController.NO_GROUP;
    }

    /// @param onGroupPoint Told the group the pointer is on, `0` for a match outside any
    ///                     capturing group, and [RegexController#NO_GROUP] when it leaves.
    public void setOnGroupPoint(final IntConsumer onGroupPoint) {
        this.onGroupPoint = onGroupPoint;
    }

    /// Told when a mark is clicked, which selects what it is of.
    ///
    /// @param onGroupPin Told the frame of the mark clicked and its group, `0` for a match outside
    ///                   any capturing group.
    public void setOnGroupPin(final BiConsumer<Long, Integer> onGroupPin) {
        this.onGroupPin = onGroupPin;
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
