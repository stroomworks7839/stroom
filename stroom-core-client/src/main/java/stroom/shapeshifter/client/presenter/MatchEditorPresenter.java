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
import stroom.dispatch.client.RestFactory;
import stroom.entity.client.presenter.LinkTabPanelView;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.shared.ShapeshifterPatternRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.widget.tab.client.presenter.TabData;
import stroom.widget.tab.client.presenter.TabDataImpl;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.List;

/**
 * The match editor (design 43 §4), tabbed by match kind: <b>Regex</b>, <b>Pattern tree</b>,
 * <b>Parts</b>, and <b>Other</b> for the kinds without a form of their own — delimiter, source,
 * all, named — as the wire form. The tab shown follows the template's match; choosing another
 * tab converts the match to that kind where the engine can say what it means — a regex explodes
 * into the tree ({@code explode}), the tree prints back to a regex ({@code print}), either wraps
 * into a one-part sequence — and refuses where it cannot.
 */
public class MatchEditorPresenter extends MyPresenterWidget<LinkTabPanelView> {

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    static final TabData REGEX = new TabDataImpl("Regex");
    static final TabData TREE = new TabDataImpl("Pattern tree");
    static final TabData PARTS = new TabDataImpl("Parts");
    static final TabData OTHER = new TabDataImpl("Other");

    private final RestFactory restFactory;
    private final RegexTabPresenter regexTab;
    private final PatternTreePresenter treeTab;
    private final MatchStructurePresenter partsTab;
    private final MatchStructurePresenter otherTab;

    private ProjectHost host;
    private String templateId;
    private TabData selectedTab;

    @Inject
    public MatchEditorPresenter(final EventBus eventBus,
                                final LinkTabPanelView view,
                                final RestFactory restFactory,
                                final RegexTabPresenter regexTab,
                                final PatternTreePresenter treeTab,
                                final Provider<MatchStructurePresenter> structureProvider) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.regexTab = regexTab;
        this.treeTab = treeTab;
        this.partsTab = structureProvider.get();
        this.otherTab = structureProvider.get();
        partsTab.setMode(MatchStructurePresenter.Mode.PARTS);
        otherTab.setMode(MatchStructurePresenter.Mode.ANY);
        regexTab.setOnExplode(() -> convert(TREE));
        view.getTabBar().addTab(REGEX);
        view.getTabBar().addTab(TREE);
        view.getTabBar().addTab(PARTS);
        view.getTabBar().addTab(OTHER);
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(getView().getTabBar().addSelectionHandler(event -> onTabChosen(event.getSelectedItem())));
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        regexTab.setHost(host);
        treeTab.setHost(host);
        partsTab.setHost(host);
        otherTab.setHost(host);
    }

    public void setTemplate(final String id) {
        this.templateId = id;
        final Template template = host.template(id);
        if (template == null) {
            return;
        }
        final TabData natural = tabFor(template.match());
        // A kind with a form of its own still shows as its wire form if that is what is open:
        // the Other tab is a view of any match, not a conversion of it.
        final TabData tab = selectedTab == OTHER
                ? OTHER
                : natural;
        show(tab);
        presenter(tab).setTemplate(id);
    }

    private static TabData tabFor(final MatchExpression match) {
        if (match instanceof MatchExpression.Regex) {
            return REGEX;
        } else if (match instanceof MatchExpression.Pattern) {
            return TREE;
        } else if (match instanceof MatchExpression.Parts) {
            return PARTS;
        }
        return OTHER;
    }

    private MatchTab presenter(final TabData tab) {
        if (tab == REGEX) {
            return regexTab;
        } else if (tab == TREE) {
            return treeTab;
        } else if (tab == PARTS) {
            return partsTab;
        }
        return otherTab;
    }

    private void show(final TabData tab) {
        if (tab != selectedTab) {
            selectedTab = tab;
            getView().getLayerContainer().show((MyPresenterWidget<?>) presenter(tab));
            getView().getTabBar().selectTab(tab);
        }
    }

    private void onTabChosen(final TabData tab) {
        if (tab == selectedTab || templateId == null) {
            return;
        }
        final Template template = host.template(templateId);
        if (template == null) {
            return;
        }
        if (tab == OTHER || tabFor(template.match()) == tab) {
            show(tab);
            presenter(tab).setTemplate(templateId);
            return;
        }
        if (host.isReadOnly()) {
            getView().getTabBar().selectTab(selectedTab);
            return;
        }
        convert(tab);
    }

    /** Re-express the template's match as the kind a tab edits, through the engine where needed. */
    private void convert(final TabData tab) {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly()) {
            return;
        }
        final MatchExpression match = template.match();
        if (tab == TREE) {
            if (match instanceof MatchExpression.Regex regex) {
                explode(regex, node -> replace(template, new MatchExpression.Pattern(node)));
            } else if (singlePattern(match) != null) {
                replace(template, new MatchExpression.Pattern(singlePattern(match)));
            } else {
                refuse(tab, match);
            }
        } else if (tab == REGEX) {
            final PatternNode node = match instanceof MatchExpression.Pattern pattern
                    ? pattern.node()
                    : singlePattern(match);
            if (node instanceof PatternNode.Regex regex) {
                replace(template, new MatchExpression.Regex(regex.pattern(), regex.flags(), 0));
            } else if (node != null) {
                print(node, text -> replace(template, new MatchExpression.Regex(text, null, 0)));
            } else {
                refuse(tab, match);
            }
        } else if (tab == PARTS) {
            if (match instanceof MatchExpression.Regex regex) {
                replace(template, new MatchExpression.Parts(List.of(
                        new MatchPart.Pattern(new PatternNode.Regex(regex.pattern(), regex.flags())))));
            } else if (match instanceof MatchExpression.Pattern pattern) {
                replace(template, new MatchExpression.Parts(List.of(new MatchPart.Pattern(pattern.node()))));
            } else {
                refuse(tab, match);
            }
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

    private void refuse(final TabData tab, final MatchExpression match) {
        getView().getTabBar().selectTab(selectedTab);
        AlertEvent.fireWarn(this, "A " + Templates.kind(match) + " match cannot be re-expressed as "
                                  + tab.getLabel().toLowerCase() + "; edit it as its own kind, or in the Source tab.",
                null);
    }

    private void explode(final MatchExpression.Regex regex, final java.util.function.Consumer<PatternNode> then) {
        restFactory
                .create(RESOURCE)
                .method(res -> res.explode(new ShapeshifterPatternRequest(regex.pattern(),
                        regex.flags().caseInsensitive(), regex.flags().dotAll())))
                .onSuccess(result -> then.accept(ProjectText.parsePatternNode(result.getText())))
                .taskMonitorFactory(this)
                .exec();
    }

    private void print(final PatternNode node, final java.util.function.Consumer<String> then) {
        restFactory
                .create(RESOURCE)
                .method(res -> res.print(new ShapeshifterPatternRequest(ProjectText.printPatternNode(node),
                        false, false)))
                .onSuccess(result -> then.accept(result.getText()))
                .taskMonitorFactory(this)
                .exec();
    }

    /** One tab of the match editor: shown for a template, refreshed after every edit of it. */
    interface MatchTab {

        void setTemplate(String id);
    }
}
