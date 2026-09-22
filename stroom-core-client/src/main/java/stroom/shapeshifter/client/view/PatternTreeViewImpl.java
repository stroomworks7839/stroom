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
import stroom.shapeshifter.client.presenter.PatternTreePresenter.PatternTreeView;
import stroom.shapeshifter.client.presenter.PatternTreeUiHandlers;
import stroom.svg.client.Preset;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;
import stroom.widget.htree.client.treelayout.util.DefaultTreeForTreeLayout;

import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PatternTreeViewImpl
        extends ViewWithUiHandlers<PatternTreeUiHandlers>
        implements PatternTreeView {

    private final Widget widget;
    private final PatternTreePanel treePanel = new PatternTreePanel();

    @UiField
    ButtonPanel buttonPanel;
    @UiField
    HTML heading;
    @UiField
    Label regex;
    @UiField
    Label error;
    @UiField
    SimplePanel tree;
    @UiField
    HTML library;

    @Inject
    public PatternTreeViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        setError(null);
        tree.setWidget(treePanel);
        treePanel.setListener(new PatternItemRenderer.Listener() {
            @Override
            public void onSelect(final String path) {
                if (getUiHandlers() != null) {
                    getUiHandlers().onSelect(path);
                }
            }

            @Override
            public void onOpen(final String path) {
                if (getUiHandlers() != null) {
                    getUiHandlers().onOpen(path);
                }
            }
        });
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public ButtonView addButton(final Preset preset) {
        return buttonPanel.addButton(preset);
    }

    /**
     * The nodes in the order the presenter walked them, the root first: a parent is always laid
     * out before the children that name it, so one pass builds the layout's tree.
     */
    @Override
    public void setTree(final List<PatternItem> items, final String selected) {
        final DefaultTreeForTreeLayout<PatternItem> layout = items.isEmpty()
                ? null
                : new DefaultTreeForTreeLayout<>(items.get(0));
        final Map<String, PatternItem> byPath = new HashMap<>();
        for (final PatternItem item : items) {
            byPath.put(item.getPath(), item);
            final PatternItem parent = item.getParentPath() == null
                    ? null
                    : byPath.get(item.getParentPath());
            if (parent != null) {
                layout.addChild(parent, item);
            }
        }
        treePanel.setSelected(selected);
        treePanel.setTree(layout);
        treePanel.refresh();
    }

    @Override
    public void setHeading(final SafeHtml html) {
        heading.setHTML(html);
    }

    @Override
    public void setRegex(final String text) {
        regex.setText(text == null
                ? ""
                : text);
    }

    @Override
    public void setLibrary(final SafeHtml html) {
        library.setHTML(html);
    }

    @Override
    public void setError(final String text) {
        error.setText(text == null
                ? ""
                : text);
        error.setVisible(text != null);
    }

    public interface Binder extends UiBinder<Widget, PatternTreeViewImpl> {

    }
}
