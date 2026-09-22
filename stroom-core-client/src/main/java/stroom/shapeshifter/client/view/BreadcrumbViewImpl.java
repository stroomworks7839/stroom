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

import stroom.shapeshifter.client.presenter.BreadcrumbPresenter.BreadcrumbView;
import stroom.shapeshifter.client.presenter.BreadcrumbPresenter.Segment;
import stroom.shapeshifter.client.presenter.BreadcrumbUiHandlers;
import stroom.shapeshifter.client.presenter.Hot;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.InlineSvgButton;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Anchor;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.InlineLabel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The crumb as the mockup draws it: name › name › name, each a click, the current one plain;
 * a within-parent stepper on every segment but the document's; the whole-input stepper at
 * the far end, labelled with the template so it cannot be read as another sibling stepper.
 */
public class BreadcrumbViewImpl extends ViewWithUiHandlers<BreadcrumbUiHandlers> implements BreadcrumbView {

    private final Widget widget;

    @UiField
    FlowPanel segments;
    @UiField
    FlowPanel stepper;
    @UiField
    Label stepperLabel;
    @UiField
    InlineSvgButton stepFirst;
    @UiField
    InlineSvgButton stepPrev;
    @UiField
    InlineSvgButton stepNext;
    @UiField
    InlineSvgButton stepLast;
    @UiField
    Label stepperIndex;
    @UiField
    Label state;

    private final Map<Long, Anchor> names = new HashMap<>();
    private Anchor lit;

    @Inject
    public BreadcrumbViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        // The stepping tab's own buttons and titles, for the same gesture over matches.
        stepFirst.setSvg(SvgImage.FAST_BACKWARD);
        stepFirst.setTitle("First match");
        stepPrev.setSvg(SvgImage.STEP_BACKWARD);
        stepPrev.setTitle("Previous match (Alt+Shift+←)");
        stepNext.setSvg(SvgImage.STEP_FORWARD);
        stepNext.setTitle("Next match (Alt+Shift+→)");
        stepLast.setSvg(SvgImage.FAST_FORWARD);
        stepLast.setTitle("Last match");
        stepFirst.addClickHandler(event -> getUiHandlers().onStepTo(0));
        stepPrev.addClickHandler(event -> getUiHandlers().onStep(-1));
        stepNext.addClickHandler(event -> getUiHandlers().onStep(1));
        stepLast.addClickHandler(event -> getUiHandlers().onStepTo(-1));
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setSegments(final List<Segment> list) {
        segments.clear();
        names.clear();
        for (int i = 0; i < list.size(); i++) {
            final Segment segment = list.get(i);
            if (i > 0) {
                final InlineLabel sep = new InlineLabel("›");
                sep.addStyleName("ss-crumb-sep");
                segments.add(sep);
            }
            final FlowPanel seg = new FlowPanel();
            seg.addStyleName("ss-crumb-seg");
            final Anchor name = new Anchor(segment.getLabel());
            name.addStyleName("ss-crumb-name");
            if (segment.getColour() != null) {
                name.getElement().getStyle().setProperty("borderBottomColor", segment.getColour());
            }
            if (i == list.size() - 1) {
                name.addStyleName("ss-crumb-name--current");
            }
            name.addClickHandler(event -> getUiHandlers().onSegment(segment.getFrameId()));
            final Hot hot = Hot.frame(segment.getFrameId(), segment.getTemplateId());
            name.addMouseOverHandler(event -> getUiHandlers().onHover(hot));
            name.addMouseOutHandler(event -> getUiHandlers().onHover(null));
            names.put(segment.getFrameId(), name);
            seg.add(name);
            if (segment.getCount() > 0) {
                final Anchor prev = arrow("◀", segment.getIndex() > 1);
                prev.addClickHandler(event -> getUiHandlers().onSibling(segment.getFrameId(), -1));
                final InlineLabel index = new InlineLabel(segment.getIndex() + "/" + segment.getCount());
                index.addStyleName("ss-crumb-idx");
                final Anchor next = arrow("▶", segment.getIndex() < segment.getCount());
                next.addClickHandler(event -> getUiHandlers().onSibling(segment.getFrameId(), 1));
                seg.add(prev);
                seg.add(index);
                seg.add(next);
            }
            segments.add(seg);
        }
    }

    private static Anchor arrow(final String glyph, final boolean enabled) {
        final Anchor anchor = new Anchor(glyph);
        anchor.addStyleName("ss-crumb-arrow");
        if (!enabled) {
            anchor.addStyleName("ss-crumb-arrow--off");
        }
        return anchor;
    }

    @Override
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

    @Override
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

    @Override
    public void setState(final String text) {
        state.setText(text == null
                ? ""
                : text);
        state.setVisible(text != null);
    }

    public interface Binder extends UiBinder<Widget, BreadcrumbViewImpl> {

    }
}
