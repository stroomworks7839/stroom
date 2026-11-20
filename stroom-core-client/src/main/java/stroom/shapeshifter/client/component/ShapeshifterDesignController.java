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
import stroom.dispatch.client.RestFactory;
import stroom.pipeline.shared.SourceLocation;
import stroom.shapeshifter.client.model.Hot;
import stroom.shapeshifter.client.model.Modes;
import stroom.shapeshifter.client.model.Patterns;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.ProjectText;
import stroom.shapeshifter.client.model.SampleSource;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.client.model.TraceModel;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.shared.ShapeshifterDoc.SampleKind;
import stroom.shapeshifter.shared.ShapeshifterMessage;
import stroom.shapeshifter.shared.ShapeshifterPreviewRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.shapeshifter.shared.ShapeshifterTrace;
import stroom.util.client.DelayedUpdate;
import stroom.widget.component.client.ContextValue;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.Listeners;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.user.client.History;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/// The Design tab's root (design 43 §4): owns the [Project], the selection, and the one
/// place an edit lands. Every child edits through [ProjectHost#replace]; the root re-renders
/// the children, tells the document presenter (a [ValueChangeEvent] of the new project), and
/// asks the engine what it thinks of it on a debounce.
///
/// The frame is the mockup's (43 §4.1): the template panel; the breadcrumb, input and
/// variables cells over the strip and output cells, and the pattern workbench opening in place
/// of the crumb, input, variables and strip when a match chip is clicked.
///
/// Selection is two things owned here: the selected template's id, or null for the project,
/// pushed to the strip and the open workbench; and the cursor — the selected frame of the last
/// run's trace (design 18 §5.1) — which the crumb, the input, variables and output panes follow.
/// Selecting a frame selects its template, so the strip shows the definition the cursor is an
/// instance of.
///
/// The run (phase B): a sample is chosen in the crumb's sample chooser (design 18 Q2's
/// first door), and every edit runs the project over it again on a short debounce (Q6), so the
/// trace is never far behind the definition; between the edit and the answer it is stale.
public class ShapeshifterDesignController
        extends Controller
        implements ProjectHost {

    private final Listeners<Project> changeListeners = new Listeners<>();

    private final ShapeshifterDesign view;

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private final RestFactory restFactory;
    private final TemplatePanelController templatePanel;
    private final TemplateStripController strip;
    private final PatternWorkbenchController workbench;
    private final SampleSourcePickerController samplePage;
    /// Told whether a run is possible, whenever that changes: Run lives in the document's own
    /// toolbar (design 44 §5a), where it is present whichever tab is showing, because running is
    /// the document's verb and the Source tab edits the same project.
    private Consumer<Boolean> onCanRunChange;
    private final MessagesController messages;
    private final BreadcrumbController crumb;
    private final ContentPaneController input;
    private final VariablesPaneController variables;
    private final OutputPaneController output;
    private final DelayedUpdate validate;
    private final DelayedUpdate rerun;

    private Project project;
    private final Map<String, String> colours = new HashMap<>();
    private final Set<String> declaredModes = new LinkedHashSet<>();
    private String sourceError;
    private boolean readOnly = true;
    // What this controller adds to it: a source that does not parse can be read, not
    // edited. The key ORs this with the document's own value.
    private final ContextValue<Boolean> sourceReadOnly = ContextValue.of(false);
    private boolean workbenchOpen;
    private boolean samplePageOpen;
    private List<ShapeshifterMessage> lastMessages;

    private SampleSource sampleSource;
    /// True while the sample on screen is the one the document remembered rather than one chosen
    /// this session. A remembered reference can be stale — the stream deleted, or not this user's
    /// to read — so the first run that fails clears it and says so, instead of leaving the author
    /// with a sample that will never work.
    private boolean sampleRemembered;
    /// The pasted sample, kept whichever kind is in use (design 44 §5s). Held apart from
    /// [#sampleSource] because that is the sample in force and this is the author's own
    /// text, which a look at a stream should not destroy.
    private String keptSampleText;
    /// The stream, kept the same way and for the same reason: returning to the paste keeps it.
    private SourceLocation keptSampleLocation;
    private TraceModel trace;
    // The captures' colours at the cursor, kept while neither the run nor the cursor moves: the
    // input pane and the pattern workbench both read them, and at the document the walk is the
    // whole tree
    private CaptureHues captureHues;
    private TraceModel huesTrace;
    private long huesCursor;
    private long cursor = TraceModel.ROOT;
    private boolean stale;
    private boolean running;
    private boolean runAgain;
    private Hot hot;

    // Navigation states - (frame, selected template), the two things that make this tab show
    // something else - recorded on every move the user makes and walked with back and forward;
    // a new move truncates the forward branch, as a browser's does (design 18 §5.3).
    private final List<NavState> history = new ArrayList<>();
    private int historyAt = -1;
    private boolean walking;
    /// The browser token this tab's states are pushed under; see [#record()].
    private int historyToken;
    /// This navigator's token space: the prefix every token of its own carries.
    private final String historySpace = HISTORY_PREFIX + ++navigators + "-";

    @Inject
    public ShapeshifterDesignController(final EventBus eventBus,
                                       final ShapeshifterDesign view,
                                       final RestFactory restFactory,
                                       final TemplatePanelController templatePanel,
                                       final TemplateStripController strip,
                                       final PatternWorkbenchController workbench,
                                       final SampleSourcePickerController samplePage,
                                       final MessagesController messages,
                                       final BreadcrumbController crumb,
                                       final ContentPaneController input,
                                       final VariablesPaneController variables,
                                       final OutputPaneController output) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        this.restFactory = restFactory;
        this.templatePanel = own(templatePanel).attach(view.getTemplatePanel());
        this.strip = own(strip).attach(view.getStrip());
        this.workbench = own(workbench);
        this.samplePage = own(samplePage);
        this.messages = own(messages).attach(view.getMessages());
        this.crumb = own(crumb).attach(view.getCrumb());
        this.input = own(input).attach(view.getInput());
        this.variables = own(variables).attach(view.getVariables());
        this.output = own(output).attach(view.getOutput());
        this.validate = new DelayedUpdate(400, this::validate);
        onDispose(validate::reset);
        this.rerun = new DelayedUpdate(600, this::run);
        onDispose(rerun::reset);
        templatePanel.setHost(this);
        strip.setHost(this);
        // A mode's own list: its templates are gone to, and added, as the panel's are
        strip.setOnSelectTemplate(id -> templatePanel.select(id));
        strip.setOnAddTemplate(mode -> templatePanel.addTemplate(mode));
        strip.setOnRenameMode(this::renameMode);
        strip.setOnOpenWorkbench(this::openWorkbench);
        workbench.setHost(this);
        samplePage.setHost(this);
        workbench.setOnClose(this::closeWorkbench);
        crumb.setHost(this);
        messages.setOnGoTo(this::setCursor);
        messages.setOnCount(view::setMessageCount);
        input.setHost(this);
        variables.setHost(this);
        output.setHost(this);
        view.setWorkbench(workbench);
        view.setSamplePage(samplePage);
        own(templatePanel.addSelectionListener(id -> {
            onSelect(id);
            record();
        }));
        // A template or a mode just made in the panel is shown, called what a new one is
        // called, and naming it is the first thing to do: its title opens to be changed (the
        // owner, 2026-11-18). From a template's name Enter goes on to its match (design 47 B).
        // A pattern part's is in the workbench, which is where a part is shown (design 47 §7.2,
        // finding 15).
        templatePanel.setOnCreated(id -> {
            if (Patterns.nameOf(id) != null) {
                workbench.editName();
            } else {
                strip.editTitle();
            }
        });
        // A row double-clicked is opened to be renamed, where it is shown: a part's in the
        // workbench, which is where a part is shown, and anything else's in the strip
        templatePanel.setOnOpened(id -> {
            if (Patterns.nameOf(id) != null) {
                workbench.editName();
            } else {
                strip.onEditIdentity();
            }
        });
        workbench.setOnRenamePart(this::renamePart);
        view.setKeyHandler(this::onKey);
        own(History.addValueChangeHandler(event -> onHistory(event.getValue())));
        // Read-only is the document's, pushed down as context rather than carried in read(): the
        // panes below watch it too. Watched, not read once, because a document
        // locked or unlocked while it is open has to reach the panes that are already drawn.
        // A source that does not parse makes the design read-only for everything below, which
        // the key ORs with the document's own value (Contexts.READ_ONLY narrows).
        provide(Contexts.READ_ONLY, sourceReadOnly);
        watch(Contexts.READ_ONLY, readOnly -> {
            if (this.readOnly == readOnly) {
                return;
            }
            this.readOnly = readOnly;
            // Nothing to redraw until a project has been read; read() draws it with this in force.
            if (project != null) {
                refresh();
            }
        });

    }


    // ---- where I was ----

    /// The browser's own back and forward walk this tab's navigation states (design 18 §5.3:
    /// "the state a GWT implementation hands to the platform's own place history rather than
    /// inventing a second one").
    ///
    /// Stroom's content tabs already own the history, as a counter over a list they keep
    /// themselves (`ContentTabPanePresenter`); this is the same trick beside it. The token
    /// is prefixed, so the tab pane's `Integer.parseInt` of it throws and the press is
    /// ignored there, and this handler ignores the tab pane's numeric ones. Nothing survives a
    /// reload, and nothing should: the states name frames of a run, and the data a run is over is
    /// never the document's (Q2), so a restored cursor would point at what is no longer there.
    private static final String HISTORY_PREFIX = "ss";

    /// How many navigators this page has built, which gives each one its own token space. Two open
    /// projects both counting from one would read each other's tokens as their own and walk to
    /// whatever state the arithmetic landed on.
    private static int navigators;

    private void record() {
        if (walking) {
            return;
        }
        final NavState state = new NavState(cursor, templatePanel.getSelectedTemplateId());
        if (historyAt >= 0 && history.get(historyAt).equals(state)) {
            return;
        }
        while (history.size() > historyAt + 1) {
            history.remove(history.size() - 1);
        }
        history.add(state);
        historyAt = history.size() - 1;
        historyToken++;
        try {
            History.newItem(historySpace + historyToken, false);
        } catch (final RuntimeException e) {
            // A history the browser would not take is not worth failing a navigation over.
        }
        crumb.refresh();
    }

    /// The browser moved: walk by as many states as the token moved, when this tab is the one showing.
    private void onHistory(final String token) {
        if (walking || token == null || !token.startsWith(historySpace) || !isShowing()) {
            return;
        }
        final int was = historyToken;
        final int now;
        try {
            now = Integer.parseInt(token.substring(historySpace.length()));
        } catch (final NumberFormatException e) {
            return;
        }
        final int to = historyAt + (now - was);
        if (to >= 0 && to < history.size() && to != historyAt) {
            // Only a walk we actually take moves the counter. Assigning it first desynchronised
            // this document from the token space for good: a token from another document's
            // navigation is out of range here, was rejected, and yet left the offset wrong for
            // every press afterwards.
            historyToken = now;
            walk(to);
        }
    }

    /// Whether this tab is the one on screen: a press elsewhere must not move a cursor nobody sees.
    private boolean isShowing() {
        return asWidget() != null && asWidget().isAttached() && asWidget().isVisible();
    }

    @Override
    public boolean canGoBack() {
        return historyAt > 0;
    }

    @Override
    public boolean canGoForward() {
        return historyAt >= 0 && historyAt < history.size() - 1;
    }

    @Override
    public void goBack() {
        if (canGoBack()) {
            // Through the browser, so that its own back button and these keys are one history.
            History.back();
        }
    }

    @Override
    public void goForward() {
        if (canGoForward()) {
            History.forward();
        }
    }

    private void walk(final int to) {
        historyAt = to;
        final NavState state = history.get(to);
        walking = true;
        try {
            if (trace != null && trace.has(state.frameId)) {
                cursor = state.frameId;
                trace.setCursor(cursor);
            }
            templatePanel.select(state.templateId);
            refreshTrace();
        } finally {
            walking = false;
        }
    }

    /// The tab's keys (design 18 §5.3, §5.7): Ctrl+Enter runs; Alt+←/→ walk the history - the
    /// browser's, now, so the keys and its own back button are one thing; Alt+Shift+←/→ step the
    /// cursor's template across the whole input, Ctrl+Alt+←/→ among its siblings, Alt+↑ to the
    /// parent, Alt+↓ to the first child. A card with focus keeps its own Alt+arrows (they stop
    /// there).
    private void onKey(final KeyDownEvent event) {
        final int key = event.getNativeKeyCode();
        if (key == KeyCodes.KEY_ENTER && event.isControlKeyDown()) {
            event.preventDefault();
            runAndShow();
            return;
        }
        if (key == KeyCodes.KEY_ESCAPE && workbenchOpen) {
            // The workbench's other door (design 18 §5.6): a final flush, not a decision.
            event.preventDefault();
            closeWorkbench();
            return;
        }
        if (!event.isAltKeyDown() || trace == null) {
            return;
        }
        final boolean handled;
        if (key == KeyCodes.KEY_LEFT || key == KeyCodes.KEY_RIGHT) {
            final int delta = key == KeyCodes.KEY_LEFT
                    ? -1
                    : 1;
            if (event.isShiftKeyDown()) {
                crumb.onStep(delta);
            } else if (event.isControlKeyDown()) {
                crumb.onSibling(cursor, delta);
            } else if (delta < 0) {
                goBack();
            } else {
                goForward();
            }
            handled = true;
        } else if (key == KeyCodes.KEY_UP) {
            if (cursor != TraceModel.ROOT) {
                setCursor(trace.parent(cursor));
            }
            handled = true;
        } else if (key == KeyCodes.KEY_DOWN) {
            final List<ShapeshifterTrace.Frame> children = trace.children(cursor);
            if (!children.isEmpty()) {
                setCursor(children.get(0).getId());
            }
            handled = true;
        } else {
            handled = false;
        }
        if (handled) {
            event.preventDefault();
            event.stopPropagation();
        }
    }

    /// One place the user has been: the cursor and the selected template.
    private static final class NavState {

        private final long frameId;
        private final String templateId;

        private NavState(final long frameId, final String templateId) {
            this.frameId = frameId;
            this.templateId = templateId;
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof NavState && ((NavState) o).frameId == frameId
                   && Objects.equals(((NavState) o).templateId, templateId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(frameId, templateId);
        }
    }

    /// The document as read, or as the Source tab last parsed it. A null project with a source
    /// error keeps the previous project on screen, read-only, under the error.
    public void read(final Project project, final Map<String, String> colours, final SourceLocation sample,
                     final String sampleText, final SampleKind sampleKind, final String sourceError) {
        this.sourceError = sourceError;
        sourceReadOnly.set(sourceError != null);
        if (project != null) {
            this.project = project;
            declaredModes.removeAll(Modes.of(project));
        }
        if (colours != null) {
            this.colours.clear();
            this.colours.putAll(colours);
        }
        // Restored only when the author has not already chosen one this session: a read can
        // arrive from the Source tab mid-edit, and it must not pull the sample out from under
        // them. The feed is unknown until something reads the stream, so the row says what the
        // reference says.
        if (keptSampleText == null) {
            keptSampleText = sampleText;
        }
        if (keptSampleLocation == null) {
            keptSampleLocation = sample;
        }
        if (sampleSource == null) {
            // The document says which it was using. Where it does not — it never had one, or it
            // arrived from an export, which carries the text and neither the stream nor the claim
            // to be using one — whichever it has will do, the text first because it always works.
            final boolean stream = sampleKind == SampleKind.STREAM
                    ? sample != null
                    : sampleKind == null && sample != null && sampleText == null;
            if (stream) {
                sampleSource = SampleSource.record(sample, null);
                sampleRemembered = true;
            } else if (sampleText != null) {
                // Pasted text cannot fail to resolve, so it is not "remembered" in the sense the
                // flag means: there is nothing for a failed run to forget.
                sampleSource = SampleSource.pasted(sampleText);
            }
        }
        view.setBanner(sourceError == null
                ? null
                : "The Source tab does not parse, so this tab shows the last good project read-only: " + sourceError);
        refresh();
        if (project != null) {
            check();
        } else {
            messages.setMessages(sourceError, lastMessages);
        }
    }

    /// Forget a sample the document remembered but the server could not read — deleted, or not
    /// this user's to read. Says so once and leaves the author at the empty state rather than on a
    /// sample that will never work. A sample chosen by hand this session is left alone: that is an
    /// ordinary unreadable stream, and the messages already say so.
    ///
    /// @return true when a remembered sample was forgotten, so the caller stops
    private boolean forgetRememberedSample() {
        if (!sampleRemembered) {
            return false;
        }
        sampleRemembered = false;
        sampleSource = null;
        trace = null;
        refresh();
        AlertEvent.fireWarn(this, "The sample this project last used could not be read — the stream may "
                                  + "have been deleted, or may not be yours to read. Choose sample data "
                                  + "to run over.", null);
        return true;
    }

    /// The engine's opinion of the project: a run when there is a sample, a validation otherwise.
    private void check() {
        if (sampleSource != null) {
            stale = true;
            refreshTrace();
            rerun.update();
        } else {
            validate.update();
        }
    }

    @Override
    public Project getProject() {
        return project;
    }


    /// The sample reference the document should remember: a stream's location, or null for a
    /// pasted sample, which is data and stays out of the document (design 44 §5j). Read when the
    /// document is written, never pushed, so choosing a sample does not make the document dirty.
    @Override
    public String getKeptSampleText() {
        return keptSampleText;
    }

    @Override
    public SourceLocation getKeptSampleLocation() {
        return keptSampleLocation;
    }

    /// What the document keeps: the pasted text, whether or not it is the sample in force.
    public String getSampleText() {
        return keptSampleText;
    }

    /// Both are kept; this is the stream, whether or not it is the one in force.
    public SourceLocation getSampleLocation() {
        return keptSampleLocation;
    }

    /// Which of the two the project is using, for the document to remember.
    public SampleKind getSampleKind() {
        if (sampleSource == null) {
            return null;
        }
        return sampleSource.getText() != null
                ? SampleKind.PASTED
                : SampleKind.STREAM;
    }

    /// The colour overrides as they stand, for the document to keep: only for templates that still exist.
    public Map<String, String> getColours() {
        final Map<String, String> kept = new HashMap<>();
        for (final Map.Entry<String, String> entry : colours.entrySet()) {
            if (template(entry.getKey()) != null) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        return kept;
    }

    @Override
    public String colour(final String templateId) {
        final String override = colours.get(templateId);
        if (override != null) {
            return override;
        }
        final List<Template> templates = project == null
                ? List.of()
                : project.templates();
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).id().equals(templateId)) {
                return Templates.colour(i);
            }
        }
        return "transparent";
    }

    @Override
    public void setColour(final String templateId, final String colour) {
        if (readOnly || templateId == null) {
            return;
        }
        final String before = colours.get(templateId);
        if (Objects.equals(before, colour)) {
            return;
        }
        if (colour == null) {
            colours.remove(templateId);
        } else {
            colours.put(templateId, colour);
        }
        refresh();
        // The document is dirty for it; the project is unchanged, and the run never stale for a colour.
        changeListeners.fire(project);
    }

    @Override
    public void replace(final Project next) {
        if (next == null || readOnly) {
            return;
        }
        project = next;
        declaredModes.removeAll(Modes.of(next));
        refresh();
        changeListeners.fire(next);
        check();
    }

    // ---- renaming what is selected ----

    // A mode renamed where it is shown. The selection is moved to the row it will have before
    // the rows change, so it is never lost: losing it moved the cursor to the document
    // (design 47 §7.8, finding 5). A mode nothing uses yet is only the editor's, and is simply
    // called something else.
    private void renameMode(final String from, final String to) {
        templatePanel.follow(TemplatePanelController.modeRowId(to));
        if (project != null && Modes.of(project).contains(from)) {
            replace(Modes.rename(project, from, to));
        } else {
            forgetMode(from);
            declareMode(to);
            refresh();
        }
    }

    // A part renamed where it is shown, the workbench: every tree that names it follows it, and
    // so does the selection.
    private void renamePart(final String from, final String to) {
        if (project != null) {
            templatePanel.follow(Patterns.rowId(to));
            replace(Patterns.rename(project, from, to));
        }
    }

    // ---- modes ----

    @Override
    public List<String> modes() {
        // Disjoint by construction: a project arriving by either door prunes what it holds.
        final List<String> modes = project == null
                ? new ArrayList<>()
                : new ArrayList<>(Modes.of(project));
        modes.addAll(declaredModes);
        return modes;
    }

    @Override
    public void declareMode(final String mode) {
        if (mode != null && !mode.isEmpty() && !modes().contains(mode)) {
            declaredModes.add(mode);
        }
    }

    @Override
    public void forgetMode(final String mode) {
        declaredModes.remove(mode);
    }

    /// Told when whether the project can be run changes.
    ///
    /// @param onCanRunChange The handler.
    public void setOnCanRunChange(final Consumer<Boolean> onCanRunChange) {
        this.onCanRunChange = onCanRunChange;
        announceCanRun();
    }

    /// Whether Run would do anything: there is a project, and data to run it over.
    public boolean canRun() {
        return project != null && sampleSource != null;
    }

    private void announceCanRun() {
        if (onCanRunChange != null) {
            onCanRunChange.accept(canRun());
        }
    }

    private void refresh() {
        announceCanRun();
        templatePanel.refresh();
        onSelect(templatePanel.getSelectedTemplateId());
    }

    // ---- the run and the cursor ----

    @Override
    public String getSample() {
        return sampleSource == null
                ? null
                : sampleSource.getText();
    }

    @Override
    public SampleSource getSampleSource() {
        return sampleSource;
    }

    @Override
    public void setSampleSource(final SampleSource source) {
        this.sampleSource = source;
        this.sampleRemembered = false;
        final String text = source == null
                ? null
                : source.getText();
        if (source != null && source.getLocation() != null) {
            keptSampleLocation = source.getLocation();
        }
        if (text != null && !text.equals(keptSampleText)) {
            // The author's own text is document content now that it is saved and exported
            // (§5q), so writing it makes the document dirty exactly as any other edit does.
            // Choosing a stream does not: it says which data to look at, not what the project is.
            keptSampleText = text;
            changeListeners.fire(project);
        }
        announceCanRun();
        if (source == null) {
            trace = null;
            cursor = TraceModel.ROOT;
            stale = false;
            refreshTrace();
            validate.update();
        } else {
            run();
        }
    }

    /// Run, and show the run: pressing Run - or Ctrl+Enter - while the sample page is open leaves
    /// it, because what was asked for is the result rather than the picker. Choosing a sample
    /// does not, so several can be tried without the page being pulled away.
    public void runAndShow() {
        if (samplePageOpen) {
            templatePanel.select(null);
        }
        run();
    }

    @Override
    public void run() {
        if (project == null || sampleSource == null) {
            return;
        }
        if (running) {
            // One answer at a time; the edit that arrived meanwhile runs when this one lands.
            runAgain = true;
            return;
        }
        running = true;
        stale = true;
        crumb.refresh();
        final ShapeshifterPreviewRequest request = new ShapeshifterPreviewRequest(ProjectText.print(project),
                sampleSource.getText(), sampleSource.getLocation());
        restFactory
                .create(RESOURCE)
                .method(res -> res.preview(request))
                .onSuccess(result -> {
                    running = false;
                    if (!result.isSampleRead() && forgetRememberedSample()) {
                        // A sample that cannot be read comes back as a successful answer carrying
                        // the fact (the project may be sound), so the recovery belongs here and
                        // not in onFailure, where no transport error ever arrives.
                        stale = false;
                        refreshTrace();
                        return;
                    }
                    trace = new TraceModel(result);
                    trace.setCursor(cursor);
                    stale = false;
                    lastMessages = result.getMessages();
                    messages.setMessages(sourceError, lastMessages);
                    if (!trace.has(cursor)) {
                        cursor = TraceModel.ROOT;
                        trace.setCursor(cursor);
                    }
                    refreshTrace();
                    record();
                    if (runAgain) {
                        runAgain = false;
                        run();
                    }
                })
                .onFailure(error -> {
                    // No trace to be stale against; the crumb and the pane say the run failed.
                    running = false;
                    runAgain = false;
                    stale = false;
                    AlertEvent.fireError(this, "The run failed: " + error.getMessage(), null);
                    refreshTrace();
                })
                .owner(this)
                .exec();
    }

    @Override
    public TraceModel trace() {
        return trace;
    }

    @Override
    public boolean isStale() {
        return stale || running;
    }

    @Override
    public long cursor() {
        return cursor;
    }

    @Override
    public CaptureHues captureHues() {
        if (trace == null) {
            return null;
        }
        if (captureHues == null || huesTrace != trace || huesCursor != cursor) {
            captureHues = CaptureHues.inScope(trace, cursor);
            huesTrace = trace;
            huesCursor = cursor;
        }
        return captureHues;
    }

    /// Move the cursor to what a panel row stands for: a template's first match, or the document.
    /// Answers whether it moved. Nothing happens where there is no run to move within, or where
    /// the cursor is already inside a match of that template - descending into one selects its
    /// row, and that must not throw the cursor back to the first.
    private boolean goTo(final String templateId) {
        if (trace == null) {
            return false;
        }
        if (templateId == null) {
            if (cursor == TraceModel.ROOT) {
                return false;
            }
            cursor = TraceModel.ROOT;
            trace.setCursor(cursor);
            return true;
        }
        final ShapeshifterTrace.Frame at = trace.frame(cursor);
        if (at != null && templateId.equals(at.getTemplateId())) {
            return false;
        }
        final List<ShapeshifterTrace.Frame> matches = trace.matches(templateId);
        if (matches.isEmpty()) {
            return false;
        }
        cursor = matches.get(0).getId();
        trace.setCursor(cursor);
        return true;
    }

    @Override
    public void setCursor(final long frameId) {
        if (trace == null || !trace.has(frameId)) {
            return;
        }
        cursor = frameId;
        trace.setCursor(cursor);
        // A frame selects its template (design 18 §5.1): the strip shows what the cursor is an instance of.
        final ShapeshifterTrace.Frame frame = trace.frame(frameId);
        walking = true;
        try {
            templatePanel.select(frame == null
                    ? null
                    : frame.getTemplateId());
        } finally {
            walking = false;
        }
        refreshTrace();
        record();
    }

    @Override
    public void revealCard(final int index) {
        strip.revealCard(index);
    }

    @Override
    public void hover(final Hot hot) {
        if (Objects.equals(hot, this.hot)) {
            return;
        }
        this.hot = hot;
        light();
    }

    /// Tell every surface what is pointed at. Apart from [#hover(Hot)] because a redrawn pane
    /// has to be told again, and that is not a change of what is pointed at.
    private void light() {
        templatePanel.setHot(hot);
        crumb.setHot(hot);
        input.setHot(hot);
        variables.setHot(hot);
        output.setHot(hot);
        strip.setHot(hot);
    }

    /// Everything that reads the trace or the cursor, after either changes.
    private void refreshTrace() {
        templatePanel.refresh();
        strip.refresh();
        crumb.refresh();
        input.refresh();
        variables.refresh();
        output.refresh();
        if (workbenchOpen) {
            // Its groups are their variables' colours, which a run or a step can move
            workbench.recolour();
        }
        // Redrawing a pane throws away what was lit in it: the Html is replaced, so the marks
        // carrying `highlight` are gone and `Marks.rest` has cleared the block. The pointer has
        // not moved and the browser fires no `mouseover` for a pointer standing still — and if
        // it did, hover(Hot) would discard it as a repeat — so without this the thing under the
        // pointer goes dark and stays dark until the pointer finds a different mark.
        //
        // This is the one thing the `:hover` rules did for free, and removing them (design 18
        // §5.12.3) left it to be done on purpose. A click that descends is the common way in:
        // it refreshes every pane with the pointer still on the mark it was clicking.
        light();
    }

    private void onSelect(final String rowId) {
        if (SampleSource.isRow(rowId)) {
            // The sample is a page beside the templates (design 44 §5a), not a frame to navigate.
            closeWorkbench();
            samplePageOpen = true;
            samplePage.refresh();
            view.showSamplePage(true);
            strip.setTemplate(null);
            crumb.setTemplate(null);
            return;
        }
        if (samplePageOpen) {
            samplePageOpen = false;
            view.showSamplePage(false);
        }
        final String pattern = Patterns.nameOf(rowId);
        if (pattern != null && project != null && project.patterns().containsKey(pattern)) {
            // A part of the library has no frame and no strip: selecting it is opening the
            // workbench on it (design 18 §5.9, design 44 §3).
            strip.setTemplate(null);
            workbenchOpen = true;
            workbench.setPattern(pattern);
            view.showWorkbench(true);
            return;
        }
        final String mode = TemplatePanelController.modeOf(rowId);
        if (mode != null && modes().contains(mode)) {
            // A mode is not a frame either: selecting it shows the mode — its name, and the
            // templates in it — where a template would be shown, and the cursor stays put.
            closeWorkbench();
            strip.setMode(mode);
            crumb.setTemplate(null);
            return;
        }
        final String id = template(rowId) == null
                ? null
                : rowId;
        // Selecting a template is navigating to it (design 18 §5.3: "the crumb rewrites around a
        // different frame"): the cursor moves to its first match, so the crumb, the input, the
        // variables and the output all show it - and the stepper has something to step. A
        // template with no matches leaves the cursor where it is; the strip says it matched none.
        final boolean moved = goTo(id);
        strip.setTemplate(id);
        crumb.setTemplate(id);
        if (moved) {
            refreshTrace();
        }
        if (workbenchOpen) {
            // The workbench follows the selection (design 18 §5.6: retargeted in place); the
            // document has no match to edit, so selecting it closes the workbench.
            if (id == null) {
                closeWorkbench();
            } else {
                workbench.setTemplate(id);
            }
        }
    }

    private void openWorkbench(final String templateId) {
        if (template(templateId) == null) {
            return;
        }
        workbenchOpen = true;
        workbench.setTemplate(templateId);
        view.showWorkbench(true);
    }

    private void closeWorkbench() {
        if (workbenchOpen) {
            workbenchOpen = false;
            workbench.closed();
            view.showWorkbench(false);
            if (Patterns.nameOf(templatePanel.getSelectedTemplateId()) != null) {
                // A part is only ever looked at in the workbench: closing it is leaving the part.
                templatePanel.select(null);
            }
        }
    }

    private void validate() {
        if (project == null) {
            return;
        }
        final String text = ProjectText.print(project);
        restFactory
                .create(RESOURCE)
                .method(res -> res.validate(text))
                .onSuccess(result -> {
                    lastMessages = result.getMessages();
                    messages.setMessages(sourceError, lastMessages);
                })
                .owner(this)
                .exec();
    }

    /// @param listener Told each time this changes.
    /// @return The registration, for the listener's owner to own.
    public HandlerRegistration addChangeListener(final Consumer<Project> listener) {
        return changeListeners.add(listener);
    }

}
