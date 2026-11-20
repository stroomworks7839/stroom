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

import stroom.widget.layout.client.SplitPane;

import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/// The panes of one split pane sharing its length, some of which fold away under their title
/// bars ([CollapsiblePane]) (design 47 E). A folded pane is its title bar. Of those that are
/// open, the first in the order given by [#restFirst] takes the rest, and the others have the
/// size they were given or dragged to; with every pane folded, the last takes the rest, as an
/// empty pane under the bars. A folded pane that is dragged open is open, at that size.
///
/// The Design tab's data column, its messages, and the workbench's two columns each share their
/// length this way, so a pane folds and opens the same wherever it is.
public final class FoldingPanes {

    private static final String COLLAPSED_SIZE = CollapsiblePane.HEAD_PX + "px";
    private static final String REST = "*";

    private final SplitPane split;
    private final List<Pane> panes = new ArrayList<>();
    private final List<Widget> restOrder = new ArrayList<>();
    private Consumer<CollapsiblePane> onFold = pane -> {
    };

    /// @param split The split pane, whose resizing this now listens to.
    public FoldingPanes(final SplitPane split) {
        this.split = split;
        split.setOnResized(this::onResized);
    }

    /// Adds the next of the split pane's panes, in the order they stand in it. A
    /// [CollapsiblePane] folds, and the panes are shared out again whenever the user folds or
    /// opens it; any other widget never folds.
    ///
    /// @param pane The pane.
    /// @param size What it has when it is open and another takes the rest, until it is dragged.
    /// @return This, to add the next.
    public FoldingPanes add(final Widget pane, final String size) {
        final CollapsiblePane fold = pane instanceof final CollapsiblePane collapsible
                ? collapsible
                : null;
        panes.add(new Pane(pane, fold, size));
        if (fold != null) {
            fold.setOnToggle(collapsed -> {
                share();
                onFold.accept(fold);
            });
        }
        return this;
    }

    /// @param order Which of the panes takes the rest, the first of them that is open; by
    ///              default the first open pane in the order they stand.
    /// @return This.
    public FoldingPanes restFirst(final Widget... order) {
        restOrder.clear();
        restOrder.addAll(Arrays.asList(order));
        return this;
    }

    /// @param onFold Told when the user folds or opens a pane, by its bar or by dragging it open,
    ///               with the pane: what the user chose, for an owner that keeps it.
    public void setOnFold(final Consumer<CollapsiblePane> onFold) {
        this.onFold = onFold;
    }

    /// Folds or opens a pane for its owner, and shares the length out again, without telling
    /// [#setOnFold]'s handler.
    ///
    /// @param pane   One of the panes.
    /// @param folded Whether only its title bar is to be shown.
    public void fold(final CollapsiblePane pane, final boolean folded) {
        pane.setCollapsed(folded);
        share();
    }

    /// Gives each pane its share: the folded their bars, the one that takes the rest the rest,
    /// and the others their sizes.
    public void share() {
        final Pane rest = rest();
        // The pane that takes the rest is set last: until then two would be asking for it
        for (final Pane pane : panes) {
            if (pane != rest) {
                split.setPaneSize(pane.widget, pane.isFolded()
                        ? COLLAPSED_SIZE
                        : pane.size);
            }
        }
        split.setPaneSize(rest.widget, REST);
    }

    private Pane rest() {
        final List<Pane> order = new ArrayList<>();
        for (final Widget widget : restOrder) {
            for (final Pane pane : panes) {
                if (pane.widget == widget) {
                    order.add(pane);
                }
            }
        }
        if (order.isEmpty()) {
            order.addAll(panes);
        }
        for (final Pane pane : order) {
            if (!pane.isFolded()) {
                return pane;
            }
        }
        return panes.get(panes.size() - 1);
    }

    // A splitter was dragged. An open pane keeps the size it was dragged to for when it is next
    // opened; a folded one that was dragged open is open, at that size.
    private void onResized() {
        final List<CollapsiblePane> opened = new ArrayList<>();
        for (final Pane pane : panes) {
            final String size = split.getPaneSize(pane.widget);
            if (size == null || REST.equals(size)) {
                continue;
            }
            if (pane.isFolded()) {
                if (COLLAPSED_SIZE.equals(size)) {
                    continue;
                }
                pane.fold.setCollapsed(false);
                opened.add(pane.fold);
            }
            pane.size = size;
        }
        if (!opened.isEmpty()) {
            // Which pane takes the rest depends on which are open
            share();
            opened.forEach(onFold);
        }
    }

    // One of the panes: the widget the split pane holds, the same widget when it folds, and the
    // size it has when it is open and does not take the rest.
    private static final class Pane {

        private final Widget widget;
        private final CollapsiblePane fold;
        private String size;

        private Pane(final Widget widget, final CollapsiblePane fold, final String size) {
            this.widget = widget;
            this.fold = fold;
            this.size = size;
        }

        private boolean isFolded() {
            return fold != null && fold.isCollapsed();
        }
    }
}
