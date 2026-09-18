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

package stroom.shapeshifter.client.presenter;

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestFactory;
import stroom.editor.client.presenter.EditorPresenter;
import stroom.shapeshifter.client.presenter.PatternTreePresenter.PatternTreeView;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.shared.ShapeshifterLibrary;
import stroom.shapeshifter.shared.ShapeshifterPatternRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.svg.client.Preset;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DelayedUpdate;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MouseUtil;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

import java.util.Arrays;
import java.util.List;

/**
 * The pattern tree tab (design 18 §10, design 43 §4): a nested node editor over the design 38
 * vocabulary. The tree is rendered as nested rows — click selects, double-click edits — and
 * a toolbar over it acts on the selection: add a child, add after, wrap, edit, remove, unwrap,
 * up and down. Every action is a rewrite through {@link PatternNodes} and lands on the host
 * as a replacement match. Beside the tree: the regex the tree means, printed live by the
 * engine ({@code print}), and the standard library a {@code ref} can name, read-only. The
 * wire form stays editable in the pane to the right, for pasting and for what the rows do not
 * yet surface.
 */
public class PatternTreePresenter
        extends MyPresenterWidget<PatternTreeView>
        implements PatternTreeUiHandlers, MatchEditorPresenter.MatchTab {

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final RestFactory restFactory;
    private final PatternNodeEditPresenter nodeEditor;
    private final EditorPresenter editor;
    private final DelayedUpdate commit;
    private final ButtonView addChildButton;
    private final ButtonView addAfterButton;
    private final ButtonView wrapButton;
    private final ButtonView editButton;
    private final ButtonView removeButton;
    private final ButtonView unwrapButton;
    private final ButtonView upButton;
    private final ButtonView downButton;

    private ProjectHost host;
    private String templateId;
    private PatternNode root;
    private int[] selected = new int[0];
    private ShapeshifterLibrary library;
    private boolean libraryRequested;
    private String committed;
    private String printed;

    @Inject
    public PatternTreePresenter(final EventBus eventBus,
                                final PatternTreeView view,
                                final RestFactory restFactory,
                                final PatternNodeEditPresenter nodeEditor,
                                final Provider<EditorPresenter> editorProvider) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.nodeEditor = nodeEditor;
        this.editor = editorProvider.get();
        this.commit = new DelayedUpdate(400, this::commit);
        editor.setMode(AceEditorMode.JSON);
        editor.getFormatAction().setAvailable(false);
        view.setUiHandlers(this);
        view.setEditor(editor.getView());
        addChildButton = view.addButton(SvgPresets.ADD.title("Add a child to the selected node"));
        addAfterButton = view.addButton(SvgPresets.ADD_BELOW.title("Add a node after the selected one"));
        wrapButton = view.addButton(SvgPresets.OPERATOR.title("Wrap the selected node in a container"));
        editButton = view.addButton(SvgPresets.EDIT.title("Edit the selected node"));
        removeButton = view.addButton(SvgPresets.DELETE.title("Remove the selected node and what it holds"));
        unwrapButton = view.addButton(SvgPresets.COLLAPSE_UP.title("Replace the selected container by what it holds"));
        upButton = view.addButton(SvgPresets.UP.title("Move up among its siblings"));
        downButton = view.addButton(SvgPresets.DOWN.title("Move down among its siblings"));
        enableButtons();
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(editor.addValueChangeHandler(event -> commit.update()));
        registerHandler(addChildButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onAddChild();
            }
        }));
        registerHandler(addAfterButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onAddAfter();
            }
        }));
        registerHandler(wrapButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onWrap();
            }
        }));
        registerHandler(editButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onEdit();
            }
        }));
        registerHandler(removeButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onRemove();
            }
        }));
        registerHandler(unwrapButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onUnwrap();
            }
        }));
        registerHandler(upButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onMove(-1);
            }
        }));
        registerHandler(downButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onMove(1);
            }
        }));
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    @Override
    public void setTemplate(final String id) {
        if (!id.equals(templateId)) {
            commit.reset();
            selected = new int[0];
        }
        this.templateId = id;
        final Template template = host.template(id);
        if (template == null || !(template.match() instanceof MatchExpression.Pattern pattern)) {
            return;
        }
        editor.setReadOnly(host.isReadOnly());
        root = pattern.node();
        if (PatternNodes.get(root, selected) == null) {
            selected = new int[0];
        }
        final String text = ProjectText.printPatternNode(root);
        if (!text.equals(committed)) {
            committed = text;
            editor.setText(text);
        }
        getView().setError(null);
        render();
        printRegex();
        if (!libraryRequested) {
            loadLibrary();
        }
    }

    // ---- selection and the toolbar ----

    @Override
    public void onSelect(final String path) {
        selected = PatternNodes.path(path);
        if (PatternNodes.get(root, selected) == null) {
            selected = new int[0];
        }
        render();
    }

    @Override
    public void onOpen(final String path) {
        onSelect(path);
        onEdit();
    }

    private void enableButtons() {
        final boolean editable = host != null && !host.isReadOnly() && root != null;
        final PatternNode node = root == null
                ? null
                : PatternNodes.get(root, selected);
        final boolean container = node != null && PatternNodes.isContainer(node);
        final boolean hasParent = selected.length > 0;
        addChildButton.setEnabled(editable && container);
        addAfterButton.setEnabled(editable && node != null);
        wrapButton.setEnabled(editable && node != null);
        editButton.setEnabled(editable && node != null);
        removeButton.setEnabled(editable && node != null);
        unwrapButton.setEnabled(editable && container && PatternNodes.children(node).size() == 1);
        upButton.setEnabled(editable && hasParent && selected[selected.length - 1] > 0);
        downButton.setEnabled(editable && hasParent
                              && selected[selected.length - 1]
                                 < PatternNodes.children(PatternNodes.get(root, PatternNodes.parent(selected))).size()
                                   - 1);
    }

    private void onAddChild() {
        final PatternNode parent = PatternNodes.get(root, selected);
        if (parent == null || !PatternNodes.isContainer(parent) || host.isReadOnly()) {
            return;
        }
        final int[] parentPath = selected;
        final int index = PatternNodes.children(parent).size();
        newNode("New Node", null, node -> {
            apply(PatternNodes.insert(root, parentPath, index, node), PatternNodes.child(parentPath, index));
        });
    }

    private void onAddAfter() {
        final PatternNode node = PatternNodes.get(root, selected);
        if (node == null || host.isReadOnly()) {
            return;
        }
        if (selected.length == 0) {
            // Beside the root: the root becomes a sequence of itself and the new node.
            newNode("New Node", null, added -> apply(
                    new PatternNode.Sequence(List.of(root, added)), new int[]{1}));
            return;
        }
        final int[] parentPath = PatternNodes.parent(selected);
        final int index = selected[selected.length - 1] + 1;
        newNode("New Node", null, added -> apply(
                PatternNodes.insert(root, parentPath, index, added), PatternNodes.child(parentPath, index)));
    }

    private void onWrap() {
        final PatternNode node = PatternNodes.get(root, selected);
        if (node == null || host.isReadOnly()) {
            return;
        }
        final int[] path = selected;
        newNode("Wrap Node", node, wrapper -> {
            if (!PatternNodes.isContainer(wrapper)) {
                AlertEvent.fireWarn(this, "A " + Templates.describe(wrapper) + " holds nothing; choose a container",
                        null);
                return;
            }
            apply(PatternNodes.replace(root, path, wrapper), path);
        });
    }

    private void onEdit() {
        final PatternNode node = PatternNodes.get(root, selected);
        if (node == null || host.isReadOnly()) {
            return;
        }
        final int[] path = selected;
        nodeEditor.setLibrary(library);
        nodeEditor.read(node, null);
        nodeEditor.show("Edit Node", e -> {
            if (e.isOk()) {
                final PatternNode edited = nodeEditor.write();
                if (edited != null) {
                    apply(PatternNodes.replace(root, path, edited), path);
                    e.hide();
                }
            } else {
                e.hide();
            }
        });
    }

    private void onRemove() {
        final PatternNode node = PatternNodes.get(root, selected);
        if (node == null || host.isReadOnly()) {
            return;
        }
        final int[] path = selected;
        final int held = PatternNodes.children(node).size();
        if (held == 0) {
            remove(path);
            return;
        }
        final String message = "Remove " + Templates.describe(node) + " and the " + held + (held == 1
                ? " node"
                : " nodes") + " it holds?";
        ConfirmEvent.fire(this, message, ok -> {
            if (ok) {
                remove(path);
            }
        });
    }

    private void remove(final int[] path) {
        final PatternNode next = PatternNodes.remove(root, path);
        if (next == null) {
            // The root itself: a tree needs a node, so the root becomes the placeholder.
            apply(PatternNodes.PLACEHOLDER, new int[0]);
        } else {
            apply(next, path.length == 0
                    ? path
                    : PatternNodes.parent(path));
        }
    }

    private void onUnwrap() {
        final PatternNode node = PatternNodes.get(root, selected);
        if (node == null || host.isReadOnly()) {
            return;
        }
        try {
            apply(PatternNodes.unwrap(root, selected), selected);
        } catch (final IllegalArgumentException e) {
            AlertEvent.fireWarn(this, e.getMessage(), null);
        }
    }

    private void onMove(final int by) {
        if (selected.length == 0 || host.isReadOnly()) {
            return;
        }
        final PatternNode next = PatternNodes.move(root, selected, by);
        if (next != root) {
            final int[] path = Arrays.copyOf(selected, selected.length);
            path[path.length - 1] += by;
            apply(next, path);
        }
    }

    /** Open the node dialog for a new node; a body pre-fills what a container will wrap. */
    private void newNode(final String caption, final PatternNode body,
                         final java.util.function.Consumer<PatternNode> then) {
        nodeEditor.setLibrary(library);
        nodeEditor.read(null, body);
        nodeEditor.show(caption, e -> {
            if (e.isOk()) {
                final PatternNode node = nodeEditor.write();
                if (node != null) {
                    then.accept(node);
                    e.hide();
                }
            } else {
                e.hide();
            }
        });
    }

    /** Hand a rewritten tree to the host and select a path in it. */
    private void apply(final PatternNode next, final int[] select) {
        final Template template = host.template(templateId);
        if (template == null) {
            return;
        }
        selected = select;
        committed = ProjectText.printPatternNode(next);
        editor.setText(committed);
        host.replace(host.withTemplate(Templates.withMatch(template, new MatchExpression.Pattern(next))));
    }

    // ---- the wire-form editor ----

    private void commit() {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly()) {
            return;
        }
        final PatternNode node;
        try {
            node = ProjectText.parsePatternNode(editor.getText());
        } catch (final ConfigException e) {
            getView().setError(e.getMessage());
            return;
        }
        getView().setError(null);
        if (node.equals(root)) {
            return;
        }
        committed = ProjectText.printPatternNode(node);
        host.replace(host.withTemplate(Templates.withMatch(template, new MatchExpression.Pattern(node))));
    }

    // ---- rendering ----

    private void render() {
        final SafeHtmlBuilder sb = new SafeHtmlBuilder();
        sb.appendHtmlConstant("<ul class=\"shapeshifter-tree shapeshifter-tree--editable\">");
        renderNode(sb, root, new int[0]);
        sb.appendHtmlConstant("</ul>");
        getView().setTree(sb.toSafeHtml());
        enableButtons();
    }

    private void renderNode(final SafeHtmlBuilder sb, final PatternNode node, final int[] path) {
        final StringBuilder classes = new StringBuilder("shapeshifter-node");
        if (node instanceof PatternNode.Labelled) {
            classes.append(" shapeshifter-node--labelled");
        }
        if (Arrays.equals(path, selected)) {
            classes.append(" shapeshifter-node--selected");
        }
        sb.appendHtmlConstant("<li class=\"" + classes + "\" data-path=\"" + PatternNodes.path(path) + "\">");
        sb.appendHtmlConstant("<span class=\"shapeshifter-node-text\">")
                .appendEscaped(Templates.describe(node))
                .appendHtmlConstant("</span>");
        final List<PatternNode> children = PatternNodes.children(node);
        if (!children.isEmpty()) {
            sb.appendHtmlConstant("<ul>");
            for (int i = 0; i < children.size(); i++) {
                renderNode(sb, children.get(i), PatternNodes.child(path, i));
            }
            sb.appendHtmlConstant("</ul>");
        }
        sb.appendHtmlConstant("</li>");
    }

    private void printRegex() {
        final String text = ProjectText.printPatternNode(root);
        if (text.equals(printed)) {
            return;
        }
        printed = text;
        restFactory
                .create(RESOURCE)
                .method(res -> res.print(new ShapeshifterPatternRequest(text, false, false)))
                .onSuccess(result -> {
                    if (text.equals(printed)) {
                        getView().setRegex(result.getText());
                    }
                })
                .onFailure(error -> {
                    if (text.equals(printed)) {
                        getView().setRegex("— " + error.getMessage());
                    }
                })
                .taskMonitorFactory(this)
                .exec();
    }

    private void loadLibrary() {
        libraryRequested = true;
        restFactory
                .create(RESOURCE)
                .method(ShapeshifterResource::library)
                .onSuccess(result -> {
                    library = result;
                    final SafeHtmlBuilder sb = new SafeHtmlBuilder();
                    sb.appendHtmlConstant("<table class=\"shapeshifter-library\">");
                    for (final ShapeshifterLibrary.Entry entry : result.getEntries()) {
                        sb.appendHtmlConstant("<tr><td class=\"shapeshifter-library-name\">")
                                .appendEscaped(entry.getName())
                                .appendHtmlConstant("</td><td class=\"shapeshifter-pattern-text\">")
                                .appendEscaped(entry.getRegex())
                                .appendHtmlConstant("</td></tr>");
                    }
                    sb.appendHtmlConstant("</table>");
                    getView().setLibrary(sb.toSafeHtml());
                })
                .onFailure(error -> {
                    libraryRequested = false;
                    getView().setError("The standard library could not be fetched: " + error.getMessage());
                })
                .taskMonitorFactory(this)
                .exec();
    }

    public interface PatternTreeView extends View, HasUiHandlers<PatternTreeUiHandlers> {

        ButtonView addButton(Preset preset);

        void setTree(SafeHtml html);

        /** The regex the tree means, as the engine prints it. */
        void setRegex(String regex);

        void setLibrary(SafeHtml html);

        void setError(String error);

        void setEditor(View view);
    }
}
