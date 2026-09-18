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
import stroom.shapeshifter.client.presenter.OtherMatchPresenter.OtherMatchView;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.Template;
import stroom.util.client.DelayedUpdate;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

/**
 * The Other tab: the match kinds without a form of their own — <b>source</b> (the whole
 * document), <b>all</b>, <b>named</b>, and <b>delimiter</b> with its four fields — as a kind
 * picker, over the match's wire form, which is what this tab has always been: a view of any
 * match, editable. Picking a kind replaces the match; the wire form commits on a debounce.
 */
public class OtherMatchPresenter
        extends MyPresenterWidget<OtherMatchView>
        implements OtherMatchUiHandlers, MatchEditorPresenter.MatchTab {

    /** The kinds the picker offers; a regex, tree or parts match shows as "other" here. */
    public enum Kind {
        SOURCE("source"),
        ALL("all"),
        NAMED("named"),
        DELIMITER("delimiter"),
        OTHER("(regex, tree or parts: see its own tab)");

        private final String spelling;

        Kind(final String spelling) {
            this.spelling = spelling;
        }

        public String spelling() {
            return spelling;
        }
    }

    private final EditorPresenter editor;
    private final DelayedUpdate commit;

    private ProjectHost host;
    private String templateId;
    private String committed;
    private boolean reading;

    @Inject
    public OtherMatchPresenter(final EventBus eventBus,
                               final OtherMatchView view,
                               final Provider<EditorPresenter> editorProvider) {
        super(eventBus, view);
        this.editor = editorProvider.get();
        this.commit = new DelayedUpdate(400, this::commit);
        editor.setMode(AceEditorMode.JSON);
        editor.getFormatAction().setAvailable(false);
        view.setUiHandlers(this);
        view.setEditor(editor.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(editor.addValueChangeHandler(event -> commit.update()));
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
        final MatchExpression match = template.match();
        editor.setReadOnly(host.isReadOnly());
        getView().setEnabled(!host.isReadOnly());
        reading = true;
        try {
            if (match instanceof MatchExpression.Delimiter d) {
                getView().setKind(Kind.DELIMITER);
                getView().setDelimiter(d.delimiter(), d.escape(), d.containerStart(), d.containerEnd());
            } else if (match instanceof MatchExpression.Source) {
                getView().setKind(Kind.SOURCE);
            } else if (match instanceof MatchExpression.All) {
                getView().setKind(Kind.ALL);
            } else if (match instanceof MatchExpression.Named) {
                getView().setKind(Kind.NAMED);
            } else {
                getView().setKind(Kind.OTHER);
            }
        } finally {
            reading = false;
        }
        final String text = ProjectText.printMatch(match);
        if (!text.equals(committed)) {
            committed = text;
            editor.setText(text);
        }
        getView().setError(null);
    }

    @Override
    public void onChange() {
        final Template template = host.template(templateId);
        if (reading || template == null || host.isReadOnly()) {
            return;
        }
        final MatchExpression match;
        switch (getView().getKind()) {
            case SOURCE:
                match = new MatchExpression.Source();
                break;
            case ALL:
                match = new MatchExpression.All();
                break;
            case NAMED:
                match = new MatchExpression.Named();
                break;
            case DELIMITER:
                final String delimiter = getView().getDelimiter();
                if (delimiter == null || delimiter.isEmpty()) {
                    getView().setError("A delimiter match needs its delimiter");
                    return;
                }
                match = new MatchExpression.Delimiter(delimiter, blankToNull(getView().getEscape()),
                        blankToNull(getView().getContainerStart()), blankToNull(getView().getContainerEnd()));
                break;
            default:
                // "Other" names what is already there; nothing to pick.
                return;
        }
        getView().setError(null);
        if (match.equals(template.match())) {
            return;
        }
        committed = ProjectText.printMatch(match);
        editor.setText(committed);
        host.replace(host.withTemplate(Templates.withMatch(template, match)));
    }

    private static String blankToNull(final String text) {
        return text == null || text.isEmpty()
                ? null
                : text;
    }

    private void commit() {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly()) {
            return;
        }
        final MatchExpression match;
        try {
            match = ProjectText.parseMatch(editor.getText());
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

    public interface OtherMatchView extends View, HasUiHandlers<OtherMatchUiHandlers> {

        void setEnabled(boolean enabled);

        Kind getKind();

        void setKind(Kind kind);

        String getDelimiter();

        String getEscape();

        String getContainerStart();

        String getContainerEnd();

        void setDelimiter(String delimiter, String escape, String containerStart, String containerEnd);

        void setError(String error);

        void setEditor(View view);
    }
}
