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

import stroom.shapeshifter.client.presenter.PatternItem;
import stroom.widget.htree.client.CellRenderer2;
import stroom.widget.htree.client.treelayout.Bounds;
import stroom.widget.htree.client.treelayout.Dimension;
import stroom.widget.htree.client.treelayout.NodeExtentProvider;
import stroom.widget.htree.client.treelayout.TreeLayout;

import com.google.gwt.dom.client.Style;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;

import java.util.ArrayList;
import java.util.List;

/**
 * A pattern node drawn as a box the tree layout places (the expression editor's
 * {@code ExpressionItemRenderer}, for this vocabulary): a container is a short box — the
 * combinator's word — and a leaf a wider one holding what it matches, so the shape of a
 * composition is read across the boxes rather than down an indent.
 */
public final class PatternItemRenderer implements CellRenderer2<PatternItem>, NodeExtentProvider<PatternItem> {

    /** As the expression editor's boxes: one row of text. */
    private static final double HEIGHT = 25;
    private static final int CONTAINER_WIDTH = 96;
    private static final int LEAF_MIN_WIDTH = 120;
    private static final int LEAF_MAX_WIDTH = 380;
    /** Near enough for the monospaced-ish label at this size; the box itself wraps nothing. */
    private static final int CHAR_WIDTH = 7;
    private static final int PADDING = 22;

    private final FlowPanel panel;
    private final List<PatternItemBox> boxes = new ArrayList<>();
    private Listener listener;
    private String selected;

    public PatternItemRenderer(final FlowPanel panel) {
        this.panel = panel;
    }

    public void setListener(final Listener listener) {
        this.listener = listener;
    }

    public void setSelected(final String path) {
        this.selected = path;
    }

    @Override
    public void render(final TreeLayout<PatternItem> treeLayout, final Bounds bounds, final PatternItem item) {
        final double x = bounds.getX();
        final double y = bounds.getY() + ((bounds.getHeight() - HEIGHT) / 2);

        final PatternItemBox box = new PatternItemBox(item);
        final Style style = box.getElement().getStyle();
        style.setLeft(x, Unit.PX);
        style.setTop(y, Unit.PX);

        final Label label = new Label(item.getLabel(), false);
        label.addStyleName("expressionItemBox-label ss-node-label");
        if (item.isContainer()) {
            label.addStyleName("ss-node-label--container");
        }
        if (item.isLabelled()) {
            label.addStyleName("ss-node-label--labelled");
        }
        label.setTitle(item.getLabel());

        final FlowPanel inner = new FlowPanel();
        inner.setStyleName("termEditor-inner");
        inner.add(label);
        final FlowPanel layout = new FlowPanel();
        layout.setStyleName("termEditor-outer");
        layout.add(inner);
        box.setInnerWidget(layout);
        box.setSelected(item.getPath().equals(selected));

        box.addDomHandler(event -> {
            if (listener != null) {
                listener.onSelect(item.getPath());
            }
        }, com.google.gwt.event.dom.client.ClickEvent.getType());
        box.addDomHandler(event -> {
            if (listener != null) {
                listener.onOpen(item.getPath());
            }
        }, com.google.gwt.event.dom.client.DoubleClickEvent.getType());

        panel.add(box);
        boxes.add(box);
    }

    @Override
    public Dimension getExtents(final PatternItem item) {
        if (item.isContainer()) {
            return new Dimension(CONTAINER_WIDTH, HEIGHT);
        }
        final int width = Math.min(LEAF_MAX_WIDTH,
                Math.max(LEAF_MIN_WIDTH, item.getLabel().length() * CHAR_WIDTH + PADDING));
        return new Dimension(width, HEIGHT);
    }

    public void clear() {
        boxes.clear();
    }

    public List<PatternItemBox> getBoxes() {
        return boxes;
    }

    /** What a click on a box asks for. */
    public interface Listener {

        void onSelect(String path);

        void onOpen(String path);
    }
}
