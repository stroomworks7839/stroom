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

import stroom.data.grid.client.MouseHelper;
import stroom.pipeline.structure.client.view.Box;
import stroom.pipeline.structure.client.view.TreePanel;
import stroom.shapeshifter.client.presenter.PatternItem;
import stroom.widget.htree.client.BracketConnectorRenderer;
import stroom.widget.htree.client.ConnectorRenderer;
import stroom.widget.htree.client.LayeredCanvas;
import stroom.widget.htree.client.TreeRenderer;
import stroom.widget.htree.client.TreeRenderer2;
import stroom.widget.htree.client.treelayout.CenteredParentTreeLayout;
import stroom.widget.htree.client.treelayout.Configuration.AlignmentInLevel;
import stroom.widget.htree.client.treelayout.Configuration.Location;
import stroom.widget.htree.client.treelayout.NodeExtentProvider;
import stroom.widget.htree.client.treelayout.TreeLayout;
import stroom.widget.htree.client.treelayout.util.DefaultConfiguration;
import stroom.widget.htree.client.treelayout.util.DefaultTreeForTreeLayout;

import com.google.gwt.canvas.dom.client.Context2d;
import com.google.gwt.dom.client.Element;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.view.client.SelectionModel;

import java.util.Objects;

/**
 * The pattern tree drawn as Stroom draws an expression (design 44 §4): boxes the layout places
 * and bracket connectors on a canvas behind them, rather than an indented list — the same
 * widgets the query expression editor and the pipeline structure editor are built from, so a
 * composition reads here the way a condition reads there.
 */
public class PatternTreePanel extends TreePanel<PatternItem> {

    private static final double HORIZONTAL_SEPARATION = 20;
    private static final double VERTICAL_SEPARATION = 0;

    private final FlowPanel boxPanel;
    private final PatternItemRenderer cellRenderer;
    private TreeRenderer2<PatternItem> renderer;
    private TreeLayout<PatternItem> treeLayout;
    private DefaultTreeForTreeLayout<PatternItem> tree;

    public PatternTreePanel() {
        final FlowPanel panel = new FlowPanel();
        panel.setStyleName("ss-tree-panel");
        boxPanel = new FlowPanel();
        boxPanel.setStyleName("ss-tree-boxes");
        cellRenderer = new PatternItemRenderer(boxPanel);

        final LayeredCanvas canvas = LayeredCanvas.createIfSupported();
        if (canvas != null) {
            final Context2d arrowContext = canvas.getLayer(TreeRenderer.ARROW_LAYER).getContext2d();
            final ConnectorRenderer<PatternItem> connectorRenderer = new BracketConnectorRenderer<>(arrowContext);
            final DefaultConfiguration<PatternItem> layoutConfig = new DefaultConfiguration<>(
                    HORIZONTAL_SEPARATION, VERTICAL_SEPARATION, Location.Left, AlignmentInLevel.TowardsRoot);
            final NodeExtentProvider<PatternItem> extentProvider = cellRenderer;
            treeLayout = new CenteredParentTreeLayout<>(extentProvider, layoutConfig);
            renderer = new TreeRenderer2<>(canvas, cellRenderer, connectorRenderer);
            renderer.setTreeLayout(treeLayout);
            canvas.setStyleName("ss-tree-canvas");
            panel.add(canvas);
        }
        panel.add(boxPanel);
        initWidget(panel);
    }

    public void setListener(final PatternItemRenderer.Listener listener) {
        cellRenderer.setListener(listener);
    }

    /** The selected node's path, so a redraw comes back to it. */
    public void setSelected(final String path) {
        cellRenderer.setSelected(path);
    }

    @Override
    public Box<PatternItem> getBox(final PatternItem item) {
        for (final PatternItemBox box : cellRenderer.getBoxes()) {
            if (Objects.equals(box.getItem(), item)) {
                return box;
            }
        }
        return null;
    }

    @Override
    public Box<PatternItem> getTargetBox(final Event event, final boolean usePosition) {
        final Element target = event.getEventTarget().cast();
        for (final PatternItemBox box : cellRenderer.getBoxes()) {
            if (usePosition
                    ? MouseHelper.mouseIsOverElement(event, box.getElement())
                    : box.getElement().isOrHasChild(target)) {
                return box;
            }
        }
        return null;
    }

    @Override
    public void setSelectionModel(final SelectionModel<PatternItem> selectionModel) {
        // The selection is the presenter's, by path: a redraw follows every edit, and a box
        // rebuilt from a rewritten tree is not the object that was selected.
    }

    @Override
    public void refresh() {
        refresh(null);
    }

    @Override
    public void refresh(final RefreshCallback callback) {
        boxPanel.clear();
        if (renderer != null) {
            cellRenderer.clear();
            renderer.draw();
        }
        if (callback != null) {
            callback.onRefresh();
        }
    }

    @Override
    public DefaultTreeForTreeLayout<PatternItem> getTree() {
        return tree;
    }

    @Override
    public void setTree(final DefaultTreeForTreeLayout<PatternItem> tree) {
        this.tree = tree;
        if (treeLayout != null) {
            treeLayout.setTree(tree);
        }
    }
}
