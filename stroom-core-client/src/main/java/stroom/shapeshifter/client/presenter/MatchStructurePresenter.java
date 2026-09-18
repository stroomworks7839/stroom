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

import stroom.dispatch.client.RestFactory;
import stroom.editor.client.presenter.EditorPresenter;
import stroom.shapeshifter.client.presenter.MatchStructurePresenter.MatchStructureView;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.shared.ShapeshifterPatternRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.util.client.DelayedUpdate;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

/**
 * The structured match kinds — the pattern tree, the match sequence of parts, and any other kind
 * as its wire form — each as a rendering of its structure above an editor of its JSON, the same
 * form the Source tab holds. Edits commit on a debounce when the text reads as the kind the tab
 * edits; while it does not, the error is shown and the model keeps the last good value. The tree
 * tab also prints the tree back as the regex it means, live, through {@code print} (design 43 §5).
 *
 * <p>A nested node editor over the vocabulary is design 18 §10's ask for this tab; this is its
 * first cut — the structure rendered, the wire form edited — and the renderer is where the node
 * editor grows.
 */
public class MatchStructurePresenter
        extends MyPresenterWidget<MatchStructureView>
        implements MatchEditorPresenter.MatchTab {

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    public enum Mode {
        TREE,
        PARTS,
        ANY
    }

    private final RestFactory restFactory;
    private final EditorPresenter editor;
    private final DelayedUpdate commit;

    private Mode mode = Mode.ANY;
    private ProjectHost host;
    private String templateId;
    /** The canonical text of the match last handed to, or received from, the host. */
    private String committed;
    private String printed;

    @Inject
    public MatchStructurePresenter(final EventBus eventBus,
                                   final MatchStructureView view,
                                   final RestFactory restFactory,
                                   final Provider<EditorPresenter> editorProvider) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.editor = editorProvider.get();
        this.commit = new DelayedUpdate(400, this::commit);
        editor.setMode(AceEditorMode.JSON);
        editor.getFormatAction().setAvailable(false);
        view.setEditor(editor.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(editor.addValueChangeHandler(event -> commit.update()));
    }

    public void setMode(final Mode mode) {
        this.mode = mode;
        getView().setRegexVisible(mode == Mode.TREE);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    @Override
    public void setTemplate(final String id) {
        if (!id.equals(templateId)) {
            commit.reset();
        }
        this.templateId = id;
        final Template template = host.template(id);
        if (template == null) {
            return;
        }
        editor.setReadOnly(host.isReadOnly());
        final MatchExpression match = template.match();
        final String text = print(match);
        if (text == null) {
            getView().setError("This tab edits a " + mode.name().toLowerCase() + " match; the template's is "
                               + Templates.kind(match));
            return;
        }
        getView().setError(null);
        if (!text.equals(committed)) {
            committed = text;
            editor.setText(text);
        }
        render(match);
    }

    /** The match in the form this tab edits, or null when it is not of that kind. */
    private String print(final MatchExpression match) {
        if (mode == Mode.TREE) {
            return match instanceof MatchExpression.Pattern pattern
                    ? ProjectText.printPatternNode(pattern.node())
                    : null;
        } else if (mode == Mode.PARTS) {
            return match instanceof MatchExpression.Parts
                    ? ProjectText.printMatch(match)
                    : null;
        }
        return ProjectText.printMatch(match);
    }

    private MatchExpression parse(final String text) {
        if (mode == Mode.TREE) {
            return new MatchExpression.Pattern(ProjectText.parsePatternNode(text));
        }
        final MatchExpression match = ProjectText.parseMatch(text);
        if (mode == Mode.PARTS && !(match instanceof MatchExpression.Parts)) {
            throw new ConfigException("The parts tab edits a match sequence: {\"parts\": [...]}");
        }
        return match;
    }

    private void commit() {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly()) {
            return;
        }
        final MatchExpression match;
        try {
            match = parse(editor.getText());
        } catch (final ConfigException e) {
            getView().setError(e.getMessage());
            return;
        }
        getView().setError(null);
        if (match.equals(template.match())) {
            return;
        }
        committed = print(match);
        host.replace(host.withTemplate(Templates.withMatch(template, match)));
    }

    private void render(final MatchExpression match) {
        final SafeHtmlBuilder sb = new SafeHtmlBuilder();
        if (match instanceof MatchExpression.Pattern pattern) {
            sb.appendHtmlConstant("<ul class=\"shapeshifter-tree\">");
            renderNode(sb, pattern.node());
            sb.appendHtmlConstant("</ul>");
            printRegex(pattern.node());
        } else if (match instanceof MatchExpression.Parts parts) {
            sb.appendHtmlConstant("<ol class=\"shapeshifter-parts\">");
            for (final MatchPart part : parts.parts()) {
                sb.appendHtmlConstant("<li>");
                if (part instanceof MatchPart.Pattern pattern) {
                    sb.appendHtmlConstant("<span class=\"shapeshifter-kind\">pattern</span>"
                                          + "<ul class=\"shapeshifter-tree\">");
                    renderNode(sb, pattern.node());
                    sb.appendHtmlConstant("</ul>");
                } else {
                    sb.appendEscaped(Templates.describe(part));
                }
                sb.appendHtmlConstant("</li>");
            }
            sb.appendHtmlConstant("</ol>");
        } else {
            sb.appendHtmlConstant("<div class=\"shapeshifter-kind\">")
                    .appendEscaped(Templates.kind(match))
                    .appendHtmlConstant("</div><div>")
                    .appendEscaped(Templates.describe(match))
                    .appendHtmlConstant("</div>");
        }
        getView().setStructure(sb.toSafeHtml());
    }

    private static void renderNode(final SafeHtmlBuilder sb, final PatternNode node) {
        final boolean labelled = node instanceof PatternNode.Labelled;
        sb.appendHtmlConstant(labelled
                ? "<li class=\"shapeshifter-node shapeshifter-node--labelled\">"
                : "<li class=\"shapeshifter-node\">");
        sb.appendHtmlConstant("<span class=\"shapeshifter-node-text\">")
                .appendEscaped(Templates.describe(node))
                .appendHtmlConstant("</span>");
        final java.util.List<PatternNode> children = Templates.children(node);
        if (!children.isEmpty()) {
            sb.appendHtmlConstant("<ul>");
            for (final PatternNode child : children) {
                renderNode(sb, child);
            }
            sb.appendHtmlConstant("</ul>");
        }
        sb.appendHtmlConstant("</li>");
    }

    private void printRegex(final PatternNode node) {
        final String text = ProjectText.printPatternNode(node);
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

    public interface MatchStructureView extends View {

        void setEditor(View view);

        void setStructure(com.google.gwt.safehtml.shared.SafeHtml html);

        void setRegexVisible(boolean visible);

        /** The regex the tree means, as the engine prints it. */
        void setRegex(String regex);

        void setError(String error);
    }
}
