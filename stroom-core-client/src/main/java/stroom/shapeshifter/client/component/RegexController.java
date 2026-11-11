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

import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.model.GroupRowData;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
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
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/// The regex form (design 18 §5.6): the pattern, committed live on a debounce, rendered twice —
/// the editable text and beneath it the **pattern map**, each capture group's span in its
/// hue; the **groups panel**, one row per group, which is the capture-declaration editor —
/// typing a name beside `$2` declares it and binds the group to it, blanking it unbinds;
/// and what the engine says, the error as you type and the plan it would run. Every fact about
/// the pattern — validity, groups, their spans and names — is the engine's (`patternInfo`);
/// the form never reads the pattern itself.
public class RegexController
        extends Controller
        implements MatchEditorController.MatchForm {

    // From Contexts.READ_ONLY: true until the document says otherwise, so a
    // controller with no owner fails closed rather than editable.
    private boolean readOnly = true;

    private final Regex view;

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final RestFactory restFactory;
    private final DelayedUpdate commit;

    private ProjectHost host;
    private String templateId;
    private Runnable onExplode;
    private IntConsumer onGroupSelect;
    private ShapeshifterPatternRequest inspected;
    private ShapeshifterPatternInfo info;
    private int selectedGroup;

    @Inject
    public RegexController(final EventBus eventBus, final Regex view, final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        watch(Contexts.READ_ONLY, readOnly -> this.readOnly = readOnly);
        view.setOnChange(this::onChange);
        view.setOnExplode(this::onExplode);
        view.setOnGroupName(this::onGroupName);
        view.setOnGroupSelect(this::onGroupSelect);
        this.restFactory = restFactory;
        this.commit = new DelayedUpdate(300, this::commit);
        onDispose(commit::reset);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    /// Told when the pattern is to be exploded into a framed sequence.
    ///
    /// @param onExplode The handler.
    public void setOnExplode(final Runnable onExplode) {
        this.onExplode = onExplode;
    }

    /// Told when a capture group is chosen, with its number.
    ///
    /// @param onGroupSelect The handler.
    public void setOnGroupSelect(final IntConsumer onGroupSelect) {
        this.onGroupSelect = onGroupSelect;
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
        view.setEnabled(!readOnly);
        // Only touch the fields when the model disagrees with them, so that a commit of the user's
        // own typing does not move the caret.
        if (!regex.pattern().equals(view.getPattern())) {
            view.setPattern(regex.pattern());
        }
        view.setCaseInsensitive(regex.flags().caseInsensitive());
        view.setDotAll(regex.flags().dotAll());
        if (regex.advance() != view.getAdvance()) {
            view.setAdvance(regex.advance());
        }
        inspect(regex);
        // The groups panel reads the template's captures, which change without the pattern changing.
        if (info != null) {
            showGroups(template);
        }
    }

    public void onChange() {
        commit.update();
    }

    public void onExplode() {
        commit.reset();
        commit();
        if (onExplode != null) {
            onExplode.run();
        }
    }

    public void onGroupSelect(final int index) {
        selectedGroup = selectedGroup == index
                ? 0
                : index;
        final Template template = host.template(templateId);
        if (template != null && info != null) {
            showGroups(template);
        }
        if (onGroupSelect != null) {
            onGroupSelect.accept(selectedGroup);
        }
    }

    /// The groups panel is the capture-declaration editor (design 18 §5.6): a name beside a group
    /// is a scalar declaration of that name and a capture binding the group into it; blank is no
    /// capture. The declaration is left in place when a group is unbound or renamed - the body
    /// may read it - and the messages say so if nothing does.
    public void onGroupName(final int index, final String typed) {
        final Template template = host.template(templateId);
        if (template == null || readOnly) {
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
        if (template == null || readOnly || !(template.match() instanceof MatchExpression.Regex)) {
            return;
        }
        final MatchExpression.Regex regex = new MatchExpression.Regex(view.getPattern(),
                new RegexFlags(view.isCaseInsensitive(), view.isDotAll()),
                Math.max(0, view.getAdvance()));
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
        view.setGroups(List.of());
        view.setPatternMap(new SafeHtmlBuilder().appendEscaped(regex.pattern()).toSafeHtml());
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
                .owner(this)
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
        view.setError(info.isValid()
                ? null
                : info.getError());
        view.setExplain(info.getExplain());
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
        view.setGroups(rows);
        view.setPatternMap(patternMap(inspected.getPattern(), groups));
    }

    /// A capture's hue, shared by its chip, its row and its span in the map (design 18 §10).
    static String hue(final int index) {
        // floorMod, not %: a caller counting from zero would otherwise produce a negative hue,
        // which Colours.safe rejects and paints transparent — one capture silently uncoloured
        // rather than a visibly wrong colour, which is the hardest kind of bug to notice.
        return "hsl(" + Math.floorMod((index - 1) * 47, 360) + ", 62%, 58%)";
    }

    /// The pattern with each character coloured by its innermost capture group, regex101's
    /// inner-and-outer reading; a click on a span selects its group.
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

    /// The attributes hold only numbers and an hsl() of our own making; the text is escaped. The
    /// group's colour goes in as a custom property, so the sheet decides what is done with it and
    /// the selected run is a class rather than a background written here.
    private void appendRun(final SafeHtmlBuilder sb, final String text, final int group) {
        if (group == 0) {
            sb.appendEscaped(text);
            return;
        }
        sb.appendHtmlConstant("<span class=\"regex__group" + (group == selectedGroup
                ? " regex__group--selected"
                : "") + "\" data-group=\"" + group + "\" style=\"--hue:" + hue(group)
                              + "\" title=\"group $" + group + "\">")
                .appendEscaped(text)
                .appendHtmlConstant("</span>");
    }

}
