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
import stroom.dispatch.client.RestError;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.presenter.MatchEditorPresenter.MatchEditorView;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.shared.ShapeshifterPatternRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The match editor (design 44 §1): the match's <b>kind</b> as one choice, and beneath it the
 * form that kind has — regex, pattern tree, parts, delimiter — or, for source, all and named,
 * a sentence saying what the kind means, since they have nothing to set. The body always shows
 * the template's match as the model holds it; choosing another kind converts the match to that
 * kind where the engine can say what it means — a regex explodes into the tree ({@code explode}),
 * the tree prints back to a regex ({@code print}), either wraps into a one-part sequence, and
 * the plain kinds replace outright — and refuses where it cannot, the picker springing back.
 */
public class MatchEditorPresenter
        extends MyPresenterWidget<MatchEditorView>
        implements MatchEditorUiHandlers {

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final RestFactory restFactory;
    private final RegexPresenter regexForm;
    private final PatternTreePresenter treeForm;
    private final PartsPresenter partsForm;
    private final DelimiterPresenter delimiterForm;

    private ProjectHost host;
    private String templateId;
    private MatchKind shown;

    @Inject
    public MatchEditorPresenter(final EventBus eventBus,
                                final MatchEditorView view,
                                final RestFactory restFactory,
                                final RegexPresenter regexForm,
                                final PatternTreePresenter treeForm,
                                final PartsPresenter partsForm,
                                final DelimiterPresenter delimiterForm) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.regexForm = regexForm;
        this.treeForm = treeForm;
        this.partsForm = partsForm;
        this.delimiterForm = delimiterForm;
        view.setUiHandlers(this);
        regexForm.setOnExplode(() -> convert(MatchKind.TREE));
    }

    /** Told the group the regex map isolates, 0 for none. */
    public void setOnGroupSelect(final IntConsumer onGroupSelect) {
        regexForm.setOnGroupSelect(onGroupSelect);
    }

    /** Told the label of the tree node selected, null for an unlabelled one or none. */
    public void setOnLabelSelect(final Consumer<String> onLabelSelect) {
        treeForm.setOnLabelSelect(onLabelSelect);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        regexForm.setHost(host);
        treeForm.setHost(host);
        partsForm.setHost(host);
        delimiterForm.setHost(host);
    }

    /** A part of the library as the subject (design 44 §3): a tree, so the tree form and no kind to choose. */
    public void setPattern(final String name) {
        this.templateId = null;
        shown = null;
        getView().showKind(false);
        getView().setBody(treeForm.getView());
        treeForm.setPattern(name);
    }

    public void setTemplate(final String id) {
        this.templateId = id;
        final Template template = host.template(id);
        if (template == null) {
            return;
        }
        final MatchKind kind = MatchKind.of(template.match());
        getView().showKind(true);
        getView().setEnabled(!host.isReadOnly());
        getView().setKind(kind);
        if (kind != shown) {
            shown = kind;
            final MatchForm form = form(kind);
            if (form != null) {
                getView().setBody(((MyPresenterWidget<?>) form).getView());
            } else {
                getView().setNote(meaning(kind));
            }
        }
        final MatchForm form = form(kind);
        if (form != null) {
            form.setTemplate(id);
        }
    }

    /** The form a kind is edited in, or null for a kind with nothing to set. */
    private MatchForm form(final MatchKind kind) {
        switch (kind) {
            case REGEX:
                return regexForm;
            case TREE:
                return treeForm;
            case PARTS:
                return partsForm;
            case DELIMITER:
                return delimiterForm;
            default:
                return null;
        }
    }

    private static String meaning(final MatchKind kind) {
        switch (kind) {
            case SOURCE:
                return "The document itself, matched exactly once. The body's instructions before apply-templates "
                       + "are written once at the start of the output, those after it once at the end, and the "
                       + "apply-templates is the loop over the input.";
            case ALL:
                return "Everything handed to this template is the match, as group 0. Not valid at the root.";
            case NAMED:
                return "Invoked by name only, with a call; never a candidate for apply-templates.";
            default:
                return "";
        }
    }

    @Override
    public void onKind(final MatchKind kind) {
        if (templateId == null) {
            return;
        }
        final Template template = host.template(templateId);
        if (template == null) {
            return;
        }
        final MatchKind current = MatchKind.of(template.match());
        if (kind == current) {
            return;
        }
        if (host.isReadOnly()) {
            getView().setKind(current);
            return;
        }
        convert(kind);
    }

    /** Re-express the template's match as a kind, through the engine where needed. */
    private void convert(final MatchKind kind) {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly()) {
            return;
        }
        final MatchExpression match = template.match();
        switch (kind) {
            case TREE:
                if (match instanceof MatchExpression.Regex regex) {
                    explode(regex, node -> replace(template, new MatchExpression.Pattern(node)),
                            error -> failed(kind, match, error));
                } else if (singlePattern(match) != null) {
                    replace(template, new MatchExpression.Pattern(singlePattern(match)));
                } else {
                    refuse(kind, match);
                }
                break;
            case REGEX:
                final PatternNode node = match instanceof MatchExpression.Pattern pattern
                        ? pattern.node()
                        : singlePattern(match);
                if (node instanceof PatternNode.Regex regex) {
                    replace(template, new MatchExpression.Regex(regex.pattern(), regex.flags(), 0));
                } else if (node != null) {
                    print(node, text -> replace(template, new MatchExpression.Regex(text, null, 0)),
                            error -> failed(kind, match, error));
                } else {
                    refuse(kind, match);
                }
                break;
            case PARTS:
                if (match instanceof MatchExpression.Regex regex) {
                    replace(template, new MatchExpression.Parts(List.of(
                            new MatchPart.Pattern(new PatternNode.Regex(regex.pattern(), regex.flags())))));
                } else if (match instanceof MatchExpression.Pattern pattern) {
                    replace(template, new MatchExpression.Parts(List.of(new MatchPart.Pattern(pattern.node()))));
                } else {
                    refuse(kind, match);
                }
                break;
            case DELIMITER:
                replace(template, DelimiterPresenter.DEFAULT);
                break;
            case SOURCE:
                replace(template, new MatchExpression.Source());
                break;
            case ALL:
                replace(template, new MatchExpression.All());
                break;
            case NAMED:
                replace(template, new MatchExpression.Named());
                break;
            default:
                break;
        }
    }

    /** The one pattern of a one-part sequence, or null. */
    private static PatternNode singlePattern(final MatchExpression match) {
        if (match instanceof MatchExpression.Parts parts
            && parts.parts().size() == 1
            && parts.parts().get(0) instanceof MatchPart.Pattern pattern) {
            return pattern.node();
        }
        return null;
    }

    private void replace(final Template template, final MatchExpression match) {
        host.replace(host.withTemplate(Templates.withMatch(template, match)));
    }

    private void refuse(final MatchKind kind, final MatchExpression match) {
        getView().setKind(MatchKind.of(match));
        AlertEvent.fireWarn(this, "A " + Templates.kind(match) + " match cannot be re-expressed as a "
                                  + kind.label() + "; edit it as its own kind, or in the Source tab.",
                null);
    }

    /** The engine would not convert: the match is as it was, so the picker says so again. */
    private void failed(final MatchKind kind, final MatchExpression match, final RestError error) {
        getView().setKind(MatchKind.of(match));
        AlertEvent.fireError(this, "The " + Templates.kind(match) + " match could not be re-expressed as a "
                                   + kind.label() + ": " + error.getMessage(), null);
    }

    private void explode(final MatchExpression.Regex regex,
                         final Consumer<PatternNode> then,
                         final RestErrorHandler otherwise) {
        restFactory
                .create(RESOURCE)
                .method(res -> res.explode(new ShapeshifterPatternRequest(regex.pattern(),
                        regex.flags().caseInsensitive(), regex.flags().dotAll())))
                .onSuccess(result -> then.accept(ProjectText.parsePatternNode(result.getText())))
                .onFailure(otherwise)
                .taskMonitorFactory(this)
                .exec();
    }

    private void print(final PatternNode node, final Consumer<String> then, final RestErrorHandler otherwise) {
        restFactory
                .create(RESOURCE)
                .method(res -> res.print(new ShapeshifterPatternRequest(ProjectText.printPatternNode(node),
                        false, false, ProjectText.printPatterns(host.getProject()))))
                .onSuccess(result -> then.accept(result.getText()))
                .onFailure(otherwise)
                .taskMonitorFactory(this)
                .exec();
    }

    /** One kind's form: shown for a template, refreshed after every edit of it. */
    interface MatchForm {

        void setTemplate(String id);
    }

    public interface MatchEditorView extends View, HasUiHandlers<MatchEditorUiHandlers> {

        void setEnabled(boolean enabled);

        /** The picker's choice, without telling the handlers. */
        void setKind(MatchKind kind);

        /** The kind row is a template's; a library part has none. */
        void showKind(boolean shown);

        /** The kind's form as the body. */
        void setBody(View form);

        /** A sentence as the body, for a kind with nothing to set. */
        void setNote(String text);
    }
}
