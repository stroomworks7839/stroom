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
import stroom.shapeshifter.client.presenter.RegexTabPresenter.RegexTabView;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.RegexFlags;
import stroom.shapeshifter.shared.ShapeshifterPatternInfo;
import stroom.shapeshifter.shared.ShapeshifterPatternInfo.Group;
import stroom.shapeshifter.shared.ShapeshifterPatternRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.util.client.DelayedUpdate;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;

/**
 * The regex tab (design 18 §5.6): the pattern, its two flags and its advance, committed live on a
 * debounce; beneath it what the engine says — validity and the error as you type, the groups
 * with their names, and the plan it would run ({@code patternInfo}). Pattern facts come from the
 * engine; the tab never reads the pattern itself. "Explode into tree" hands over to the tree tab.
 */
public class RegexTabPresenter
        extends MyPresenterWidget<RegexTabView>
        implements RegexTabUiHandlers, MatchEditorPresenter.MatchTab {

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final RestFactory restFactory;
    private final DelayedUpdate commit;

    private ProjectHost host;
    private String templateId;
    private Runnable onExplode;
    private ShapeshifterPatternRequest inspected;

    @Inject
    public RegexTabPresenter(final EventBus eventBus, final RegexTabView view, final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.commit = new DelayedUpdate(300, this::commit);
        view.setUiHandlers(this);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    public void setOnExplode(final Runnable onExplode) {
        this.onExplode = onExplode;
    }

    @Override
    public void setTemplate(final String id) {
        if (!id.equals(templateId)) {
            // Typing not yet committed belongs to the template that was showing; drop it.
            commit.reset();
        }
        this.templateId = id;
        final Template template = host.template(id);
        if (template == null || !(template.match() instanceof MatchExpression.Regex regex)) {
            return;
        }
        getView().setEnabled(!host.isReadOnly());
        // Only touch the fields when the model disagrees with them, so that a commit of the user's
        // own typing does not move the caret.
        if (!regex.pattern().equals(getView().getPattern())) {
            getView().setPattern(regex.pattern());
        }
        getView().setCaseInsensitive(regex.flags().caseInsensitive());
        getView().setDotAll(regex.flags().dotAll());
        if (regex.advance() != getView().getAdvance()) {
            getView().setAdvance(regex.advance());
        }
        inspect(regex);
    }

    @Override
    public void onChange() {
        commit.update();
    }

    @Override
    public void onExplode() {
        commit.reset();
        commit();
        if (onExplode != null) {
            onExplode.run();
        }
    }

    private void commit() {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly() || !(template.match() instanceof MatchExpression.Regex)) {
            return;
        }
        final MatchExpression.Regex regex = new MatchExpression.Regex(getView().getPattern(),
                new RegexFlags(getView().isCaseInsensitive(), getView().isDotAll()),
                Math.max(0, getView().getAdvance()));
        if (regex.equals(template.match())) {
            return;
        }
        host.replace(host.withTemplate(Templates.withMatch(template, regex)));
    }

    private void inspect(final MatchExpression.Regex regex) {
        final ShapeshifterPatternRequest request = new ShapeshifterPatternRequest(regex.pattern(),
                regex.flags().caseInsensitive(), regex.flags().dotAll());
        if (same(request, inspected)) {
            return;
        }
        inspected = request;
        if (regex.pattern().isEmpty()) {
            getView().setInfo("A template needs a pattern", List.of(), null);
            return;
        }
        restFactory
                .create(RESOURCE)
                .method(res -> res.patternInfo(request))
                .onSuccess(info -> {
                    if (same(request, inspected)) {
                        show(info);
                    }
                })
                .taskMonitorFactory(this)
                .exec();
    }

    private static boolean same(final ShapeshifterPatternRequest a, final ShapeshifterPatternRequest b) {
        return b != null
               && a.getPattern().equals(b.getPattern())
               && a.isCaseInsensitive() == b.isCaseInsensitive()
               && a.isDotAll() == b.isDotAll();
    }

    private void show(final ShapeshifterPatternInfo info) {
        getView().setInfo(info.isValid()
                ? null
                : info.getError(), info.getGroups() == null
                ? List.of()
                : info.getGroups(), info.getExplain());
    }

    public interface RegexTabView extends View, HasUiHandlers<RegexTabUiHandlers> {

        void setEnabled(boolean enabled);

        String getPattern();

        void setPattern(String pattern);

        boolean isCaseInsensitive();

        void setCaseInsensitive(boolean caseInsensitive);

        boolean isDotAll();

        void setDotAll(boolean dotAll);

        int getAdvance();

        void setAdvance(int advance);

        /** What the engine said: an error or null, the groups, and the plan (null while invalid). */
        void setInfo(String error, List<Group> groups, String explain);
    }
}
