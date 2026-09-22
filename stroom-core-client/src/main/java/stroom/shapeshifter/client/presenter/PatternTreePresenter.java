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
import stroom.shapeshifter.client.presenter.PatternTreePresenter.PatternTreeView;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.shared.ShapeshifterLibrary;
import stroom.shapeshifter.shared.ShapeshifterPatternRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.svg.client.Preset;
import stroom.svg.client.SvgPresets;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MouseUtil;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The pattern tree form (design 18 §10, design 43 §4): a nested node editor over the design 38
 * vocabulary. The tree is rendered as nested rows — click selects, double-click edits — and
 * a toolbar over it acts on the selection: add a child, add after, wrap, edit, remove, unwrap,
 * up and down, and (design 44 §3) extract to the project's library and inline from it. Every
 * action is a rewrite through {@link PatternNodes} and lands on the host as a replacement of
 * the {@link Subject}: a template's match, or a part of the library. Beside the tree: the
 * regex the tree means, printed live by the engine ({@code print}), and what a {@code ref}
 * can name — the project's parts, then the standard library — read-only. Every node kind the
 * vocabulary has is reachable from the rows, so there is no wire form here: the Source tab is
 * the document's, and the tree is edited as a tree.
 */
public class PatternTreePresenter
        extends MyPresenterWidget<PatternTreeView>
        implements PatternTreeUiHandlers, MatchEditorPresenter.MatchForm {

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final RestFactory restFactory;
    private final PatternNodeEditPresenter nodeEditor;
    private final ButtonView addChildButton;
    private final ButtonView addAfterButton;
    private final ButtonView wrapButton;
    private final ButtonView editButton;
    private final ButtonView removeButton;
    private final ButtonView unwrapButton;
    private final ButtonView upButton;
    private final ButtonView downButton;
    private final ButtonView extractButton;
    private final ButtonView inlineButton;
    private final NamePresenter namePrompt;

    private ProjectHost host;
    private Subject subject;
    private PatternNode root;
    private int[] selected = new int[0];
    private Consumer<String> onLabelSelect;
    private ShapeshifterLibrary library;
    private boolean libraryRequested;
    private String printed;

    @Inject
    public PatternTreePresenter(final EventBus eventBus,
                                final PatternTreeView view,
                                final RestFactory restFactory,
                                final PatternNodeEditPresenter nodeEditor,
                                final NamePresenter namePrompt) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.nodeEditor = nodeEditor;
        this.namePrompt = namePrompt;
        view.setUiHandlers(this);
        addChildButton = view.addButton(SvgPresets.ADD.title("Add a child to the selected node"));
        addAfterButton = view.addButton(SvgPresets.ADD_BELOW.title("Add a node after the selected one"));
        wrapButton = view.addButton(SvgPresets.OPERATOR.title("Wrap the selected node in a container"));
        editButton = view.addButton(SvgPresets.EDIT.title("Edit the selected node"));
        removeButton = view.addButton(SvgPresets.DELETE.title("Remove the selected node and what it holds"));
        unwrapButton = view.addButton(SvgPresets.COLLAPSE_UP.title("Replace the selected container by what it holds"));
        upButton = view.addButton(SvgPresets.UP.title("Move up among its siblings"));
        downButton = view.addButton(SvgPresets.DOWN.title("Move down among its siblings"));
        extractButton = view.addButton(SvgPresets.enabled(SvgImage.LINK,
                "Extract to the library: the node becomes a named part, and a ref to it stays here"));
        inlineButton = view.addButton(SvgPresets.enabled(SvgImage.UNLINK,
                "Inline: the part the ref names, in its place"));
        enableButtons();
    }

    @Override
    protected void onBind() {
        super.onBind();
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
        registerHandler(extractButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onExtract();
            }
        }));
        registerHandler(inlineButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onInline();
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

    /** Told the label of the node selected, or null: the sample isolates it (design 44 §2). */
    public void setOnLabelSelect(final Consumer<String> onLabelSelect) {
        this.onLabelSelect = onLabelSelect;
    }

    @Override
    public void setTemplate(final String id) {
        setSubject(new Subject() {
            @Override
            public String key() {
                return id;
            }

            @Override
            public String name() {
                final Template template = host.template(id);
                return template == null
                        ? ""
                        : template.name();
            }

            @Override
            public PatternNode node() {
                final Template template = host.template(id);
                return template != null && template.match() instanceof MatchExpression.Pattern pattern
                        ? pattern.node()
                        : null;
            }

            @Override
            public Project with(final Project project, final PatternNode node) {
                final Template template = Templates.byId(project, id);
                return template == null
                        ? project
                        : Templates.replace(project, Templates.withMatch(template, new MatchExpression.Pattern(node)));
            }

            @Override
            public SafeHtml heading() {
                return headingOf("Pattern tree", "composition: a sequence, a choice or a repeat over parts, "
                                               + "and refs to the library");
            }
        });
    }

    /** Edit a part of the project's library (design 44 §3). */
    public void setPattern(final String name) {
        setSubject(new Subject() {
            @Override
            public String key() {
                return Patterns.rowId(name);
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public PatternNode node() {
                return host.getProject() == null
                        ? null
                        : host.getProject().patterns().get(name);
            }

            @Override
            public Project with(final Project project, final PatternNode node) {
                return Patterns.define(project, name, node);
            }

            @Override
            public SafeHtml heading() {
                return headingOf("Pattern part " + name, "defined once here; any template's tree names it with "
                                                       + "ref, and so may another part");
            }
        });
    }

    /** The section heading over the tree, as the other forms of the workbench head theirs. */
    private static SafeHtml headingOf(final String title, final String note) {
        return new SafeHtmlBuilder()
                .appendEscaped(title)
                .appendHtmlConstant(" <span class=\"ss-wb-h4-note\">(")
                .appendEscaped(note)
                .appendHtmlConstant(")</span>")
                .toSafeHtml();
    }

    private void setSubject(final Subject next) {
        if (subject == null || !next.key().equals(subject.key())) {
            selected = new int[0];
        }
        this.subject = next;
        final PatternNode node = next.node();
        if (node == null) {
            return;
        }
        root = node;
        if (PatternNodes.get(root, selected) == null) {
            selected = new int[0];
        }
        getView().setHeading(next.heading());
        getView().setError(null);
        showLibrary();
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
        final PatternNode node = PatternNodes.get(root, selected);
        if (node == null) {
            selected = new int[0];
        }
        render();
        if (onLabelSelect != null) {
            onLabelSelect.accept(node instanceof PatternNode.Labelled labelled
                    ? labelled.label()
                    : null);
        }
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
        extractButton.setEnabled(editable && node != null);
        inlineButton.setEnabled(editable && node != null && host.getProject() != null
                                && PatternNodes.bare(node) instanceof PatternNode.Ref ref
                                && host.getProject().patterns().containsKey(ref.name()));
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
        nodeEditor.setLibrary(host.getProject(), library);
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
        nodeEditor.setLibrary(host.getProject(), library);
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
        apply(host.getProject(), next, select);
    }

    /** Hand a rewritten tree to the host, on a project rewritten with it, and select a path. */
    private void apply(final Project base, final PatternNode next, final int[] select) {
        if (subject == null || base == null) {
            return;
        }
        selected = select;
        host.replace(subject.with(base, next));
    }

    /** The selected node becomes a part of the library, named here, and a ref to it stays in its place. */
    private void onExtract() {
        final PatternNode node = PatternNodes.get(root, selected);
        final Project project = host.getProject();
        if (node == null || project == null || host.isReadOnly()) {
            return;
        }
        if (subject.key().equals(Patterns.rowId(subject.name())) && selected.length == 0) {
            AlertEvent.fireWarn(this, "This is the part itself; extract a node within it", null);
            return;
        }
        final int[] path = selected;
        final List<String> taken = new ArrayList<>(project.patterns().keySet());
        if (library != null) {
            for (final ShapeshifterLibrary.Entry entry : library.getEntries()) {
                taken.add(entry.getName());
            }
        }
        namePrompt.show("Extract to Library", "pattern part", Patterns.HELP, "", taken, name -> apply(
                Patterns.define(project, name, Patterns.part(node)), Patterns.extract(root, path, name), path));
    }

    /** The part a ref names, in the ref's place. */
    private void onInline() {
        final Project project = host.getProject();
        if (project == null || host.isReadOnly()) {
            return;
        }
        final PatternNode next = Patterns.inline(root, selected, project.patterns());
        if (next != root) {
            apply(next, selected);
        }
    }

    // ---- rendering ----

    private void render() {
        final List<PatternItem> items = new ArrayList<>();
        collect(items, root, new int[0], null);
        getView().setTree(items, PatternNodes.path(selected));
        enableButtons();
    }

    /** The tree flattened parents-first, which is the order the layout needs to build itself. */
    private void collect(final List<PatternItem> items,
                         final PatternNode node,
                         final int[] path,
                         final String parentPath) {
        final String here = PatternNodes.path(path);
        final List<PatternNode> children = PatternNodes.children(node);
        items.add(new PatternItem(here, parentPath, Templates.describe(node), !children.isEmpty(),
                node instanceof PatternNode.Labelled));
        for (int i = 0; i < children.size(); i++) {
            collect(items, children.get(i), PatternNodes.child(path, i), here);
        }
    }

    private void printRegex() {
        final String text = ProjectText.printPatternNode(root);
        if (text.equals(printed)) {
            return;
        }
        printed = text;
        restFactory
                .create(RESOURCE)
                .method(res -> res.print(new ShapeshifterPatternRequest(text, false, false,
                        ProjectText.printPatterns(host.getProject()))))
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

    /** What a ref may name: the project's own parts first (design 44 §3), then the standard entries. */
    private void showLibrary() {
        final SafeHtmlBuilder sb = new SafeHtmlBuilder();
        sb.appendHtmlConstant("<table class=\"shapeshifter-library\">");
        final Project project = host.getProject();
        if (project != null) {
            for (final Map.Entry<String, PatternNode> part : project.patterns().entrySet()) {
                sb.appendHtmlConstant("<tr><td class=\"shapeshifter-library-name\">")
                        .appendEscaped(part.getKey())
                        .appendHtmlConstant("</td><td class=\"shapeshifter-pattern-text\">")
                        .appendEscaped(Templates.describe(part.getValue()))
                        .appendHtmlConstant("</td><td class=\"ss-lib-whose\">project</td></tr>");
            }
        }
        if (library != null) {
            for (final ShapeshifterLibrary.Entry entry : library.getEntries()) {
                sb.appendHtmlConstant("<tr><td class=\"shapeshifter-library-name\">")
                        .appendEscaped(entry.getName())
                        .appendHtmlConstant("</td><td class=\"shapeshifter-pattern-text\">")
                        .appendEscaped(entry.getRegex())
                        .appendHtmlConstant("</td><td class=\"ss-lib-whose\"></td></tr>");
            }
        }
        sb.appendHtmlConstant("</table>");
        getView().setLibrary(sb.toSafeHtml());
    }

    private void loadLibrary() {
        libraryRequested = true;
        restFactory
                .create(RESOURCE)
                .method(ShapeshifterResource::library)
                .onSuccess(result -> {
                    library = result;
                    showLibrary();
                })
                .onFailure(error -> {
                    libraryRequested = false;
                    getView().setError("The standard library could not be fetched: " + error.getMessage());
                })
                .taskMonitorFactory(this)
                .exec();
    }

    /**
     * What the tree edits (design 44 §3): a template's match, or a part of the project's
     * library. Read live from the host, so every refresh sees the model; written as a rewrite
     * of a project, so an edit that also touches the library is one replacement.
     */
    public interface Subject {

        /** Distinct across subjects: a template's id, or a part's row id. */
        String key();

        String name();

        /** The tree, or null when the subject no longer holds one. */
        PatternNode node();

        /** The project with this subject's tree replaced. */
        Project with(Project project, PatternNode node);

        /** What to say over the tree. */
        SafeHtml heading();
    }

    public interface PatternTreeView extends View, HasUiHandlers<PatternTreeUiHandlers> {

        ButtonView addButton(Preset preset);

        /** What is being edited, over the tree: a template's match, or a part of the library. */
        void setHeading(SafeHtml html);

        /** The nodes parents-first, and the path of the selected one. */
        void setTree(List<PatternItem> items, String selected);

        /** The regex the tree means, as the engine prints it. */
        void setRegex(String regex);

        void setLibrary(SafeHtml html);

        void setError(String error);
    }
}
