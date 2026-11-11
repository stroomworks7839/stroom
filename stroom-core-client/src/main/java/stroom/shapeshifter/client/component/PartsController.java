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

import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Template;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.util.client.MouseUtil;

import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;

/// The parts form (design 39): the match sequence as rows — pattern, take, seek, read — with a
/// toolbar acting on the selected row: add after, edit, remove, up, down; each a rewrite landing
/// on the host as a replacement match. Every kind of part is reachable from the rows, so there
/// is no wire form here either (design 44 §4): the Source tab is the document's. A sequence
/// needs at least one part, so the last cannot be removed.
public class PartsController
        extends Controller
        implements MatchEditorController.MatchForm {

    // From Contexts.READ_ONLY: true until the document says otherwise, so a
    // controller with no owner fails closed rather than editable.
    private boolean readOnly = true;

    private final Parts view;

    private final Provider<PartEditController> partEditorProvider;
    private final ToolbarButton addButton;
    private final ToolbarButton editButton;
    private final ToolbarButton removeButton;
    private final ToolbarButton upButton;
    private final ToolbarButton downButton;

    private ProjectHost host;
    private String templateId;
    private List<MatchPart> parts = List.of();
    private int selected = -1;

    @Inject
    public PartsController(final EventBus eventBus,
                          final Parts view,
                          final Provider<PartEditController> partEditorProvider) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        watch(Contexts.READ_ONLY, readOnly -> this.readOnly = readOnly);
        view.setOnSelect(this::onSelect);
        view.setOnOpen(this::onOpen);
        this.partEditorProvider = partEditorProvider;
        addButton = view.getToolbar().button(SvgImage.ADD, "Add a part after the selected one", () -> {
        });
        editButton = view.getToolbar().button(SvgImage.EDIT, "Edit the selected part", () -> {
        });
        removeButton = view.getToolbar().button(SvgImage.DELETE, "Remove the selected part", () -> {
        });
        upButton = view.getToolbar().button(SvgImage.UP, "Move up: earlier in the sequence", () -> {
        });
        downButton = view.getToolbar().button(SvgImage.DOWN, "Move down: later in the sequence", () -> {
        });
        enableButtons();
        own(addButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onAdd();
            }
        }));
        own(editButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onEdit();
            }
        }));
        own(removeButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onRemove();
            }
        }));
        own(upButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onMove(-1);
            }
        }));
        own(downButton.addClickHandler(e -> {
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
            selected = -1;
        }
        this.templateId = id;
        final Template template = host.template(id);
        if (template == null || !(template.match() instanceof MatchExpression.Parts sequence)) {
            return;
        }
        parts = sequence.parts();
        if (selected >= parts.size()) {
            selected = parts.size() - 1;
        }
        view.setError(null);
        render();
    }

    public void onSelect(final int index) {
        selected = index >= 0 && index < parts.size()
                ? index
                : -1;
        render();
    }

    public void onOpen(final int index) {
        onSelect(index);
        onEdit();
    }

    private void enableButtons() {
        final boolean editable = host != null && !readOnly && templateId != null;
        final boolean has = selected >= 0 && selected < parts.size();
        addButton.setEnabled(editable);
        editButton.setEnabled(editable && has);
        removeButton.setEnabled(editable && has && parts.size() > 1);
        upButton.setEnabled(editable && has && selected > 0);
        downButton.setEnabled(editable && has && selected < parts.size() - 1);
    }

    private void onAdd() {
        if (readOnly || templateId == null) {
            return;
        }
        final int at = selected < 0
                ? parts.size()
                : selected + 1;
        final PartEditController editor = partEditorProvider.get();
        editor.read(null);
        editor.show(this, "New Part", e -> {
            if (e.isOk()) {
                final MatchPart part = editor.write();
                if (part != null) {
                    final List<MatchPart> next = new ArrayList<>(parts);
                    next.add(at, part);
                    apply(next, at);
                    e.hide();
                } else {
                    // Nothing was written, so the dialog stays open — and its OK button has to come back
                    // out of its busy state, or the alert leaves it spinning for good.
                    e.reset();
                }
            } else {
                e.hide();
            }
        });
    }

    private void onEdit() {
        if (readOnly || selected < 0 || selected >= parts.size()) {
            return;
        }
        final int at = selected;
        final PartEditController editor = partEditorProvider.get();
        editor.read(parts.get(at));
        editor.show(this, "Edit Part", e -> {
            if (e.isOk()) {
                final MatchPart part = editor.write();
                if (part != null) {
                    final List<MatchPart> next = new ArrayList<>(parts);
                    next.set(at, part);
                    apply(next, at);
                    e.hide();
                } else {
                    // Nothing was written, so the dialog stays open — and its OK button has to come back
                    // out of its busy state, or the alert leaves it spinning for good.
                    e.reset();
                }
            } else {
                e.hide();
            }
        });
    }

    private void onRemove() {
        if (readOnly || selected < 0 || selected >= parts.size() || parts.size() < 2) {
            return;
        }
        final List<MatchPart> next = new ArrayList<>(parts);
        next.remove(selected);
        apply(next, Math.min(selected, next.size() - 1));
    }

    private void onMove(final int by) {
        final int to = selected + by;
        if (readOnly || selected < 0 || to < 0 || to >= parts.size()) {
            return;
        }
        final List<MatchPart> next = new ArrayList<>(parts);
        next.add(to, next.remove(selected));
        apply(next, to);
    }

    private void apply(final List<MatchPart> next, final int select) {
        final Template template = host.template(templateId);
        if (template == null) {
            return;
        }
        final MatchExpression.Parts match = new MatchExpression.Parts(next);
        selected = select;
        host.replace(host.withTemplate(Templates.withMatch(template, match)));
    }

    private void render() {
        final SafeHtmlBuilder sb = new SafeHtmlBuilder();
        sb.appendHtmlConstant("<ol class=\"parts parts--editable\">");
        for (int i = 0; i < parts.size(); i++) {
            final MatchPart part = parts.get(i);
            sb.appendHtmlConstant("<li class=\"" + (i == selected
                    ? "parts__item--selected"
                    : "") + "\" data-index=\"" + i + "\">");
            sb.appendHtmlConstant("<span class=\"parts__kind\">").appendEscaped(kind(part))
                    .appendHtmlConstant("</span> <span class=\"parts__node-text\">")
                    .appendEscaped(Templates.describe(part))
                    .appendHtmlConstant("</span>");
            if (part instanceof MatchPart.Pattern pattern) {
                sb.appendHtmlConstant("<ul class=\"parts__tree\">");
                renderNode(sb, pattern.node());
                sb.appendHtmlConstant("</ul>");
            }
            sb.appendHtmlConstant("</li>");
        }
        sb.appendHtmlConstant("</ol>");
        view.setParts(sb.toSafeHtml());
        enableButtons();
    }

    private static String kind(final MatchPart part) {
        if (part instanceof MatchPart.Pattern) {
            return "pattern";
        } else if (part instanceof MatchPart.Take) {
            return "take";
        } else if (part instanceof MatchPart.Seek) {
            return "seek";
        }
        return "read";
    }

    private static void renderNode(final SafeHtmlBuilder sb, final PatternNode node) {
        sb.appendHtmlConstant("<li class=\"parts__node\"><span class=\"parts__node-text\">")
                .appendEscaped(Templates.describe(node))
                .appendHtmlConstant("</span>");
        final List<PatternNode> children = Templates.children(node);
        if (!children.isEmpty()) {
            sb.appendHtmlConstant("<ul>");
            for (final PatternNode child : children) {
                renderNode(sb, child);
            }
            sb.appendHtmlConstant("</ul>");
        }
        sb.appendHtmlConstant("</li>");
    }

}
