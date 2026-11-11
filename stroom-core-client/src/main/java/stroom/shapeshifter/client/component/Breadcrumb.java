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

import stroom.shapeshifter.client.component.BreadcrumbController.Segment;
import stroom.shapeshifter.client.model.Hot;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.LinkButton;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.InlineLabel;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The crumb as the mockup draws it: name › name › name, each a click, the current one plain;
 * a within-parent stepper on every segment but the document's; the whole-input stepper at
 * the far end, labelled with the template so it cannot be read as another sibling stepper.
 */
public class Breadcrumb extends Component {

    private Consumer<Long> onSegment = frameId -> {
    };
    private BiConsumer<Long, Integer> onSibling = (frameId, delta) -> {
    };
    private IntConsumer onStep = delta -> {
    };
    private IntConsumer onStepTo = which -> {
    };
    private Consumer<Hot> onHover = hot -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    FlowPanel segments;
    @UiField
    FlowPanel stepper;
    @UiField
    Label stepperLabel;
    @UiField
    ToolbarButton stepFirst;
    @UiField
    ToolbarButton stepPrev;
    @UiField
    ToolbarButton stepNext;
    @UiField
    ToolbarButton stepLast;
    @UiField
    Label stepperIndex;
    @UiField
    Label state;

    private final Map<Long, LinkButton> names = new HashMap<>();
    private LinkButton lit;

    public Breadcrumb() {
        initWidget(BINDER.createAndBindUi(this));
        // The stepping tab's own buttons and titles, for the same gesture over matches.
        stepFirst.setSvg(SvgImage.FAST_BACKWARD);
        stepFirst.setTitle("First match");
        stepPrev.setSvg(SvgImage.STEP_BACKWARD);
        stepPrev.setTitle("Previous match (Alt+Shift+←)");
        stepNext.setSvg(SvgImage.STEP_FORWARD);
        stepNext.setTitle("Next match (Alt+Shift+→)");
        stepLast.setSvg(SvgImage.FAST_FORWARD);
        stepLast.setTitle("Last match");
        stepFirst.addClickHandler(event -> onStepTo.accept(0));
        stepPrev.addClickHandler(event -> onStep.accept(-1));
        stepNext.addClickHandler(event -> onStep.accept(1));
        stepLast.addClickHandler(event -> onStepTo.accept(-1));
    }


    public void setSegments(final List<Segment> list) {
        segments.clear();
        names.clear();
        for (int i = 0; i < list.size(); i++) {
            final Segment segment = list.get(i);
            if (i > 0) {
                final InlineLabel sep = new InlineLabel("›");
                sep.addStyleName("breadcrumb__separator");
                segments.add(sep);
            }
            final FlowPanel seg = new FlowPanel();
            seg.addStyleName("breadcrumb__segment");
            final LinkButton name = new LinkButton(segment.getLabel());
            name.addStyleName("breadcrumb__name");
            if (segment.getColour() != null) {
                name.getElement().getStyle().setProperty("borderBottomColor", segment.getColour());
            }
            if (i == list.size() - 1) {
                name.addStyleName("breadcrumb__name--current");
            }
            name.addClickHandler(event -> onSegment.accept(segment.getFrameId()));
            final Hot hot = Hot.frame(segment.getFrameId(), segment.getTemplateId());
            name.addMouseOverHandler(event -> onHover.accept(hot));
            name.addMouseOutHandler(event -> onHover.accept(null));
            names.put(segment.getFrameId(), name);
            seg.add(name);
            if (segment.getCount() > 0) {
                final LinkButton prev = arrow("◀", segment.getIndex() > 1);
                prev.addClickHandler(event -> onSibling.accept(segment.getFrameId(), -1));
                final InlineLabel index = new InlineLabel(segment.getIndex() + "/" + segment.getCount());
                index.addStyleName("breadcrumb__index");
                final LinkButton next = arrow("▶", segment.getIndex() < segment.getCount());
                next.addClickHandler(event -> onSibling.accept(segment.getFrameId(), 1));
                seg.add(prev);
                seg.add(index);
                seg.add(next);
            }
            segments.add(seg);
        }
    }

    private static LinkButton arrow(final String glyph, final boolean enabled) {
        final LinkButton anchor = new LinkButton(glyph);
        anchor.addStyleName("breadcrumb__arrow");
        if (!enabled) {
            anchor.addStyleName("breadcrumb__arrow--off");
        }
        return anchor;
    }

    public void setStepper(final String label, final int index, final int count) {
        stepper.setVisible(label != null);
        if (label != null) {
            stepperLabel.setText(label);
            stepperIndex.setText((index == 0
                    ? "–"
                    : String.valueOf(index)) + "/" + count);
            stepperIndex.setTitle(count == 0
                    ? "Nothing matched"
                    : "Which match the cursor is on, of how many");
            stepFirst.setEnabled(index > 1);
            stepPrev.setEnabled(index > 1);
            stepNext.setEnabled(index < count);
            stepLast.setEnabled(index < count);
        }
    }

    public void setHot(final long frameId) {
        if (lit != null) {
            lit.removeStyleName(Marks.HOT_CLASS);
            lit = null;
        }
        lit = names.get(frameId);
        if (lit != null) {
            lit.addStyleName(Marks.HOT_CLASS);
        }
    }

    public void setState(final String text) {
        state.setText(text == null
                ? ""
                : text);
        state.setVisible(text != null);
    }


    /// @param onSegment Told when segment.
    public void setOnSegment(final Consumer<Long> onSegment) {
        this.onSegment = onSegment;
    }

    /// @param onSibling Told when sibling.
    public void setOnSibling(final BiConsumer<Long, Integer> onSibling) {
        this.onSibling = onSibling;
    }

    /// @param onStep Told when step.
    public void setOnStep(final IntConsumer onStep) {
        this.onStep = onStep;
    }

    /// @param onStepTo Told when stepto.
    public void setOnStepTo(final IntConsumer onStepTo) {
        this.onStepTo = onStepTo;
    }

    /// @param onHover Told when hover.
    public void setOnHover(final Consumer<Hot> onHover) {
        this.onHover = onHover;
    }

    interface Binder extends UiBinder<Widget, Breadcrumb> {

    }
}
