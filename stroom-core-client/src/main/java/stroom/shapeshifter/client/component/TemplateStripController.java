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
import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.model.GuardClause;
import stroom.shapeshifter.client.model.Hot;
import stroom.shapeshifter.client.model.MatchKind;
import stroom.shapeshifter.client.model.MatchLine;
import stroom.shapeshifter.client.model.MatchRole;
import stroom.shapeshifter.client.model.Modes;
import stroom.shapeshifter.client.model.Profile;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.client.model.TraceModel;
import stroom.shapeshifter.config.Dispatch;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.shared.ShapeshifterPatternRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.user.client.Timer;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/// The template strip (design 18 §5.6): "what it is", beneath the panes that say what
/// happened. Its title line is the template's identity — swatch, name, mode, where it is
/// dispatched from, computed from the mode graph — and its body opens with the match as a
/// read-only chip that opens the pattern workbench, then guard and limits as a summary, then the
/// body as cards, with what it declares and captures listed above them.
/// For the project row the strip shows the document: its name and source settings.
public class TemplateStripController extends Controller {

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);
    // Long enough that a pattern typed at speed is asked about once, short enough to read as
    // an answer to what was typed.
    private static final int MATCH_INSPECT_DELAY_MS = 250;

    // From Contexts.READ_ONLY: true until the document says otherwise, so a
    // controller with no owner fails closed rather than editable.
    private boolean readOnly = true;

    private TemplateStrip view;

    private final SourceSettingsController sourceConfig;
    private final BodyController bodyPresenter;
    private final DeclarationsController declarations;
    private final CapturesController captures;
    // A template's settings, open under its name while its title is (the owner, 2026-11-18).
    // One for the strip's life, read afresh each time it opens, so the encodings it offers are
    // fetched once rather than each time a title opens (design 47 §7.8, finding 7).
    private final TemplateEditController settings;
    private final RestFactory restFactory;
    // The pattern last sent to the engine from the open match field: an answer for any other
    // arrived late and is not shown (design 47 §7.2, finding 14).
    private ShapeshifterPatternRequest matchInspected;
    // Whether the line's taking is being asked about: the question takes the keyboard from the
    // line, and that is not a second taking.
    private boolean asking;
    private final Timer matchTimer = new Timer() {
        @Override
        public void run() {
            inspectMatch();
        }
    };

    private ProjectHost host;
    private Consumer<String> onOpenWorkbench = templateId -> {
    };
    private Consumer<String> onOpenGuard = templateId -> {
    };
    private Consumer<String> onSelectTemplate = id -> {
    };
    private Consumer<String> onAddTemplate = mode -> {
    };
    private BiConsumer<String, String> onRenameMode = (from, to) -> {
    };
    // What is shown: a template, or a mode, or neither — the project. Never both.
    private String templateId;
    private String mode;
    // The mode's templates, by their place in its list
    private List<String> modeTemplates = List.of();
    // Whether a template's title is open, with its settings under its name: what they and the
    // name hold is a draft. A mode has a name and no settings.
    private boolean settingsOpen;
    // Whether what is being named was only just made: taking its name with Enter then goes on
    // to the next thing to say about it.
    private boolean fresh;

    @Inject
    public TemplateStripController(final EventBus eventBus,
                                  final SourceSettingsController sourceConfig,
                                  final BodyController bodyPresenter,
                                  final DeclarationsController declarations,
                                  final CapturesController captures,
                                  final TemplateEditController settings,
                                  final RestFactory restFactory) {
        super(eventBus);
        this.restFactory = restFactory;
        watch(Contexts.READ_ONLY, readOnly -> this.readOnly = readOnly);
        this.sourceConfig = own(sourceConfig);
        this.bodyPresenter = own(bodyPresenter);
        this.declarations = own(declarations);
        this.captures = own(captures);
        this.settings = own(settings);
        settings.setOnCommit(() -> takeTitle(false));
        settings.setOnCancel(this::dropTitle);
    }

    /// Attaches this to a strip, e.g. one a template declares, and its declarations and captures
    /// to the strip's pagers. The strip is then this controller's widget.
    ///
    /// @param view The strip.
    /// @return This, for its owner to keep.
    public TemplateStripController attach(final TemplateStrip view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnEditIdentity(this::onEditIdentity);
        view.setOnNameEnter(() -> takeTitle(true));
        view.setOnNameCancel(this::dropTitle);
        view.getTemplates().setOnEdit(index -> {
            if (index >= 0 && index < modeTemplates.size()) {
                onSelectTemplate.accept(modeTemplates.get(index));
            }
        });
        view.getTemplates().setOnAdd(() -> {
            if (mode != null) {
                onAddTemplate.accept(mode);
            }
        });
        view.setOnDispatchLink(this::onDispatchFromDocument);
        view.setOnModeDispatch(this::onModeDispatch);
        view.setOnRole(this::onRole);
        view.setOnOpenWorkbench(this::onOpenWorkbench);
        view.setOnOpenGuard(() -> {
            if (templateId != null) {
                onOpenGuard.accept(templateId);
            }
        });
        view.setOnMatch(this::onMatch);
        view.setOnMatchTyped(pattern -> matchTimer.schedule(MATCH_INSPECT_DELAY_MS));
        declarations.attach(view.getDeclarations());
        captures.attach(view.getCaptures());
        return this;
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        sourceConfig.setHost(host);
        bodyPresenter.setHost(host);
        declarations.setHost(host);
        captures.setHost(host);
    }

    /// @param onSelectTemplate Told when one of a mode's templates is pressed in the mode's
    ///                         own list, with its id.
    public void setOnSelectTemplate(final Consumer<String> onSelectTemplate) {
        this.onSelectTemplate = onSelectTemplate;
    }

    /// @param onAddTemplate Told when a template is asked for from a mode's own list, with the
    ///                      mode.
    public void setOnAddTemplate(final Consumer<String> onAddTemplate) {
        this.onAddTemplate = onAddTemplate;
    }

    /// @param onRenameMode Asked to rename the mode shown, with its name and the name it is to
    ///                     have, once the name is known to be free. The owner renames it, so
    ///                     that what is selected — the row that stands for it — goes with the
    ///                     name rather than being lost and found again, which moved the cursor
    ///                     (design 47 §7.8, finding 5).
    public void setOnRenameMode(final BiConsumer<String, String> onRenameMode) {
        this.onRenameMode = onRenameMode;
    }

    /// Shows again what is shown, as the project has it now.
    public void refresh() {
        if (mode != null) {
            setMode(mode);
        } else {
            setTemplate(templateId);
        }
    }

    /// Show a mode: its name, which is edited there, and the templates in it (the owner,
    /// 2026-11-18). A mode is a name its templates share and its dispatches point at, so this
    /// is all there is of one; renaming it here renames it on both.
    ///
    /// @param mode The mode.
    public void setMode(final String mode) {
        if (templateId != null || !Objects.equals(mode, this.mode)) {
            bodyPresenter.leave();
            leaveTitle();
            leaveMatch();
            view.scrollToTop();
        }
        this.templateId = null;
        this.mode = mode;
        final Project project = host.getProject();
        final List<DefinitionList.Row> rows = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        if (project != null) {
            for (final Template template : project.templates()) {
                if (mode.equals(template.mode())) {
                    rows.add(new DefinitionList.Row(template.name(),
                            Templates.kind(template.match()) + " " + Templates.describe(template.match())));
                    ids.add(template.id());
                }
            }
        }
        modeTemplates = ids;
        view.setHeader("Mode", null, mode, rows.size() == 1
                ? "1 template"
                : rows.size() + " templates", "", "");
        final Modes.DispatchState state = project == null
                ? new Modes.DispatchState(0, true, null, false, null, List.of())
                : Modes.dispatchState(project, mode);
        view.setModeDispatch(state.dispatch(), !readOnly && state.sites() > 0 && state.agreed(),
                Modes.dispatchNote(state, false), Modes.dispatchNote(state, true), !readOnly && state.offered());
        view.setNameEditable(!readOnly, "Edit the name", "Mode name");
        view.setRole(false, MatchRole.RECORD, false, "", false);
        view.setMatchVisible(false);
        view.setDetailsVisible(false);
        view.setContent(null);
        view.getTemplates().setRows(rows);
        view.getTemplates().setEnabled(!readOnly);
        view.setTemplatesVisible(true);
        if (!view.isNameOpen()) {
            // Read only now: the name being typed went with the field
            closeTitle();
        }
    }

    /// The mode's own way out of the two-part edit (design 47 C): the document's
    /// `apply-templates` is pointed at it. The author stays where they are, with the keyboard
    /// on the strategy, which is the next thing there is to say about the dispatch.
    private void onDispatchFromDocument() {
        final Project project = host.getProject();
        if (mode == null || readOnly || project == null) {
            return;
        }
        final Project dispatched = Modes.dispatchFromDocument(project, mode);
        if (dispatched != project) {
            host.replace(dispatched);
            view.focusModeDispatch();
        }
    }

    /// The mode's strategy chosen in its pane: set on every site that dispatches into it,
    /// which is where a strategy lives. Offered only where those sites agree.
    ///
    /// @param dispatch The strategy, or null for the project's.
    private void onModeDispatch(final Dispatch dispatch) {
        final Project project = host.getProject();
        if (mode == null || readOnly || project == null) {
            return;
        }
        final Modes.DispatchState state = Modes.dispatchState(project, mode);
        if (state.sites() > 0 && state.agreed() && state.dispatch() != dispatch) {
            host.replace(Modes.withDispatch(project, mode, dispatch));
        }
    }

    /// Told when a template's guard and limits are asked for, from the strip's `+ guard or limit`
    /// or its guard line: the match workbench is to be opened on them.
    ///
    /// @param onOpenGuard Told with the template's id.
    public void setOnOpenGuard(final Consumer<String> onOpenGuard) {
        this.onOpenGuard = onOpenGuard;
    }

    /// Told when the match workbench is to be opened, with the template's id.
    ///
    /// @param onOpenWorkbench The handler.
    public void setOnOpenWorkbench(final Consumer<String> onOpenWorkbench) {
        this.onOpenWorkbench = onOpenWorkbench;
    }

    /// Show a template, or the project for null; called again after every edit, so it is also refresh.
    public void setTemplate(final String id) {
        if (!Objects.equals(id, templateId) || mode != null) {
            // The body is about to show something else, or nothing: a card left open in it is
            // taken or let go here, since the body is not told when it goes off screen. And so
            // is a title being edited.
            bodyPresenter.leave();
            leaveTitle();
            leaveMatch();
            view.scrollToTop();
        }
        this.mode = null;
        this.templateId = id;
        view.setTemplatesVisible(false);
        final Project project = host.getProject();
        final Template template = host.template(id);
        if (template == null) {
            view.setHeader("Document", null, project == null
                    ? ""
                    : project.name(), "", "the document: every template is reached from here",
                    "the document: every template is reached from here");
            view.setNameEditable(false, "", "");
            view.setRole(false, MatchRole.RECORD, false, "", false);
            view.setMatchVisible(false);
            view.setDetailsVisible(false);
            sourceConfig.refresh();
            view.setContent(sourceConfig);
            return;
        }
        view.setNameEditable(!readOnly, "Edit the name and settings", "Template name");
        if (!view.isNameOpen()) {
            // Read only now: the name being typed went with the field
            closeTitle();
        }
        view.setHeader("Template", host.colour(template.id()), template.name(), "mode " + Modes.label(template.mode()),
                dispatchedNote(project, template) + runNote(project, template),
                dispatchedNote(project, template) + runTitle(template));
        view.setMatch(Templates.kind(template.match()), Templates.describe(template.match()),
                GuardClause.describe(template.guard()) + guardVerdict(template) + " · "
                + Templates.describeLimits(template.matchLimits()), !Templates.unguardedAndUnlimited(template),
                readOnly
                        ? null
                        : MatchLine.of(template.match()));
        // The role matters once the template shares its mode, where what it does with a match
        // decides what the others are tried on (design 48 C2); before that it is a choice with
        // nothing to choose between. A template already advance only says so wherever it is.
        view.setRole(template.consume() || sharesItsMode(project, template), MatchRole.of(template.consume()),
                !readOnly, roleNote(template),
                template.consume() && !template.captures().isEmpty());
        view.setMatchVisible(true);
        bodyPresenter.setTemplate(id);
        view.setContent(bodyPresenter);
        declarations.setTemplate(id);
        captures.setTemplate(id);
        view.setDetailsVisible(true);
    }

    public void setHot(final Hot hot) {
        bodyPresenter.setHot(hot);
    }

    /// Bring a top-level card of the body on show into view (design 18 §5.13.3).
    ///
    /// @param index The card's position in the body.
    public void revealCard(final int index) {
        bodyPresenter.revealCard(index);
    }

    private static String dispatchedNote(final Project project, final Template template) {
        return "dispatched from " + dispatchedFrom(project, template);
    }

    // After a run that has something to say of this template, what it made of it, in the few
    // words the heading has room for
    private String runNote(final Project project, final Template template) {
        final TraceModel trace = host.trace();
        final String summary = trace == null || project == null
                ? null
                : Profile.summary(project, trace, template);
        return summary == null
                ? ""
                : " · " + summary;
    }

    // The same, with every number
    private String runTitle(final Template template) {
        final TraceModel trace = host.trace();
        final Project project = host.getProject();
        return trace == null || project == null || Profile.summary(project, trace, template) == null
                ? ""
                : " · " + Profile.describe(trace, template.id());
    }

    public String getTemplateId() {
        return templateId;
    }

    /// The title's edit button, its name or its chip pressed: the title opens, or, open, is
    /// taken.
    public void onEditIdentity() {
        if (view.isNameOpen()) {
            takeTitle(false);
        } else {
            openTitle(false);
        }
    }

    /// Opens the title of what has just been made, for it to be named: a template arrives
    /// called `new template` and a mode `new mode`, and naming it is the first thing to do
    /// (the owner, 2026-11-18).
    public void editTitle() {
        openTitle(true);
    }

    /// Puts the keyboard on the add link of the mode's own list of templates.
    public void focusAddTemplate() {
        view.getTemplates().focusAdd();
    }

    private void openTitle(final boolean made) {
        if (readOnly || view.isNameOpen()) {
            return;
        }
        if (mode == null) {
            final Template template = host.template(templateId);
            if (template == null) {
                return;
            }
            settings.read(host, template, overrideOf(template.id()));
            settingsOpen = true;
            view.setSettings(settings);
        }
        fresh = made;
        view.openName();
    }

    /// The title, taken: Enter in the name, OK, or the edit button pressed again.
    ///
    /// **A template's** is its name and its settings, written onto the template as the project
    /// has it now, so anything done to its body while the title was open is kept. Taken with
    /// Enter for a template just made, the keyboard goes on to its match, which opens: a
    /// template is a name, a pattern and then what it writes, each with Enter.
    ///
    /// **A mode's** is its name: renamed on the templates in it and on every dispatch that
    /// points at it, or, for a mode nothing uses yet, simply called something else. For a mode
    /// just made the keyboard goes on to the add link of its list, since a mode is only ever
    /// the mode of a template.
    ///
    /// @param entered Whether it was taken with Enter in the name.
    private void takeTitle(final boolean entered) {
        if (!view.isNameOpen() || readOnly) {
            return;
        }
        final boolean chain = entered && fresh;
        if (mode != null) {
            final String from = mode;
            final String name = view.getNameText().trim();
            final String whyNot = Modes.whyNotName(name);
            if (whyNot != null) {
                AlertEvent.fireWarn(this, whyNot, null);
                return;
            }
            if (!name.equals(from) && host.modes().contains(name)) {
                AlertEvent.fireWarn(this, "There is already a mode named '" + name + "'", null);
                return;
            }
            closeTitle();
            if (!name.equals(from)) {
                onRenameMode.accept(from, name);
            }
            if (chain) {
                view.getTemplates().focusAdd();
            } else {
                view.focusName();
            }
            return;
        }
        final Template current = host.template(templateId);
        if (current == null || !settingsOpen) {
            closeTitle();
            return;
        }
        final Template written = settings.write(current, view.getNameText(), false);
        if (written == null) {
            return;
        }
        final String colour = settings.getColour();
        closeTitle();
        if (!written.equals(current)) {
            host.replace(host.withTemplate(written));
        }
        host.setColour(written.id(), colour);
        if (chain) {
            view.editMatch();
        } else {
            view.focusName();
        }
    }

    // Escape, or Cancel: what is named is as it was — which, for one just made, is the name it
    // arrived with.
    private void dropTitle() {
        if (view.isNameOpen()) {
            closeTitle();
            view.focusName();
        }
    }

    private void closeTitle() {
        view.closeName();
        view.setSettings(null);
        settingsOpen = false;
        fresh = false;
    }

    // The strip is about to show something else. A template's title is taken if it can be
    // written as it stands and let go if it cannot, with nobody asked — a moment later and
    // not now, for the reason a card's draft is ([BodyController#leave()]). A mode's name is
    // let go: taking it would move the selection that is just being changed.
    private void leaveTitle() {
        if (!view.isNameOpen()) {
            closeTitle();
            return;
        }
        final String id = templateId;
        final Template was = id == null
                ? null
                : host.template(id);
        final Template written = was == null || !settingsOpen
                ? null
                : settings.write(was, view.getNameText(), true);
        final String colour = settingsOpen
                ? settings.getColour()
                : null;
        closeTitle();
        if (written != null && !readOnly) {
            Scheduler.get().scheduleDeferred(() -> {
                // Written onto the template as it is by then, and not refused for having changed:
                // a card left open in its body is taken in the same moment, and is taken first
                // (design 47 §7.8, finding 1). Only the title's own fields have to be as they were.
                final Template now = host.template(id);
                if (now != null && !readOnly && Templates.sameIdentity(now, was)) {
                    final Template next = Templates.withIdentityOf(now, written);
                    if (!next.equals(now)) {
                        host.replace(host.withTemplate(next));
                    }
                    host.setColour(id, colour);
                }
            });
        }
    }

    // The strip is about to show something else. What is being typed on the match line is
    // taken for the template it was typed for, as a title is ([#leaveTitle()]). Leaving the
    // line by the pointer takes it already; this is for what moves the selection without moving
    // the keyboard — an Alt+arrow, a step back — which left it open over another template's
    // match and then wrote it there (design 47 §7.8, finding 3). Another kind chosen on the line
    // is taken only where it throws nothing away: the template it would be asked about is no
    // longer on screen.
    private void leaveMatch() {
        if (!view.isMatchOpen()) {
            return;
        }
        final MatchLine line = view.getMatchLine();
        view.closeMatch();
        matchTimer.cancel();
        matchInspected = null;
        final String id = templateId;
        final Template was = id == null
                ? null
                : host.template(id);
        if (readOnly || was == null || !MatchLine.spells(was.match()) || line.replacesWork(was.match())) {
            return;
        }
        final MatchExpression match = line.toMatch(was.match());
        if (match == null || match.equals(was.match())) {
            return;
        }
        Scheduler.get().scheduleDeferred(() -> {
            final Template now = host.template(id);
            if (now != null && !readOnly && now.match().equals(was.match())) {
                host.replace(host.withTemplate(Templates.withMatch(now, match)));
            }
        });
    }

    // The colour the author chose for a template, or null when it wears the palette's.
    private String overrideOf(final String id) {
        final String colour = host.colour(id);
        final List<Template> templates = host.getProject().templates();
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).id().equals(id)) {
                return colour.equals(Templates.colour(i))
                        ? null
                        : colour;
            }
        }
        return colour;
    }

    /// What the picked role means, said beside the picker — [MatchRole#note()], except where
    /// the role and the captures contradict each other. Captures are not merely ignored by an
    /// advance-only match but refused at compile time, so the author is told at the moment they
    /// cause it rather than at the next run.
    private String roleNote(final Template template) {
        final int captures = template.captures().size();
        if (template.consume() && captures > 0) {
            return "this template binds " + captures + (captures == 1
                    ? " capture"
                    : " captures") + ", and an advance-only match has no index to bind at:"
                   + " the project will not compile until they go or the role does";
        }
        return MatchRole.of(template.consume()).note();
    }

    /// The role is the one identity field the strip writes itself: it is a single bit, it changes
    /// whether this template records anything at all, and it is the answer when a template matches
    /// and the navigator shows nothing (design 44 §5d, §5f).
    public void onRole(final MatchRole role) {
        final Template template = host.template(templateId);
        if (template == null || readOnly || template.consume() == role.consume()) {
            return;
        }
        host.replace(host.withTemplate(Templates.withRole(template, role.consume())));
    }

    public void onOpenWorkbench() {
        if (templateId != null) {
            onOpenWorkbench.accept(templateId);
        }
    }

    /// Opens the match where it stands, for a template just made: what it matches is the next
    /// thing to say about it (design 47 B).
    public void editMatch() {
        view.editMatch();
    }

    /// The match line taken (design 47 B, reassessed 2026-11-18, and G): a regex's pattern is
    /// what was typed, and its flags and advance are as they were; a delimiter's character and
    /// container are, and its escape is as it was — those are the workbench's. Another kind
    /// chosen on the line replaces the match, after asking where that throws away something the
    /// author wrote.
    ///
    /// Taken with Enter in a template whose body is empty, the keyboard goes on into the body,
    /// with a new card open: a template just made is a name, a match and then what it writes,
    /// each with Enter. Otherwise it stays on the match.
    ///
    /// @param line What the line held.
    /// @param entered Whether it was taken with Enter, rather than by the keyboard leaving it.
    public void onMatch(final MatchLine line, final boolean entered) {
        if (asking) {
            return;
        }
        // An answer still on its way is for a line about to close, or to be refused
        matchTimer.cancel();
        matchInspected = null;
        final Template template = host.template(templateId);
        if (template == null || readOnly || !MatchLine.spells(template.match())) {
            view.closeMatch();
            return;
        }
        final MatchExpression match = line.toMatch(template.match());
        if (match == null) {
            // Refused where it is, with what was typed still there to be put right
            view.setMatchError(MatchLine.NO_DELIMITER);
            return;
        }
        final String id = template.id();
        if (!line.replacesWork(template.match())) {
            view.closeMatch();
            writeMatch(template, match);
            afterMatch(id, entered);
            return;
        }
        final String question = "Make the match a " + line.kind().label() + "? The "
                                + Templates.kind(template.match()) + " it holds, "
                                + Templates.describe(template.match()) + ", goes.";
        asking = true;
        ConfirmEvent.fire(this, question, ok -> {
            asking = false;
            // The line is still this template's only if nothing has moved the strip meanwhile
            final boolean shown = id.equals(templateId) && view.isMatchOpen();
            final Template now = host.template(id);
            if (!ok || now == null || readOnly || !now.match().equals(template.match())) {
                // Left as it was: open, with the keyboard back in it
                if (shown) {
                    view.focusOpenLine();
                }
                return;
            }
            if (shown) {
                view.closeMatch();
            }
            writeMatch(now, match);
            afterMatch(id, entered);
        });
    }

    private void writeMatch(final Template template, final MatchExpression match) {
        if (!match.equals(template.match())) {
            host.replace(host.withTemplate(Templates.withMatch(template, match)));
        }
    }

    // Where the keyboard goes once the match is taken with Enter: on into an empty body, or
    // back to the match. Nowhere, if the strip is showing another template by then.
    private void afterMatch(final String id, final boolean entered) {
        if (!entered || !id.equals(templateId)) {
            return;
        }
        final Template now = host.template(id);
        if (now != null && now.body().isEmpty()) {
            bodyPresenter.openNew();
        } else {
            view.focusMatch();
        }
    }

    @Override
    protected void onDetach() {
        // Nothing is open to answer for once the strip has left the page
        matchTimer.cancel();
        matchInspected = null;
    }

    // Whether the regex in the open match field compiles, asked of the engine, which owns every
    // fact about a pattern (as the workbench's regex form asks it). Nothing is said of an empty
    // field: a template just made opens its match empty, and is not yet wrong.
    private void inspectMatch() {
        final Template template = host == null
                ? null
                : host.template(templateId);
        final MatchLine line = view.getMatchLine();
        if (template == null || !view.isMatchOpen() || line.kind() != MatchKind.REGEX) {
            return;
        }
        final String pattern = line.pattern();
        final MatchExpression.Regex regex = template.match() instanceof MatchExpression.Regex was
                ? was
                : new MatchExpression.Regex(pattern, null, 0);
        if (pattern.isEmpty()) {
            matchInspected = null;
            view.setMatchError(null);
            return;
        }
        final ShapeshifterPatternRequest request = new ShapeshifterPatternRequest(pattern,
                regex.flags().caseInsensitive(), regex.flags().dotAll());
        matchInspected = request;
        restFactory
                .create(RESOURCE)
                .method(res -> res.patternInfo(request))
                .onSuccess(info -> {
                    // Not if the line has been made a delimiter since: the answer is a regex's
                    if (request == matchInspected && view.isMatchOpen()
                        && view.getMatchLine().kind() == MatchKind.REGEX) {
                        view.setMatchError(info.isValid()
                                ? null
                                : info.getError());
                    }
                })
                .owner(this)
                .exec();
    }

    // Whether another template is in this one's mode
    private static boolean sharesItsMode(final Project project, final Template template) {
        if (project == null) {
            return false;
        }
        for (final Template other : project.templates()) {
            // The document shares the root group's null mode but is entered, not dispatched, so
            // it is never chosen between
            if (other != template && !other.id().equals(template.id())
                && !(other.match() instanceof MatchExpression.Source)
                && Objects.equals(other.mode(), template.mode())) {
                return true;
            }
        }
        return false;
    }

    /// The static complement to breadcrumb ancestry: the sites whose apply-templates dispatch
    /// into this template's mode — "record (body pos 3)" — or the document for the root.
    static String dispatchedFrom(final Project project, final Template template) {
        final List<String> sites = new ArrayList<>(Modes.dispatchSites(project, template.mode()));
        if (template.mode() == null) {
            sites.add(0, "the document");
        }
        return sites.isEmpty()
                ? "nothing applies into mode " + Modes.label(template.mode())
                : String.join(", ", sites);
    }

    /// The guard's verdicts from the run (design 18 §5.6): at the cursor, when the cursor's body
    /// dispatched to this template's mode; and over the run, how often it held and was refused.
    private String guardVerdict(final Template template) {
        final TraceModel trace = host.trace();
        if (template.guard() == null || trace == null) {
            return "";
        }
        final StringBuilder text = new StringBuilder();
        final Boolean atCursor = trace.guard(trace.cursorOf(), template.id());
        if (atCursor != null) {
            text.append(" — ").append(atCursor
                    ? "held"
                    : "refused").append(" at ").append(trace.label(trace.cursorOf()));
        }
        final int[] counts = trace.guardCounts(template.id());
        if (counts[0] + counts[1] > 0) {
            text.append(atCursor == null
                    ? " — "
                    : "; ").append("held in ").append(counts[0]).append(", refused in ").append(counts[1])
                    .append(counts[0] + counts[1] == 1
                            ? " frame"
                            : " frames");
        }
        return text.toString();
    }

    /// What the strip asks of the root: the dialogs and the workbench are the root's to open.

}
