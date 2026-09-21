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
import stroom.shapeshifter.client.presenter.RegexPresenter.RegexView;
import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.RegexFlags;
import stroom.shapeshifter.shared.ShapeshifterPatternInfo;
import stroom.shapeshifter.shared.ShapeshifterPatternInfo.Group;
import stroom.shapeshifter.shared.ShapeshifterPatternRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.util.client.DelayedUpdate;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The regex form (design 18 §5.6): the pattern, committed live on a debounce, rendered twice —
 * the editable text and beneath it the <b>pattern map</b>, each capture group's span in its
 * hue; the <b>groups panel</b>, one row per group, which is the capture-declaration editor —
 * typing a name beside {@code $2} declares it and binds the group to it, blanking it unbinds;
 * and what the engine says, the error as you type and the plan it would run. Every fact about
 * the pattern — validity, groups, their spans and names — is the engine's ({@code patternInfo});
 * the form never reads the pattern itself.
 */
public class RegexPresenter
        extends MyPresenterWidget<RegexView>
        implements RegexUiHandlers, MatchEditorPresenter.MatchForm {

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final RestFactory restFactory;
    private final DelayedUpdate commit;

    private ProjectHost host;
    private String templateId;
    private Runnable onExplode;
    private ShapeshifterPatternRequest inspected;
    private ShapeshifterPatternInfo info;
    private int selectedGroup;

    @Inject
    public RegexPresenter(final EventBus eventBus, final RegexView view, final RestFactory restFactory) {
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
            selectedGroup = 0;
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
        // The groups panel reads the template's captures, which change without the pattern changing.
        if (info != null) {
            showGroups(template);
        }
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

    @Override
    public void onGroupSelect(final int index) {
        selectedGroup = selectedGroup == index
                ? 0
                : index;
        final Template template = host.template(templateId);
        if (template != null && info != null) {
            showGroups(template);
        }
    }

    /**
     * The groups panel is the capture-declaration editor (design 18 §5.6): a name beside a group
     * is a scalar declaration of that name and a capture binding the group into it; blank is no
     * capture. The declaration is left in place when a group is unbound or renamed - the body
     * may read it - and the messages say so if nothing does.
     */
    @Override
    public void onGroupName(final int index, final String typed) {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly()) {
            return;
        }
        final String name = typed == null
                ? ""
                : typed.trim();
        final List<CaptureBinding> captures = new ArrayList<>(template.captures());
        final List<Declaration> declarations = new ArrayList<>(template.declarations());
        final CaptureBinding existing = captureOf(template, index);
        if (name.isEmpty()) {
            if (existing == null) {
                return;
            }
            captures.remove(existing);
        } else {
            if (existing != null && name.equals(existing.name())) {
                return;
            }
            boolean declared = false;
            for (final Declaration declaration : declarations) {
                declared |= declaration.name().equals(name);
            }
            if (!declared) {
                declarations.add(new Declaration(name, Declaration.Type.SCALAR));
            }
            final CaptureBinding bound = new CaptureBinding(name, new CaptureSource.Group(index), existing == null
                    ? null
                    : existing.as());
            if (existing == null) {
                captures.add(bound);
            } else {
                captures.set(captures.indexOf(existing), bound);
            }
        }
        host.replace(host.withTemplate(Templates.withCaptures(Templates.withDeclarations(template, declarations),
                captures)));
    }

    private static CaptureBinding captureOf(final Template template, final int group) {
        for (final CaptureBinding capture : template.captures()) {
            if (capture.select() instanceof CaptureSource.Group g && g.group() == group) {
                return capture;
            }
        }
        return null;
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
        // Until the engine answers for this pattern, the last answer's groups and spans belong to
        // another pattern: show the text plain rather than paint it with the wrong spans.
        info = null;
        getView().setGroups(List.of());
        getView().setPatternMap(new SafeHtmlBuilder().appendEscaped(regex.pattern()).toSafeHtml());
        if (regex.pattern().isEmpty()) {
            info = new ShapeshifterPatternInfo(false, "A template needs a pattern", List.of(), null);
            show();
            return;
        }
        restFactory
                .create(RESOURCE)
                .method(res -> res.patternInfo(request))
                .onSuccess(result -> {
                    if (same(request, inspected)) {
                        info = result;
                        show();
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

    private void show() {
        final Template template = host.template(templateId);
        getView().setError(info.isValid()
                ? null
                : info.getError());
        getView().setExplain(info.getExplain());
        if (template != null) {
            showGroups(template);
        }
    }

    private void showGroups(final Template template) {
        final List<Group> groups = info.getGroups() == null
                ? List.of()
                : info.getGroups();
        final List<GroupRowData> rows = new ArrayList<>();
        for (final Group group : groups) {
            final CaptureBinding capture = captureOf(template, group.getIndex());
            rows.add(new GroupRowData(group.getIndex(), capture == null
                    ? null
                    : capture.name(), group.getName(), hue(group.getIndex()), group.getIndex() == selectedGroup));
        }
        getView().setGroups(rows);
        getView().setPatternMap(patternMap(inspected.getPattern(), groups));
    }

    /** A capture's hue, shared by its chip, its row and its span in the map (design 18 §10). */
    static String hue(final int index) {
        return "hsl(" + ((index - 1) * 47 % 360) + ", 62%, 58%)";
    }

    /**
     * The pattern with each character coloured by its innermost capture group, regex101's
     * inner-and-outer reading; a click on a span selects its group.
     */
    private SafeHtml patternMap(final String pattern, final List<Group> groups) {
        final SafeHtmlBuilder sb = new SafeHtmlBuilder();
        int runGroup = 0;
        int runStart = 0;
        for (int i = 0; i <= pattern.length(); i++) {
            int inner = 0;
            int innerWidth = Integer.MAX_VALUE;
            if (i < pattern.length()) {
                for (final Group group : groups) {
                    final int width = group.getEnd() - group.getStart();
                    if (i >= group.getStart() && i < group.getEnd() && width < innerWidth) {
                        inner = group.getIndex();
                        innerWidth = width;
                    }
                }
            }
            if (i == pattern.length() || inner != runGroup) {
                if (i > runStart) {
                    appendRun(sb, pattern.substring(runStart, i), runGroup);
                }
                runGroup = inner;
                runStart = i;
            }
        }
        return sb.toSafeHtml();
    }

    /** The attributes hold only numbers and an hsl() of our own making; the text is escaped. */
    private void appendRun(final SafeHtmlBuilder sb, final String text, final int group) {
        if (group == 0) {
            sb.appendEscaped(text);
            return;
        }
        final String hue = hue(group);
        sb.appendHtmlConstant("<span class=\"ss-pm-g" + (group == selectedGroup
                ? " ss-pm-g--sel"
                : "") + "\" data-group=\"" + group + "\" style=\"color:" + hue + ";border-bottom-color:" + hue
                              + (group == selectedGroup
                ? ";background-color:" + hue.replace(")", ", 0.3)").replace("hsl(", "hsla(")
                : "") + "\" title=\"group $" + group + "\">")
                .appendEscaped(text)
                .appendHtmlConstant("</span>");
    }

    public interface RegexView extends View, HasUiHandlers<RegexUiHandlers> {

        void setEnabled(boolean enabled);

        String getPattern();

        void setPattern(String pattern);

        boolean isCaseInsensitive();

        void setCaseInsensitive(boolean caseInsensitive);

        boolean isDotAll();

        void setDotAll(boolean dotAll);

        int getAdvance();

        void setAdvance(int advance);

        void setError(String error);

        void setPatternMap(SafeHtml html);

        void setGroups(List<GroupRowData> groups);

        /** The engine's account of the plan, or null while the pattern is invalid. */
        void setExplain(String explain);
    }
}
