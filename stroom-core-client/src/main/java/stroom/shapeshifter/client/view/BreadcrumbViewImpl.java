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
import stroom.svg.client.Preset;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;

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
    Anchor back;
    @UiField
    Anchor forward;
    @UiField
    FlowPanel segments;
    @UiField
    FlowPanel stepper;
    @UiField
    Label stepperLabel;
    @UiField
    Anchor stepPrev;
    @UiField
    Label stepperIndex;
    @UiField
    Anchor stepNext;
    @UiField
    Label state;
    @UiField
    ButtonPanel buttons;

    private final Map<Long, Anchor> names = new HashMap<>();
    private Anchor lit;

    @Inject
    public BreadcrumbViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        back.addClickHandler(event -> getUiHandlers().onHistory(-1));
        forward.addClickHandler(event -> getUiHandlers().onHistory(1));
        stepPrev.addClickHandler(event -> getUiHandlers().onStep(-1));
        stepNext.addClickHandler(event -> getUiHandlers().onStep(1));
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public ButtonView addButton(final Preset preset) {
        return buttons.addButton(preset);
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
    public void setStepper(final String templateName, final int index, final int count) {
        stepper.setVisible(templateName != null);
        if (templateName != null) {
            stepperLabel.setText("matches of " + templateName);
            stepperIndex.setText(index + "/" + count);
            stepPrev.setStyleName("ss-crumb-arrow--off", index <= 1);
            stepNext.setStyleName("ss-crumb-arrow--off", index >= count);
        }
    }

    @Override
    public void setHistory(final boolean canBack, final boolean canForward) {
        back.setStyleName("ss-crumb-arrow--off", !canBack);
        forward.setStyleName("ss-crumb-arrow--off", !canForward);
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
