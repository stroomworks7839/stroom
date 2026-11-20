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
import stroom.widget.button.client.Toolbar;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextBox;
import stroom.widget.htree.client.treelayout.util.DefaultTreeForTreeLayout;
import stroom.widget.text.client.Html;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class PatternTree extends Component {

    private Consumer<String> onSelect = path -> {
    };
    private Consumer<String> onOpen = path -> {
    };
    private Consumer<String> onPoint = path -> {
    };
    private Consumer<String> onAdd = text -> {
    };

    private static final Binder BINDER = GWT.create(Binder.class);

    private final PatternTreePanel treePanel = new PatternTreePanel();

    @UiField
    Toolbar buttonPanel;
    @UiField
    Html heading;
    @UiField
    Label regex;
    @UiField
    Label error;
    @UiField
    SimplePanel tree;
    @UiField
    Html library;
    @UiField
    TextBox addField;

    public PatternTree() {
        initWidget(BINDER.createAndBindUi(this));
        setError(null);
        tree.setWidget(treePanel);
        treePanel.setOnSelect(path -> onSelect.accept(path));
        treePanel.setOnOpen(path -> onOpen.accept(path));
        treePanel.setOnPoint(path -> onPoint.accept(path));
        addField.getElement().setPropertyString("placeholder", "a regex, e.g. [a-z]+= — Enter adds its nodes");
        addField.addKeyDownHandler(event -> {
            if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER && !addField.getText().isEmpty()) {
                event.preventDefault();
                onAdd.accept(addField.getText());
            }
        });
    }

    /// Empties the add line, with the keyboard left in it for the next fragment.
    public void clearAdd() {
        addField.setText("");
        addField.setFocus(true);
    }

    /// @param enabled Whether the add line can be typed in: not read only, and a node selected.
    public void setAddEnabled(final boolean enabled) {
        addField.setEnabled(enabled);
    }

    /// @param onAdd Given what was typed in the add line when Enter is pressed in it.
    public void setOnAdd(final Consumer<String> onAdd) {
        this.onAdd = onAdd;
    }

    /// Selects a node among those drawn, without drawing the tree again.
    ///
    /// @param path The node's path.
    public void select(final String path) {
        treePanel.select(path);
    }

    /// @param path The node matching stopped at, marked; null for none. Drawn at the next tree.
    public void setStopped(final String path) {
        treePanel.setStopped(path);
    }

    /// Edits a node's text in place, a field where its label was.
    ///
    /// @param path The node.
    /// @param text The text to start from.
    /// @param onTake Given the text taken.
    /// @param onCancel Told when it is put back.
    /// @return Whether the node is drawn, and so could be edited.
    public boolean edit(final String path, final String text, final Consumer<String> onTake,
                        final Runnable onCancel) {
        return treePanel.edit(path, text, onTake, onCancel);
    }


    /// @return The toolbar its controller adds its actions to.
    public Toolbar getToolbar() {
        return buttonPanel;
    }

    /// The nodes in the order the presenter walked them, the root first: a parent is always laid
    /// out before the children that name it, so one pass builds the layout's tree.
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

    public void setHeading(final SafeHtml html) {
        heading.setHTML(html);
    }

    public void setRegex(final String text) {
        regex.setText(text == null
                ? ""
                : text);
    }

    public void setLibrary(final SafeHtml html) {
        library.setHTML(html);
    }

    public void setError(final String text) {
        error.setText(text == null
                ? ""
                : text);
        error.setVisible(text != null);
    }


    /// @param onSelect Told when select.
    public void setOnSelect(final Consumer<String> onSelect) {
        this.onSelect = onSelect;
    }

    /// @param onOpen Told when open.
    public void setOnOpen(final Consumer<String> onOpen) {
        this.onOpen = onOpen;
    }

    /// @param onPoint Told the path of the node the pointer comes onto, and null when it leaves.
    public void setOnPoint(final Consumer<String> onPoint) {
        this.onPoint = onPoint;
    }

    interface Binder extends UiBinder<Widget, PatternTree> {

    }
}
