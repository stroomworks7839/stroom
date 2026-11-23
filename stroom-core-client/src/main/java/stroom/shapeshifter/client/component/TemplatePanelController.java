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
import stroom.shapeshifter.client.model.Hot;
import stroom.shapeshifter.client.model.Modes;
import stroom.shapeshifter.client.model.Patterns;
import stroom.shapeshifter.client.model.Profile;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.SampleSource;
import stroom.shapeshifter.client.model.TemplateRowData;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.client.model.TraceModel;
import stroom.shapeshifter.config.Dispatch;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;
import stroom.shapeshifter.shared.ShapeshifterTrace.Timing;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.Listeners;
import stroom.widget.util.client.MouseUtil;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/// The project panel (design 18 §5.6, 43 §4.1): the sample the project runs over at the head —
/// nothing else here means anything without it — then the project itself, then the templates
/// grouped by mode in project order, which is dispatch order, so up and down are semantics, and
/// last the pattern library's parts. A template's row is a swatch, a name and, once there is a
/// trace, its match count and heat. Managed through the toolbar over the list, acting on the
/// selection; the selection is published as a value change of the row's id — a template's, a
/// part's, the sample's, or null for the project — and the root shows what that row is.
public class TemplatePanelController extends Controller {

    // From Contexts.READ_ONLY: true until the document says otherwise, so a
    // controller with no owner fails closed rather than editable.
    private boolean readOnly = true;

    private final Listeners<String> selectionListeners = new Listeners<>();

    private TemplatePanel view;

    private ToolbarButton addButton;
    private ToolbarButton removeButton;
    private ToolbarButton upButton;
    private ToolbarButton downButton;
    private ToolbarButton patternButton;

    private ProjectHost host;
/// The panel's own rows, which are not templates and cannot collide with a template id.
    private static final String SETTINGS_ROW = "settings:";
    // The add links (design 47 B): the one a mode's list ends with, by the mode's name after
    // the prefix — nothing after it for the root — and the one the modes end with.
    private static final String NEW_TEMPLATE_ROW = "new:template:";
    private static final String NEW_MODE_ROW = "new:mode";
    // A mode's heading, by the mode's name after the prefix: the way to the mode's own pane.
    private static final String MODE_ROW = "mode:";
    // What a template and a mode are called until they are named (the owner, 2026-11-18)
    private static final String NEW_TEMPLATE = "new template";
    private static final String NEW_MODE = "new mode";
    private static final String NEW_PART = "new part";

    private String selected;
    private Consumer<String> onCreated = id -> {
    };
    private Consumer<String> onOpened = id -> {
    };

    @Inject
    public TemplatePanelController(final EventBus eventBus) {
        super(eventBus);
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            // The add links are there only while the project can be added to
            if (view != null && host != null) {
                refresh();
            }
        });
    }

    /// Attaches this to a panel, e.g. one a template declares, and adds its actions to the panel's
    /// toolbar. The panel is then this controller's widget.
    ///
    /// @param view The panel.
    /// @return This, for its owner to keep.
    public TemplatePanelController attach(final TemplatePanel view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnSelect(this::onSelect);
        view.setOnOpen(this::onOpen);
        view.setOnHover(this::onHover);
        // Add, remove, up and down, and the pattern parts' own add. There is no Edit: what is
        // selected is shown beside the list, and its name and settings are edited there. And
        // no Modes: a mode is a row of the list like a template, added, renamed and removed as
        // one is (the owner, 2026-11-18).
        addButton = view.getToolbar().button(SvgImage.ADD, "Add template", () -> {
        });
        removeButton = view.getToolbar().button(SvgImage.DELETE, "Remove template", () -> {
        });
        upButton = view.getToolbar().button(SvgImage.UP, "Move up: earlier in dispatch order", () -> {
        });
        downButton = view.getToolbar().button(SvgImage.DOWN, "Move down: later in dispatch order", () -> {
        });
        patternButton = view.getToolbar().button(SvgImage.LINK,
                "Add a pattern part: a tree defined once, named with ref from any template",
                () -> {
                });
        enableButtons();
        own(addButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onAdd();
            }
        }));
        own(removeButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onRemove();
            }
        }));
        own(upButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onMove(-1);
            }
        }));
        own(downButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onMove(1);
            }
        }));
        own(patternButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onAddPattern();
            }
        }));

        return this;
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }


    /// @param listener Told each time this changes.
    /// @return The registration, for the listener's owner to own.
    public HandlerRegistration addSelectionListener(final Consumer<String> listener) {
        return selectionListeners.add(listener);
    }

    /// The selected template's id, or null when the project row (or nothing) is selected.
    public String getSelectedTemplateId() {
        return selected;
    }

    public void onSelect(final String id) {
        if (NEW_MODE_ROW.equals(id)) {
            addMode();
        } else if (id != null && id.startsWith(NEW_TEMPLATE_ROW)) {
            final String mode = id.substring(NEW_TEMPLATE_ROW.length());
            addTemplate(mode.isEmpty()
                    ? null
                    : mode);
        } else {
            select(id, true);
        }
    }

    /// @param onCreated Told when a template, a mode or a pattern part has been made here, with its row's id,
    ///                  once it is the selected one: naming it is the next thing to do.
    public void setOnCreated(final Consumer<String> onCreated) {
        this.onCreated = onCreated;
    }

    /// @param onOpened Told when a row is opened — double-clicked — with its id, once it is the
    ///                 selected one: its name is what the author is after.
    public void setOnOpened(final Consumer<String> onOpened) {
        this.onOpened = onOpened;
    }

    /// The row that stands for a mode.
    ///
    /// @param mode The mode's name.
    /// @return Its row's id.
    public static String modeRowId(final String mode) {
        return MODE_ROW + mode;
    }

    /// @param rowId A row's id.
    /// @return The mode it stands for, or null when it is not a mode's row.
    public static String modeOf(final String rowId) {
        return rowId != null && rowId.startsWith(MODE_ROW)
                ? rowId.substring(MODE_ROW.length())
                : null;
    }

    private static String newTemplateRow(final String mode) {
        return NEW_TEMPLATE_ROW + (mode == null
                ? ""
                : mode);
    }

    /// Adds a template to a mode, there and then (design 47 B; the owner, 2026-11-18): an
    /// ordinary template, whose matches count, bind and write, with a regex for its match and
    /// nothing in it yet, called `new template` until it is named. It is selected, and
    /// whoever is told through [#setOnCreated] opens its name to be changed. Nothing is
    /// named in this list, and there is no dialog.
    ///
    /// @param mode The mode it goes in, or null for the root.
    public void addTemplate(final String mode) {
        if (readOnly || host == null || host.getProject() == null) {
            return;
        }
        final List<String> names = new ArrayList<>();
        for (final Template template : host.getProject().templates()) {
            names.add(template.name());
        }
        final Template template = Templates.create(unused(NEW_TEMPLATE, names), mode, false);
        host.replace(host.withTemplate(template));
        select(template.id(), true);
        onCreated.accept(template.id());
    }

    // A mode, there and then: called `new mode` until it is named, and with no template in it
    // yet, so it is the editor's to remember until one is.
    private void addMode() {
        if (readOnly || host == null || host.getProject() == null) {
            return;
        }
        final String mode = unused(NEW_MODE, host.modes());
        host.declareMode(mode);
        refresh();
        select(modeRowId(mode), true);
        onCreated.accept(modeRowId(mode));
    }

    // A name nothing has yet: the name itself, or the name and the first number that makes it
    // one. A call names a template and a dispatch names a mode, so two of a name would be two
    // things that cannot be told apart.
    private static String unused(final String name, final List<String> taken) {
        if (!taken.contains(name)) {
            return name;
        }
        int n = 2;
        while (taken.contains(name + " " + n)) {
            n++;
        }
        return name + " " + n;
    }

    public void onOpen(final String id) {
        select(id, true);
        if (!readOnly) {
            onOpened.accept(id);
        }
    }

    public void onHover(final String id) {
        if (host != null) {
            host.hover(id == null
                    ? null
                    : Hot.template(id));
        }
    }

    /// A template, or a frame of one, lights its row — and so does a body card, through the
    /// frame whose body ran it (design 18 §5.13.4, ruled 2026-11-17).
    ///
    /// The output pane marks the cards of frames *beneath* the cursor as well as the cursor's
    /// own. For the cursor's own the body panel lights the card itself, which says everything;
    /// for a deeper one the body panel is showing the *cursor's* template, so without this
    /// nothing on screen would say whose card the reader is pointing at. Worse, it would be
    /// inconsistent rather than merely silent: `BodyController.annotationFrame` falls back to a
    /// template's first match, so pointing at the first match's card happened to light a card
    /// and pointing at the second's did not.
    ///
    /// A capture still lights no row. Its row is the variables pane's, which lights it already.
    ///
    /// @param hot What is pointed at, or null.
    public void setHot(final Hot hot) {
        view.setHot(hot == null || hot.getKind() == Hot.Kind.CAPTURE
                ? null
                : hot.getKind() == Hot.Kind.INSTRUCTION
                        ? templateOf(hot.getFrameId())
                        : hot.getTemplateId());
    }

    /// The template a frame is an instance of, or null where the trace does not know it — the
    /// document, which is no frame and has no template.
    private String templateOf(final long frameId) {
        final TraceModel trace = host == null
                ? null
                : host.trace();
        final Frame frame = trace == null
                ? null
                : trace.frame(frameId);
        return frame == null
                ? null
                : frame.getTemplateId();
    }

    /// Select a template, or the project for null, as if clicked: the root follows through the event.
    public void select(final String id) {
        select(id, true);
    }

    private void select(final String id, final boolean fire) {
        final boolean changed = !Objects.equals(id, selected);
        selected = id;
        view.setSelected(id);
        enableButtons();
        if (fire && changed) {
            selectionListeners.fire(id);
        }
    }

    /// The selection becomes a row that is about to be there, with no one told: for a row being
    /// renamed, so that the selection goes with it when the panel is next refreshed rather than
    /// being lost and found again — losing it moves the cursor to the document (design 47 §7.8,
    /// finding 5).
    ///
    /// @param id The row's id as it will be.
    public void follow(final String id) {
        selected = id;
    }

    // Templates in a mode, the document aside
    private static int templatesIn(final Project project, final String mode) {
        int count = 0;
        for (final Template template : project.templates()) {
            if (Objects.equals(template.mode(), mode) && !isDocument(template)) {
                count++;
            }
        }
        return count;
    }

    // A mode's note: how it is dispatched where that is not strict, and for a strict mode with
    // two templates, the order they are tried in, which is the one fact about it that silently
    // changes the output — said where the order is seen rather than in a tooltip on the move
    // buttons (design 48 §6). Only strict dispatch takes the first to match; the others choose
    // otherwise, and their note says which.
    //
    // Read off the dispatch itself, not off the absence of a note: no note also means sites that
    // disagree, and then nothing is said about order, because it would be false at some of them.
    private static String withOrder(final Project project, final String mode, final int inMode) {
        return Modes.dispatchOf(project, mode) == Dispatch.STRICT && inMode >= 2
                ? "tried top to bottom: the first to match takes it"
                : dispatchNote(project, mode);
    }

    /// Re-read the rows from the host's project, keeping the selection by id where it survives.
    /// Where it does not, nothing is selected, and that is said: what showed it — a mode's pane,
    /// a part's workbench — would otherwise go on showing what is no longer in the list
    /// (design 47 §7.8, finding 6).
    public void refresh() {
        final Project project = host.getProject();
        final List<TemplateRowData> rows = new ArrayList<>();
        // Four sections: three in the order the run uses them — what the project is, what it
        // runs over, and what it runs (design 44 §5m) — then the parts the templates share. The
        // two that hold rows say how many, which is what they say when folded (ruled
        // 2026-11-20).
        rows.add(TemplateRowData.section(SETTINGS_ROW, "Settings", null));
        // The section says what is chosen, so the sample needs no row of its own
        // (design 18 §5.7, design 44 §5a, §5m).
        rows.add(TemplateRowData.section(SampleSource.rowId(), "Data", host.getSampleSource() == null
                ? "none"
                : host.getSampleSource().getLabel()));
        rows.add(TemplateRowData.section(null, "Templates", project == null
                ? null
                : howMany(project.templates().size(), "template")));
        boolean survives = selected == null || SampleSource.isRow(selected);
        if (project != null) {
            // Grouped by mode - the root group first, then modes as they first appear - and in
            // project order within a group, which is the order dispatch tries them in.
            final List<String> modes = new ArrayList<>();
            modes.add(null);
            for (final Template template : project.templates()) {
                if (!modes.contains(template.mode())) {
                    modes.add(template.mode());
                }
            }
            // ...and then the modes that have been named and have no template yet. They used
            // to be nowhere in the panel; each is now a heading over its `+ template`, which is
            // where its first template is made.
            if (!readOnly) {
                for (final String mode : host.modes()) {
                    if (!modes.contains(mode)) {
                        modes.add(mode);
                    }
                }
            }
            final TraceModel trace = host.trace();
            // One walk of the project for every mode, rather than one per mode: refresh runs on
            // every committed edit and again after every run.
            final Set<String> dispatched = Modes.dispatchedModes(project);
            for (final String mode : modes) {
                final int inMode = templatesIn(project, mode);
                // A mode nothing dispatches into is a mode that never runs: its templates
                // match nothing and the preview is empty, and the author has done nothing wrong
                // — they are halfway through a two-part edit (design 46 §5.4, ruled). The root
                // group is always dispatched, by the document template itself.
                final String note = mode != null && !dispatched.contains(mode)
                        ? "nothing dispatches into this"
                        : withOrder(project, mode, inMode);
                // The templates with no mode are headed `mode: default`, as a named mode is, but
                // only when there are some, and only beside a named mode or where the heading has
                // something to say: a project of one mode has no modes to tell apart, and the
                // document is dispatched from nowhere, so it answers to no mode at all (design 48
                // C4). The note is drawn by the heading, so it keeps one.
                final String section = mode != null || (inMode > 0 && (modes.size() > 1 || note != null))
                        ? "mode: " + Modes.label(mode)
                        : null;
                // A named mode's heading is the way to the mode itself
                final String sectionId = mode == null
                        ? null
                        : modeRowId(mode);
                survives |= sectionId != null && sectionId.equals(selected);
                // The document template heads the root group and is fixed there: it is the
                // outermost execution, and everything in that group is dispatched from its body.
                final Template document = mode == null
                        ? documentTemplate(project)
                        : null;
                if (document != null) {
                    // The same furniture as every other row, heat bar included. The engine
                    // records no timing for it — it is run, not matched — so the bar is empty
                    // and the count is the one execution a stream gets; a row that is dimmed
                    // and bar-less would read as a template that failed to match, which is the
                    // opposite of what this one is (design 44 §5m).
                    rows.add(trace == null
                            ? new TemplateRowData(document.id(), "document", null, "transparent",
                                    "source", false)
                            : new TemplateRowData(document.id(), "document", null, "transparent",
                                    "1", false, 0, 0, Profile.describe(trace, document.id()), 0));
                    // It is a row like any other and its selection survives a refresh like any
                    // other. Added outside the loop below, so it needs saying here: without it,
                    // declaring a name on the document threw the author back to the settings.
                    survives |= document.id().equals(selected);
                }
                boolean firstOfMode = true;
                for (final Template template : project.templates()) {
                    if (Objects.equals(template.mode(), mode) && !isDocument(template)) {
                        // The profile is a run's reading (design 18 §5.8): before one, the row has a
                        // kind and no heat.
                        final TemplateRowData row = trace == null
                                ? new TemplateRowData(template.id(), template.name(), section,
                                        host.colour(template.id()), count(null, template), false)
                                : new TemplateRowData(template.id(), template.name(), section,
                                        host.colour(template.id()), count(trace, template), zero(trace, template),
                                        Profile.share(trace, template.id()), Profile.cost(trace, template.id()),
                                        Profile.describe(trace, template.id()),
                                        trace.worstOfTemplate(template.id()));
                        row.setLimit(Templates.limitNote(template));
                        // The heading is drawn from the first row of the mode, so the note rides
                        // on that row.
                        rows.add(firstOfMode
                                ? row.setSectionNote(note).setSectionId(sectionId)
                                : row);
                        firstOfMode = false;
                        survives |= template.id().equals(selected);
                    }
                }
                if (!readOnly) {
                    // The list ends with `+ template`, which is where a template is added to
                    // this mode
                    final TemplateRowData add = TemplateRowData.fresh(newTemplateRow(mode), "+ template",
                            section, "Click to add a template to mode " + Modes.label(mode));
                    rows.add(firstOfMode
                            ? add.setSectionNote(note).setSectionId(sectionId)
                            : add);
                }
            }
            if (!readOnly) {
                // ...and the modes end with theirs
                rows.add(TemplateRowData.fresh(NEW_MODE_ROW, "+ mode", null,
                        "Click to add a mode, and its first template"));
            }
        }
        // The library's parts (design 44 §3), a section of their own: each with how many trees
        // name it. Once there is one: until then the section said only "none", about a library
        // a new author has not met, and the toolbar's add-part button is the way in (design 48 C9).
        if (project != null && !project.patterns().isEmpty()) {
            rows.add(TemplateRowData.section(null, "Patterns", howMany(project.patterns().size(), "pattern")));
            for (final String name : project.patterns().keySet()) {
                final int uses = Patterns.uses(project, name);
                rows.add(new TemplateRowData(Patterns.rowId(name), name, null, "transparent", uses == 0
                        ? "unused"
                        : "used by " + uses, uses == 0));
                survives |= Patterns.rowId(name).equals(selected);
            }
        }
        view.setRows(rows);
        final boolean lost = !survives && selected != null;
        if (!survives) {
            selected = null;
        }
        view.setSelected(selected);
        enableButtons();
        if (lost) {
            selectionListeners.fire(null);
        }
    }

    /// The row that stands for the project's settings.
    public static String settingsRowId() {
        return SETTINGS_ROW;
    }

    public static boolean isSettingsRow(final String rowId) {
        return SETTINGS_ROW.equals(rowId);
    }

    /// The document template — the one matching the source — or null (design 35 §4).
    static Template documentTemplate(final Project project) {
        if (project == null) {
            return null;
        }
        for (final Template template : project.templates()) {
            if (isDocument(template)) {
                return template;
            }
        }
        return null;
    }

    private static boolean isDocument(final Template template) {
        return template.match() instanceof MatchExpression.Source;
    }

    /// The row's right-hand word: the kind of match until there is a trace; then the match count,
    /// or how many places were tried for none (design 18 §5.5 - a zero is a fact about the
    /// sample, not a blank).
    // How many of a thing a section holds: `1 template`, `3 templates`
    private static String howMany(final int count, final String noun) {
        return count + " " + noun + (count == 1
                ? ""
                : "s");
    }

    private static String count(final TraceModel trace, final Template template) {
        final Timing timing = trace == null
                ? null
                : trace.timing(template.id());
        if (trace == null) {
            return Templates.kind(template.match());
        }
        if (timing == null || timing.getAttempts() == 0) {
            return "not tried";
        }
        if (timing.getMatched() == 0) {
            return "0 · tried " + timing.getAttempts();
        }
        // A skipping template's wins are not matches: they move the cursor past bytes and are
        // gone (D36's eater), opening no frame, binding nothing and writing nothing. Saying
        // "11" would promise all three.
        return template.consume()
                ? timing.getMatched() + " skipped"
                : String.valueOf(timing.getMatched());
    }

    private static boolean zero(final TraceModel trace, final Template template) {
        final Timing timing = trace == null
                ? null
                : trace.timing(template.id());
        return trace != null && (timing == null || timing.getMatched() == 0);
    }

    private void enableButtons() {
        final boolean editable = host != null && !readOnly;
        final boolean template = selected != null && host != null && host.template(selected) != null;
        final boolean pattern = selectedPattern() != null;
        final boolean data = SampleSource.isRow(selected);
        // The document template is fixed: it cannot be removed, and it does not move among its
        // siblings, because every project has exactly one and it is always the head of the root.
        final boolean fixed = template && isDocument(host.template(selected));
        addButton.setEnabled(editable && !data);
        patternButton.setEnabled(editable && host.getProject() != null && !data);
        // A mode goes only when it has no templates: removing one with templates would be
        // removing them, or leaving them nowhere.
        final String mode = modeOf(selected);
        final boolean emptyMode = mode != null && host != null && host.getProject() != null
                                  && Modes.templateCount(host.getProject(), mode) == 0;
        removeButton.setEnabled(editable && ((template || pattern) && !fixed || emptyMode));
        final int index = template && !fixed
                ? indexOf(selected)
                : -1;
        upButton.setEnabled(editable && index >= 0 && neighbour(index, -1) >= 0);
        downButton.setEnabled(editable && index >= 0 && neighbour(index, 1) >= 0);
    }

    /// The index of the nearest template in the same mode in a direction, or -1: what up and down swap with.
    private int neighbour(final int index, final int by) {
        final List<Template> templates = host.getProject().templates();
        final String mode = templates.get(index).mode();
        for (int i = index + by; i >= 0 && i < templates.size(); i += by) {
            if (Objects.equals(templates.get(i).mode(), mode)) {
                return i;
            }
        }
        return -1;
    }

    private int indexOf(final String id) {
        final List<Template> templates = host.getProject().templates();
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    // The toolbar's Add: a template in the mode the selected template is in, or the selected
    // mode itself. The same thing its list's `+ template` does.
    private void onAdd() {
        final String mode = modeOf(selected);
        if (mode != null) {
            addTemplate(mode);
            return;
        }
        final Template current = host == null
                ? null
                : host.template(selected);
        addTemplate(current == null
                ? null
                : current.mode());
    }

    /// The dispatch strategy in force for a mode, where that is news (design 46 §10 W4).
    ///
    /// **The fact, not what it implies.** An earlier version of this said what the order *meant*
    /// — "the first that matches wins", and so on. That was answering the wrong question: an
    /// author writing a grammar knows a list of alternatives is ordered, and a panel explaining
    /// it is chrome. What they cannot see is **which strategy is in force**, because it is chosen
    /// on an `apply-templates` card that may belong to another template in another pane.
    ///
    /// That is the gap §6.1 names: under `lexer` an author who knows order matters will move a
    /// template up to fix a problem they do not have, and nothing will change — reassuring or
    /// baffling depending on whether the screen said `lexer` was in force.
    ///
    /// **Only where it is not `STRICT`**, which is the reading everyone brings; saying it would
    /// be chrome on most projects. And nothing at all where the sites disagree
    /// ([Modes#dispatchOf] returns null), because there is then no fact to state.
    private static String dispatchNote(final Project project, final String mode) {
        final Dispatch dispatch = Modes.dispatchOf(project, mode);
        return dispatch == null || dispatch == Dispatch.STRICT
                ? null
                : "dispatched with " + dispatch.name().toLowerCase(Locale.ROOT);
    }

    /// The library part the selection is, or null.
    private String selectedPattern() {
        final String name = Patterns.nameOf(selected);
        return name != null && host != null && host.getProject() != null
               && host.getProject().patterns().containsKey(name)
                ? name
                : null;
    }

    // ---- the library's parts (design 44 §3) ----

    private void onAddPattern() {
        final Project project = host.getProject();
        if (project == null || readOnly) {
            return;
        }
        // There and then, called `new part` and shown with its name open, as a template and a
        // mode are (design 47 §7.2, finding 15): a part is named where it is shown, which is
        // the workbench. The prompt is left to Extract, where the part is not on screen.
        final String name = unused(NEW_PART, new ArrayList<>(project.patterns().keySet()));
        host.replace(Patterns.define(project, name, new PatternNode.Regex("", null)));
        select(Patterns.rowId(name), true);
        onCreated.accept(Patterns.rowId(name));
    }

    private void removePattern(final String name) {
        final Project project = host.getProject();
        final int uses = Patterns.uses(project, name);
        if (uses > 0) {
            AlertEvent.fireWarn(this, "'" + name + "' is named by " + uses + (uses == 1
                    ? " tree"
                    : " trees") + "; inline or re-point those refs first", null);
            return;
        }
        ConfirmEvent.fire(this, "Remove pattern part '" + name + "'?", ok -> {
            if (ok) {
                selected = null;
                host.replace(Patterns.remove(project, name));
            }
        });
    }

    private void onRemove() {
        if (selectedPattern() != null) {
            if (!readOnly) {
                removePattern(selectedPattern());
            }
            return;
        }
        if (modeOf(selected) != null) {
            removeMode(modeOf(selected));
            return;
        }
        final Template existing = host.template(selected);
        if (existing == null || readOnly) {
            return;
        }
        final String message = "Remove template '" + existing.name() + "'? Templates dispatched to from its body"
                               + " stay; the sites that dispatch to it become choices with one fewer candidate.";
        ConfirmEvent.fire(this, message, ok -> {
            if (ok) {
                final Project project = host.getProject();
                final List<Template> templates = new ArrayList<>(project.templates());
                templates.removeIf(t -> t.id().equals(existing.id()));
                selected = null;
                host.replace(project.withTemplates(templates));
            }
        });
    }

    // A mode with no templates in it, removed. One the project does not hold yet — named, and
    // nothing in it or pointing at it — is simply forgotten. One that dispatches still point
    // at is asked about first: those become dispatches into the default mode.
    private void removeMode(final String mode) {
        final Project project = host.getProject();
        if (project == null || readOnly || Modes.templateCount(project, mode) > 0) {
            return;
        }
        final int sites = Modes.applySiteCount(project, mode);
        if (sites == 0) {
            host.forgetMode(mode);
            selected = null;
            refresh();
            selectionListeners.fire(null);
            return;
        }
        final String message = Modes.removeQuestion(mode, sites);
        ConfirmEvent.fire(this, message, ok -> {
            if (ok) {
                selected = null;
                host.replace(Modes.removeSites(project, mode));
            }
        });
    }

    private void onMove(final int by) {
        final int index = selected == null
                ? -1
                : indexOf(selected);
        final Project project = host.getProject();
        if (index < 0 || readOnly) {
            return;
        }
        final int to = neighbour(index, by);
        if (to < 0) {
            return;
        }
        // Swap with the neighbour in the same mode: dispatch order within the mode changes, and
        // nothing about the other modes does.
        final List<Template> templates = new ArrayList<>(project.templates());
        final Template moved = templates.get(index);
        templates.set(index, templates.get(to));
        templates.set(to, moved);
        host.replace(project.withTemplates(templates));
    }

}
