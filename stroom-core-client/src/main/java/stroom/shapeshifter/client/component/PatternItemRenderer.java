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

import stroom.shapeshifter.client.model.PatternItem;
import stroom.widget.form.client.TextBox;
import stroom.widget.htree.client.CellRenderer;
import stroom.widget.htree.client.treelayout.Bounds;
import stroom.widget.htree.client.treelayout.Dimension;
import stroom.widget.htree.client.treelayout.NodeExtentProvider;
import stroom.widget.htree.client.treelayout.TreeLayout;
import stroom.widget.text.client.Label;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.dom.client.Style;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.MouseOutEvent;
import com.google.gwt.event.dom.client.MouseOverEvent;
import com.google.gwt.user.client.ui.FlowPanel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/// A pattern node drawn as a box the tree layout places (the expression editor's
/// `ExpressionItemRenderer`, for this vocabulary): a container is a short box — the
/// combinator's word — and a leaf a wider one holding what it matches, so the shape of a
/// composition is read across the boxes rather than down an indent.
public final class PatternItemRenderer implements CellRenderer<PatternItem>, NodeExtentProvider<PatternItem> {

    /// As the expression editor's boxes: one row of text.
    private static final double HEIGHT = 25;
    private static final int CONTAINER_WIDTH = 96;
    private static final int LEAF_MIN_WIDTH = 120;
    private static final int LEAF_MAX_WIDTH = 380;
    /// Near enough for the monospaced-ish label at this size; the box itself wraps nothing.
    private static final int CHAR_WIDTH = 7;
    private static final int PADDING = 22;

    private final FlowPanel panel;
    private final List<PatternItemBox> boxes = new ArrayList<>();
    private Consumer<String> onSelect = path -> {
    };
    private Consumer<String> onOpen = path -> {
    };
    private Consumer<String> onPoint = path -> {
    };
    private String selected;
    private String stopped;
    // Each box's label holder, by its node's path: where a field takes the label's place
    private final Map<String, FlowPanel> labels = new HashMap<>();

    public PatternItemRenderer(final FlowPanel panel) {
        this.panel = panel;
    }

    /// Told when a node is chosen, with its path.
    ///
    /// @param onSelect The handler.
    public void setOnSelect(final Consumer<String> onSelect) {
        this.onSelect = onSelect;
    }

    /// Told when a node is opened, with its path.
    ///
    /// @param onOpen The handler.
    public void setOnOpen(final Consumer<String> onOpen) {
        this.onOpen = onOpen;
    }

    public void setSelected(final String path) {
        this.selected = path;
    }

    /// Selects a box among those drawn, without drawing them again: a box redrawn under the
    /// pointer between the two clicks of a double click never hears the double click.
    ///
    /// @param path The node's path.
    public void select(final String path) {
        this.selected = path;
        for (final PatternItemBox box : boxes) {
            box.setSelected(box.getItem().getPath().equals(path));
        }
    }

    /// Told when the pointer comes onto a box, with its path, and when it leaves, with null.
    ///
    /// @param onPoint The handler.
    public void setOnPoint(final Consumer<String> onPoint) {
        this.onPoint = onPoint;
    }

    /// @param path The node a trial that matched nowhere stopped at, marked; null for none.
    public void setStopped(final String path) {
        this.stopped = path;
    }

    /// Edits a box's text in place: a field in its label's place, holding the text, with the
    /// keyboard in it. Enter or leaving it takes it; Escape puts the label back, and goes no
    /// further, as it would close the workbench.
    ///
    /// @param path The box's node.
    /// @param text The text to start from.
    /// @param onTake Given the text taken.
    /// @param onCancel Told when it is put back.
    /// @return Whether there was such a box to edit.
    public boolean edit(final String path, final String text, final Consumer<String> onTake,
                        final Runnable onCancel) {
        final FlowPanel holder = labels.get(path);
        if (holder == null) {
            return false;
        }
        final TextBox field = new TextBox();
        field.addStyleName("pattern-tree__node-field");
        field.setText(text);
        field.getElement().setAttribute("aria-label", "Node text");
        final boolean[] done = {false};
        field.addKeyDownHandler(event -> {
            if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
                event.preventDefault();
                done[0] = true;
                onTake.accept(field.getText());
            } else if (event.getNativeKeyCode() == KeyCodes.KEY_ESCAPE) {
                event.preventDefault();
                event.stopPropagation();
                done[0] = true;
                onCancel.run();
            }
        });
        field.addBlurHandler(event -> {
            if (!done[0]) {
                done[0] = true;
                onTake.accept(field.getText());
            }
        });
        // A click in the field is the field's, not the box's choice
        field.addMouseDownHandler(event -> event.stopPropagation());
        holder.clear();
        holder.add(field);
        field.setFocus(true);
        field.selectAll();
        return true;
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
        label.addStyleName("pattern-tree__node-label");
        if (item.isContainer()) {
            label.addStyleName("pattern-tree__node-label--container");
        }
        final boolean stoppedHere = item.getPath().equals(stopped);
        if (stoppedHere) {
            label.addStyleName("pattern-tree__node-label--stopped");
        }
        label.setTitle(stoppedHere
                ? item.getLabel() + " — matching stopped here: the nodes before it matched, this did not"
                : item.getLabel());

        final FlowPanel inner = new FlowPanel();
        inner.setStyleName("expression-item__label");
        inner.add(label);
        labels.put(item.getPath(), inner);
        final FlowPanel layout = new FlowPanel();
        layout.setStyleName("expression-item__row");
        layout.add(inner);
        box.setInnerWidget(layout);
        box.setSelected(item.getPath().equals(selected));

        box.addDomHandler(event -> {
            onSelect.accept(item.getPath());
        }, com.google.gwt.event.dom.client.ClickEvent.getType());
        box.addDomHandler(event -> {
            onOpen.accept(item.getPath());
        }, com.google.gwt.event.dom.client.DoubleClickEvent.getType());
        // Pointing at a node marks what it matched in the sample (design 44 §4a)
        box.addDomHandler(event -> onPoint.accept(item.getPath()), MouseOverEvent.getType());
        box.addDomHandler(event -> {
            final EventTarget to = event.getRelatedTarget();
            if (to == null || !Element.is(to) || !box.getElement().isOrHasChild(Element.as(to))) {
                onPoint.accept(null);
            }
        }, MouseOutEvent.getType());

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
        labels.clear();
    }

    public List<PatternItemBox> getBoxes() {
        return boxes;
    }

    /// What a click on a box asks for.
}
