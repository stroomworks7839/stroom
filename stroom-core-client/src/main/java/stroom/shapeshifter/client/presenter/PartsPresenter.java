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

import stroom.editor.client.presenter.EditorPresenter;
import stroom.shapeshifter.client.presenter.PartsPresenter.PartsView;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Template;
import stroom.svg.client.Preset;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DelayedUpdate;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MouseUtil;

import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

import java.util.ArrayList;
import java.util.List;

/**
 * The parts tab (design 39): the match sequence as rows — pattern, take, seek, read — with a
 * toolbar acting on the selected row: add after, edit, remove, up, down; each a rewrite landing
 * on the host as a replacement match. The wire form stays editable to the right, as on the
 * tree tab. A sequence needs at least one part, so the last cannot be removed.
 */
public class PartsPresenter
        extends MyPresenterWidget<PartsView>
        implements PartsUiHandlers, MatchEditorPresenter.MatchTab {

    private final PartEditPresenter partEditor;
    private final EditorPresenter editor;
    private final DelayedUpdate commit;
    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView removeButton;
    private final ButtonView upButton;
    private final ButtonView downButton;

    private ProjectHost host;
    private String templateId;
    private List<MatchPart> parts = List.of();
    private int selected = -1;
    private String committed;

    @Inject
    public PartsPresenter(final EventBus eventBus,
                          final PartsView view,
                          final PartEditPresenter partEditor,
                          final Provider<EditorPresenter> editorProvider) {
        super(eventBus, view);
        this.partEditor = partEditor;
        this.editor = editorProvider.get();
        this.commit = new DelayedUpdate(400, this::commit);
        editor.setMode(AceEditorMode.JSON);
        editor.getFormatAction().setAvailable(false);
        view.setUiHandlers(this);
        view.setEditor(editor.getView());
        addButton = view.addButton(SvgPresets.ADD.title("Add a part after the selected one"));
        editButton = view.addButton(SvgPresets.EDIT.title("Edit the selected part"));
        removeButton = view.addButton(SvgPresets.DELETE.title("Remove the selected part"));
        upButton = view.addButton(SvgPresets.UP.title("Move up: earlier in the sequence"));
        downButton = view.addButton(SvgPresets.DOWN.title("Move down: later in the sequence"));
        enableButtons();
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(editor.addValueChangeHandler(event -> commit.update()));
        registerHandler(addButton.addClickHandler(e -> {
            if (MouseUtil.isPrimary(e)) {
                onAdd();
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
            selected = -1;
        }
        this.templateId = id;
        final Template template = host.template(id);
        if (template == null || !(template.match() instanceof MatchExpression.Parts sequence)) {
            return;
        }
        editor.setReadOnly(host.isReadOnly());
        parts = sequence.parts();
        if (selected >= parts.size()) {
            selected = parts.size() - 1;
        }
        final String text = ProjectText.printMatch(sequence);
        if (!text.equals(committed)) {
            committed = text;
            editor.setText(text);
        }
        getView().setError(null);
        render();
    }

    @Override
    public void onSelect(final int index) {
        selected = index >= 0 && index < parts.size()
                ? index
                : -1;
        render();
    }

    @Override
    public void onOpen(final int index) {
        onSelect(index);
        onEdit();
    }

    private void enableButtons() {
        final boolean editable = host != null && !host.isReadOnly() && templateId != null;
        final boolean has = selected >= 0 && selected < parts.size();
        addButton.setEnabled(editable);
        editButton.setEnabled(editable && has);
        removeButton.setEnabled(editable && has && parts.size() > 1);
        upButton.setEnabled(editable && has && selected > 0);
        downButton.setEnabled(editable && has && selected < parts.size() - 1);
    }

    private void onAdd() {
        if (host.isReadOnly() || templateId == null) {
            return;
        }
        final int at = selected < 0
                ? parts.size()
                : selected + 1;
        partEditor.read(null);
        partEditor.show("New Part", e -> {
            if (e.isOk()) {
                final MatchPart part = partEditor.write();
                if (part != null) {
                    final List<MatchPart> next = new ArrayList<>(parts);
                    next.add(at, part);
                    apply(next, at);
                    e.hide();
                }
            } else {
                e.hide();
            }
        });
    }

    private void onEdit() {
        if (host.isReadOnly() || selected < 0 || selected >= parts.size()) {
            return;
        }
        final int at = selected;
        partEditor.read(parts.get(at));
        partEditor.show("Edit Part", e -> {
            if (e.isOk()) {
                final MatchPart part = partEditor.write();
                if (part != null) {
                    final List<MatchPart> next = new ArrayList<>(parts);
                    next.set(at, part);
                    apply(next, at);
                    e.hide();
                }
            } else {
                e.hide();
            }
        });
    }

    private void onRemove() {
        if (host.isReadOnly() || selected < 0 || selected >= parts.size() || parts.size() < 2) {
            return;
        }
        final List<MatchPart> next = new ArrayList<>(parts);
        next.remove(selected);
        apply(next, Math.min(selected, next.size() - 1));
    }

    private void onMove(final int by) {
        final int to = selected + by;
        if (host.isReadOnly() || selected < 0 || to < 0 || to >= parts.size()) {
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
        committed = ProjectText.printMatch(match);
        editor.setText(committed);
        host.replace(host.withTemplate(Templates.withMatch(template, match)));
    }

    private void commit() {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly()) {
            return;
        }
        final MatchExpression match;
        try {
            match = ProjectText.parseMatch(editor.getText());
            if (!(match instanceof MatchExpression.Parts)) {
                throw new ConfigException("The parts tab edits a match sequence: {\"parts\": [...]}");
            }
        } catch (final ConfigException e) {
            getView().setError(e.getMessage());
            return;
        }
        getView().setError(null);
        if (match.equals(template.match())) {
            return;
        }
        committed = ProjectText.printMatch(match);
        host.replace(host.withTemplate(Templates.withMatch(template, match)));
    }

    private void render() {
        final SafeHtmlBuilder sb = new SafeHtmlBuilder();
        sb.appendHtmlConstant("<ol class=\"shapeshifter-parts ss-parts--editable\">");
        for (int i = 0; i < parts.size(); i++) {
            final MatchPart part = parts.get(i);
            sb.appendHtmlConstant("<li class=\"ss-part" + (i == selected
                    ? " ss-part--sel"
                    : "") + "\" data-index=\"" + i + "\">");
            sb.appendHtmlConstant("<span class=\"shapeshifter-kind\">").appendEscaped(kind(part))
                    .appendHtmlConstant("</span> <span class=\"shapeshifter-node-text\">")
                    .appendEscaped(Templates.describe(part))
                    .appendHtmlConstant("</span>");
            if (part instanceof MatchPart.Pattern pattern) {
                sb.appendHtmlConstant("<ul class=\"shapeshifter-tree\">");
                renderNode(sb, pattern.node());
                sb.appendHtmlConstant("</ul>");
            }
            sb.appendHtmlConstant("</li>");
        }
        sb.appendHtmlConstant("</ol>");
        getView().setParts(sb.toSafeHtml());
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
        sb.appendHtmlConstant("<li class=\"shapeshifter-node\"><span class=\"shapeshifter-node-text\">")
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

    public interface PartsView extends View, HasUiHandlers<PartsUiHandlers> {

        ButtonView addButton(Preset preset);

        void setParts(SafeHtml html);

        void setError(String error);

        void setEditor(View view);
    }
}
