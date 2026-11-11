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

import stroom.alert.client.event.AlertEvent;
import stroom.dispatch.client.RestError;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.model.MatchKind;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.ProjectText;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.shared.ShapeshifterPatternRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.EnumMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/// The match editor (design 44 §1): the match's **kind** as one choice, and beneath it the
/// form that kind has — regex, pattern tree, parts, delimiter — or, for source, all and named,
/// a sentence saying what the kind means, since they have nothing to set. The body always shows
/// the template's match as the model holds it; choosing another kind converts the match to that
/// kind where the engine can say what it means — a regex explodes into the tree (`explode`),
/// the tree prints back to a regex (`print`), either wraps into a one-part sequence, and
/// the plain kinds replace outright — and refuses where it cannot, the picker springing back.
public class MatchEditorController extends Controller {

    // From Contexts.READ_ONLY: true until the document says otherwise, so a
    // controller with no owner fails closed rather than editable.
    private boolean readOnly = true;

    private final MatchEditor view;

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final RestFactory restFactory;
    private final RegexController regexForm;
    private final PatternTreeController treeForm;
    private final PartsController partsForm;
    private final DelimiterController delimiterForm;

    private ProjectHost host;
    /// What each kind last held for this subject (design 44 §1): the picker is a choice among
    /// kinds, and moving through one to reach another - a regex to a source and back - should
    /// not cost the author what they had typed. Kept for the workbench session only: cleared
    /// when the workbench closes or retargets, since a kept regex is this template's, not the
    /// next one's. What the document holds is still only the current kind; the rest is a draft.
    private final EnumMap<MatchKind, MatchExpression> kept = new EnumMap<>(MatchKind.class);

    private String templateId;
    private MatchKind shown;

    @Inject
    public MatchEditorController(final EventBus eventBus,
                                final MatchEditor view,
                                final RestFactory restFactory,
                                final RegexController regexForm,
                                final PatternTreeController treeForm,
                                final PartsController partsForm,
                                final DelimiterController delimiterForm) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        watch(Contexts.READ_ONLY, readOnly -> this.readOnly = readOnly);
        view.setOnKind(this::onKind);
        this.restFactory = restFactory;
        this.regexForm = own(regexForm);
        this.treeForm = own(treeForm);
        this.partsForm = own(partsForm);
        this.delimiterForm = own(delimiterForm);
        regexForm.setOnExplode(() -> convert(MatchKind.TREE));
    }

    /// Told the group the regex map isolates, 0 for none.
    public void setOnGroupSelect(final IntConsumer onGroupSelect) {
        regexForm.setOnGroupSelect(onGroupSelect);
    }

    /// Told the label of the tree node selected, null for an unlabelled one or none.
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

    /// A part of the library as the subject (design 44 §3): a tree, so the tree form and no kind to choose.
    public void setPattern(final String name) {
        this.templateId = null;
        shown = null;
        kept.clear();
        view.showKind(false);
        view.setBody(treeForm);
        treeForm.setPattern(name);
    }

    public void setTemplate(final String id) {
        if (!id.equals(templateId)) {
            kept.clear();
        }
        this.templateId = id;
        final Template template = host.template(id);
        if (template == null) {
            return;
        }
        final MatchKind kind = MatchKind.of(template.match());
        // Every reading of the subject refreshes the draft of the kind it is in, so what a
        // kind holds is what it last held here, edits and all.
        kept.put(kind, template.match());
        view.showKind(true);
        view.setReadOnly(readOnly);
        view.setKind(kind);
        if (kind != shown) {
            shown = kind;
            final MatchForm form = form(kind);
            if (form != null) {
                view.setBody(form.asWidget());
            } else {
                view.setNote(meaning(kind));
            }
        }
        final MatchForm form = form(kind);
        if (form != null) {
            form.setTemplate(id);
        }
    }

    /// The form a kind is edited in, or null for a kind with nothing to set.
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
        if (readOnly) {
            view.setKind(current);
            return;
        }
        convert(kind);
    }

    /// Re-express the template's match as a kind, through the engine where needed. A kind that
    /// holds no pattern — source, all, named, delimiter — arrives blank, in either direction:
    /// choosing a kind is saying what the match is, and what the old kind held is gone whichever
    /// way round the choice goes. The one refusal left is a match that does hold a pattern the
    /// new kind cannot carry: a sequence of several parts, whose framing verbs no single pattern
    /// expresses.
    private void convert(final MatchKind kind) {
        final Template template = host.template(templateId);
        if (template == null || readOnly) {
            return;
        }
        final MatchExpression match = template.match();
        final PatternNode node = Templates.singlePattern(match);
        if (node == null && Templates.holdsPattern(match) && kind.holdsPattern()) {
            refuse(kind, match);
            return;
        }
        if (node == null || !kind.holdsPattern()) {
            // Nothing to carry into the kind: what it last held this session, else blank.
            kept.put(MatchKind.of(match), match);
            replace(template, kept.getOrDefault(kind, Templates.blank(kind)));
            return;
        }
        switch (kind) {
            case TREE:
                if (match instanceof MatchExpression.Regex regex) {
                    explode(regex, exploded -> replace(template, new MatchExpression.Pattern(exploded)),
                            error -> failed(kind, match, error));
                } else {
                    replace(template, new MatchExpression.Pattern(node));
                }
                break;
            case REGEX:
                if (node instanceof PatternNode.Regex regex) {
                    replace(template, new MatchExpression.Regex(regex.pattern(), regex.flags(), 0));
                } else {
                    print(node, text -> replace(template, new MatchExpression.Regex(text, null, 0)),
                            error -> failed(kind, match, error));
                }
                break;
            case PARTS:
                replace(template, new MatchExpression.Parts(List.of(new MatchPart.Pattern(node))));
                break;
            default:
                break;
        }
    }

    private void replace(final Template template, final MatchExpression match) {
        host.replace(host.withTemplate(Templates.withMatch(template, match)));
    }

    private void refuse(final MatchKind kind, final MatchExpression match) {
        view.setKind(MatchKind.of(match));
        AlertEvent.fireWarn(this, "A match of several parts cannot be re-expressed as a " + kind.label()
                                  + ": its takes, seeks and reads are not a single pattern. Reduce it to one "
                                  + "pattern part first, or edit it in the Source tab.", null);
    }

    /// The engine would not convert: the match is as it was, so the picker says so again.
    private void failed(final MatchKind kind, final MatchExpression match, final RestError error) {
        view.setKind(MatchKind.of(match));
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
                .owner(this)
                .exec();
    }

    private void print(final PatternNode node, final Consumer<String> then, final RestErrorHandler otherwise) {
        restFactory
                .create(RESOURCE)
                .method(res -> res.print(new ShapeshifterPatternRequest(ProjectText.printPatternNode(node),
                        false, false, ProjectText.printPatterns(host.getProject()))))
                .onSuccess(result -> then.accept(result.getText()))
                .onFailure(otherwise)
                .owner(this)
                .exec();
    }

    /// The workbench closed: the drafts of the kinds not chosen go with it.
    public void forget() {
        kept.clear();
    }

    /// One kind's form: shown for a template, refreshed after every edit of it.
    interface MatchForm extends IsWidget {

        void setTemplate(String id);
    }

}
