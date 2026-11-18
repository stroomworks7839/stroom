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

import stroom.alert.client.event.ConfirmEvent;
import stroom.shapeshifter.client.model.Bodies;
import stroom.shapeshifter.client.model.CardNote;
import stroom.shapeshifter.client.model.Hot;
import stroom.shapeshifter.client.model.Instructions;
import stroom.shapeshifter.client.model.Instructions.Category;
import stroom.shapeshifter.client.model.Modes;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.client.model.TraceModel;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.Dispatch;
import stroom.shapeshifter.config.EngineVars;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.ApplyTemplates;
import stroom.shapeshifter.config.OutputNode.Choose;
import stroom.shapeshifter.config.OutputNode.Holder;
import stroom.shapeshifter.config.OutputNode.Switch;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.ParamDecl;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;
import stroom.shapeshifter.shared.ShapeshifterTrace.Instruction;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.menu.client.IconMenuItem;
import stroom.widget.menu.client.InfoMenuItem;
import stroom.widget.menu.client.Item;
import stroom.widget.menu.client.ShowMenuEvent;
import stroom.widget.popup.client.PopupPosition;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/// The body as a card list (design 18 §5.6; 43 §4.2): each instruction a card with its kind and
/// a summary, each holder's branches card lists of their own, nested one level in, each with its
/// own add line. Click a card to edit it; the actions move it, delete it, or open an add line
/// before or after it; a *when* or *case* head is edited and pruned on its own line. Every
/// edit is a rewrite through [Bodies] landing on the host as the template's new body. What a
/// card shows about the run — the output it wrote, the children it dispatched — is phase B.
///
/// **A card is edited where it is** (design 47 D). One card is open at a time, and what its
/// editor holds is a draft: nothing reaches the project until it is taken, because some kinds
/// cannot exist half-filled and the editor runs itself on every edit. Anything else done to the
/// body takes the draft first — [#settle()] — and does not go ahead if it cannot be taken.
public class BodyController extends Controller {

    // From Contexts.READ_ONLY: true until the document says otherwise, so a
    // controller with no owner fails closed rather than editable.
    private boolean readOnly = true;

    private final Body view;

    private final Provider<InstructionEditController> instructionEditorProvider;
    private final Provider<ConditionEditController> conditionEditorProvider;

    private ProjectHost host;
    private String templateId;
    private List<OutputNode> body = List.of();

    // The open card, if there is one: in place of the card at editingPath, or, when that is
    // null, a new card for editingIndex of the list at editingList.
    private InstructionEditController editing;
    private String editingPath;
    private String editingList;
    private int editingIndex;
    // The add line opened among the cards, before insertIndex of the list at insertList.
    private String insertList;
    private int insertIndex;

    @Inject
    public BodyController(final EventBus eventBus,
                         final Body view,
                         final Provider<InstructionEditController> instructionEditorProvider,
                         final Provider<ConditionEditController> conditionEditorProvider) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            applyReadOnly();
        });
        view.setOnEdit(this::onEdit);
        view.setOnDescend(this::onDescend);
        view.setOnHover(this::onHover);
        view.setOnRemove(this::onRemove);
        view.setOnMove(this::onMove);
        view.setOnDrop(this::onDrop);
        view.setOnAdd(this::onAdd);
        view.setOnEntry(this::onEntry);
        view.setOnEditKind(this::onEditKind);
        view.setOnInsert(this::onInsert);
        view.setOnInsertClosed(this::onInsertClosed);
        view.setOnEditBranch(this::onEditBranch);
        view.setOnAddBranch(this::onAddBranch);
        view.setOnRemoveBranch(this::onRemoveBranch);
        this.instructionEditorProvider = instructionEditorProvider;
        this.conditionEditorProvider = conditionEditorProvider;
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    public void setTemplate(final String id) {
        if (!Objects.equals(id, templateId)) {
            leave();
        }
        this.templateId = id;
        final Template template = host.template(id);
        if (template == null) {
            body = List.of();
        } else {
            body = template.body();
        }
        // The same template again is a run's trace landing, or an edit made somewhere else. The
        // draft and the add line stay only while what they were opened on is still there.
        if (editing != null && !draftFits()) {
            dropDraft();
        }
        if (insertList != null) {
            final List<OutputNode> list = Bodies.list(body, Bodies.path(insertList));
            if (list == null || insertIndex > list.size()) {
                onInsertClosed();
            }
        }
        applyReadOnly();
        view.setBody(body, notes(template));
    }

    /// The run's annotations, one per top-level card, about one frame of this template: the
    /// cursor when it is an instance of it, else its first match (design 18 §5.6: the strip is
    /// the definition, trace-annotated). Every card gets the hue its output is painted in; a
    /// card that wrote says how much; a dispatch says how many matches it made and leads to
    /// the first, or says that nothing applies into its mode.
    private List<CardNote> notes(final Template template) {
        final TraceModel trace = host.trace();
        final List<CardNote> notes = new ArrayList<>();
        if (template == null || trace == null) {
            return notes;
        }
        final long frame = annotationFrame(trace, template);
        final List<Instruction> wrote = frame < 0
                ? List.of()
                : trace.instructions(frame);
        final List<Frame> children = frame < 0
                ? List.of()
                : trace.children(frame);
        for (int i = 0; i < template.body().size(); i++) {
            final OutputNode node = template.body().get(i);
            final StringBuilder text = new StringBuilder();
            long descendTo = -1;
            if (node instanceof ApplyTemplates apply) {
                final String mode = apply.directive().mode();
                int matches = 0;
                for (final Frame child : children) {
                    final Template t = host.template(child.getTemplateId());
                    if (t != null && Objects.equals(t.mode(), mode)) {
                        if (matches++ == 0) {
                            descendTo = child.getId();
                        }
                    }
                }
                if (!Modes.hasTemplates(host.getProject(), mode)) {
                    text.append("nothing applies into mode ").append(mode == null
                            ? "root"
                            : mode);
                } else if (frame >= 0) {
                    text.append(matches == 0
                            ? "no matches"
                            : matches + (matches == 1
                                    ? " match"
                                    : " matches"));
                }
                // How this site chooses among the mode's candidates, where that is not the
                // `strict` everyone assumes (design 46 §10 W4). Said here as well as on the mode
                // because this is where it is chosen, and here it cannot be ambiguous: a site
                // has one strategy, where a mode can in principle be dispatched from several.
                final Dispatch dispatch = Dispatch.effective(apply.directive().dispatch(),
                        host.getProject());
                if (dispatch != Dispatch.STRICT) {
                    if (text.length() > 0) {
                        text.append(" · ");
                    }
                    text.append(dispatch.name().toLowerCase(Locale.ROOT));
                }
            }
            for (final Instruction instruction : wrote) {
                if (instruction.getIndex() == i) {
                    if (text.length() > 0) {
                        text.append(" · ");
                    }
                    text.append("wrote ").append(instruction.getLength())
                            .append("EVENTS".equals(instruction.getUnit())
                                    ? " events"
                                    : " chars");
                }
            }
            notes.add(new CardNote(cardHue(i), text.length() == 0
                    ? null
                    : text.toString(), descendTo));
        }
        return notes;
    }

    /// The frame the strip is annotated with, or -1: the cursor as an instance of the template,
    /// else its first match; the document, for the template that matches the source, since its
    /// body dispatches the root level and the root's matches are the document's children.
    static long annotationFrame(final TraceModel trace, final Template template) {
        if (template.match() instanceof MatchExpression.Source) {
            return TraceModel.ROOT;
        }
        final Frame cursor = trace.frame(trace.cursorOf());
        if (cursor != null && cursor.getTemplateId().equals(template.id())) {
            return cursor.getId();
        }
        final List<Frame> matches = trace.matches(template.id());
        return matches.isEmpty()
                ? -1
                : matches.get(0).getId();
    }

    /// The hue a top-level card's output is painted in, by its position - the same rule the output pane uses.
    public static String cardHue(final int index) {
        return Colours.hue(index);
    }

    private void apply(final List<OutputNode> next) {
        final Template template = host.template(templateId);
        if (template == null || readOnly) {
            return;
        }
        host.replace(host.withTemplate(Templates.withBody(template, next)));
    }

    private List<String> names() {
        final Template template = host.template(templateId);
        final Set<String> names = new LinkedHashSet<>();
        if (template != null) {
            for (final Declaration declaration : template.declarations()) {
                names.add(declaration.name());
            }
            for (final ParamDecl param : template.param()) {
                names.add(param.name());
            }
        }
        for (final EngineVars function : EngineVars.values()) {
            names.add(function.spelling());
        }
        return new ArrayList<>(names);
    }

    // ---- the cards ----

    public void onDescend(final long frameId) {
        host.setCursor(frameId);
    }

    public void onHover(final int index) {
        final long frame = annotatedFrame();
        host.hover(index < 0 || frame < 0
                ? null
                : Hot.instruction(frame, index));
    }

    /// Bring a top-level card of the cursor's own body into view.
    ///
    /// Guarded on the body on show being the cursor's, as [#setHot(Hot)] is. The strip's body
    /// and the cursor can part company: selecting a template that matched nothing leaves the
    /// cursor where it was (`ShapeshifterDesignController.goTo`) while the strip swaps to that
    /// template, and selecting the document row leaves the body panel off screen entirely.
    /// Scrolling to card *index* of whatever body happens to be there would be an answer to a
    /// question nobody asked.
    ///
    /// @param index The card's position in the body.
    public void revealCard(final int index) {
        if (annotatedFrame() == host.cursor()) {
            view.reveal(index);
        }
    }

    /// An instruction of the annotated frame lights its card.
    public void setHot(final Hot hot) {
        final long frame = annotatedFrame();
        view.setHot(hot != null && hot.getKind() == Hot.Kind.INSTRUCTION && frame >= 0
                         && hot.getFrameId() == frame
                ? hot.getIndex()
                : -1);
    }

    private long annotatedFrame() {
        final TraceModel trace = host.trace();
        final Template template = host.template(templateId);
        return trace == null || template == null
                ? -1
                : annotationFrame(trace, template);
    }

    /// A card clicked: it opens where it is.
    ///
    /// @param path The card's path.
    public void onEdit(final String path) {
        open(path, false);
    }

    /// A card's kind clicked: it opens with the kinds offered.
    ///
    /// @param path The card's path.
    public void onEditKind(final String path) {
        open(path, true);
    }

    private void open(final String path, final boolean kind) {
        if (readOnly) {
            return;
        }
        if (editing != null && path.equals(editingPath)) {
            // Already open: a click inside the card reaches here, and means nothing new
            if (kind) {
                editing.focus(true);
            }
            return;
        }
        final Shift shift = settle();
        if (shift == null) {
            return;
        }
        final String at = shift.path(path);
        final OutputNode node = Bodies.get(body, Bodies.path(at));
        if (node == null) {
            return;
        }
        editing = make(instructionEditorProvider);
        editing.read(node, null, names(), Modes.of(host.getProject()));
        editing.setOnCommit(this::settle);
        editing.setOnCancel(this::cancel);
        editingPath = at;
        editingList = null;
        view.setEditor(at, editing.asWidget());
        editing.focus(kind);
    }

    // A new card of a kind, as a draft where it will go.
    private void openNew(final String listPath, final int index, final String kind) {
        final Shift shift = settle();
        if (shift == null) {
            return;
        }
        editing = make(instructionEditorProvider);
        editing.read(null, kind, names(), Modes.of(host.getProject()));
        editing.setOnCommit(this::settle);
        editing.setOnCancel(this::cancel);
        editingPath = null;
        editingList = shift.path(listPath);
        editingIndex = shift.index(listPath, index);
        view.setNewEditor(editingList, editingIndex, editing.asWidget());
        editing.focus(false);
    }

    /// Takes the open draft, if there is one, before something else is done to the body.
    ///
    /// @return How the paths the caller holds have moved, or null when the draft could not be
    ///         taken — it says why itself — and the caller must not go ahead.
    private Shift settle() {
        if (editing == null) {
            return Shift.NONE;
        }
        final OutputNode written = editing.write();
        if (written == null) {
            return null;
        }
        final int dropped = Bodies.held(editing.getOriginal()) - Bodies.held(written);
        if (dropped > 0) {
            // A holder changed to a kind with no place for what it holds. The dialog this
            // replaces dropped them without a word; removing a holder asks, and so does this.
            final String message = "Changing this " + Instructions.kind(editing.getOriginal()) + " to "
                                   + Instructions.kind(written) + " drops " + (dropped == 1
                    ? "the instruction"
                    : "the " + dropped + " instructions") + " it holds. Change it?";
            ConfirmEvent.fire(this, message, ok -> {
                if (ok && editing != null) {
                    take(written, true);
                }
            });
            return null;
        }
        final Shift shift = editingPath == null
                ? new Shift(Bodies.path(editingList), editingIndex)
                : Shift.NONE;
        take(written, true);
        return shift;
    }

    // The draft, into the body. The editor goes first: the edit redraws the cards, and they
    // are to be drawn without it.
    private void take(final OutputNode written, final boolean refocus) {
        final String path = editingPath;
        final String list = editingList;
        final int index = editingIndex;
        final OutputNode original = editing.getOriginal();
        final boolean fromInsertion = path == null && list.equals(insertList) && index == insertIndex;
        dropDraft();
        if (path != null) {
            if (!written.equals(original)) {
                apply(Bodies.replace(body, Bodies.path(path), written));
            }
            if (refocus) {
                view.focusCard(path);
            }
            return;
        }
        if (insertList != null) {
            // The add line among the cards stays after what was put in, as it does for a line
            final Shift shift = new Shift(Bodies.path(list), index);
            final String movedList = shift.path(insertList);
            insertIndex = shift.index(insertList, insertIndex);
            insertList = movedList;
        }
        apply(Bodies.insert(body, Bodies.path(list), index, written));
        // The new row is the selected one
        view.select(Bodies.path(Bodies.child(Bodies.path(list), index)));
        if (insertList != null) {
            view.setInsertion(insertList, insertIndex);
        }
        if (refocus) {
            view.focusEntry(fromInsertion
                    ? Body.INSERT_KEY
                    : list);
        }
    }

    // Escape, or Cancel: the card as it was, or no new card, and the keyboard back where the
    // author started from.
    private void cancel() {
        if (editing == null) {
            return;
        }
        final String path = editingPath;
        final String list = editingList;
        final boolean fromInsertion = path == null && list.equals(insertList)
                                      && editingIndex == insertIndex;
        dropDraft();
        if (path != null) {
            view.focusCard(path);
        } else {
            view.focusEntry(fromInsertion
                    ? Body.INSERT_KEY
                    : list);
        }
    }

    // The draft let go, with nothing taken from it.
    private void dropDraft() {
        if (editing == null) {
            return;
        }
        final InstructionEditController editor = editing;
        editing = null;
        editingPath = null;
        editingList = null;
        view.clearEditor();
        release(editor);
    }

    /// The body is about to show another template, or to go off screen. The draft is taken if
    /// it can be written as it stands and let go if it cannot, with nobody asked: there is no
    /// card left on screen to ask about.
    ///
    /// **Taken a moment later, not now.** This is called from inside the selection that is
    /// replacing the body, which is often itself inside an edit being announced; an edit made
    /// from here would be announced from inside that one, and its listeners would then be told
    /// about the older project last. So what the body is to become is worked out now, while
    /// the body is still this template's, and put to the host once the selection has finished
    /// — and only if nothing else has changed the template in between.
    public void leave() {
        // The selection is a row of the body that is going
        view.select(null);
        onInsertClosed();
        if (editing == null) {
            return;
        }
        final OutputNode written = editing.writeQuietly();
        final OutputNode original = editing.getOriginal();
        final String id = templateId;
        final List<OutputNode> was = body;
        final List<OutputNode> next;
        if (written == null || Bodies.held(written) < Bodies.held(original)) {
            next = null;
        } else if (editingPath != null) {
            next = written.equals(original)
                    ? null
                    : Bodies.replace(body, Bodies.path(editingPath), written);
        } else {
            next = Bodies.insert(body, Bodies.path(editingList), editingIndex, written);
        }
        dropDraft();
        if (next != null) {
            Scheduler.get().scheduleDeferred(() -> {
                final Template now = host.template(id);
                if (now != null && !readOnly && now.body().equals(was)) {
                    host.replace(host.withTemplate(Templates.withBody(now, next)));
                }
            });
        }
    }

    // Removing the card that is open: there is nothing of its draft to keep.
    private Shift discard() {
        dropDraft();
        return Shift.NONE;
    }

    // Whether what the draft was opened on is still where it was.
    private boolean draftFits() {
        if (editingPath != null) {
            return Objects.equals(Bodies.get(body, Bodies.path(editingPath)), editing.getOriginal());
        }
        final List<OutputNode> list = Bodies.list(body, Bodies.path(editingList));
        return list != null && editingIndex <= list.size();
    }

    // ---- the add line among the cards ----

    /// A card asking for an add line before or after it.
    ///
    /// @param listPath The list the card is in.
    /// @param index The position the line goes before.
    public void onInsert(final String listPath, final int index) {
        if (readOnly) {
            return;
        }
        final Shift shift = settle();
        if (shift == null) {
            return;
        }
        final String list = shift.path(listPath);
        final int at = shift.index(listPath, index);
        final List<OutputNode> cards = Bodies.list(body, Bodies.path(list));
        if (cards == null) {
            return;
        }
        if (at >= cards.size()) {
            // After the last card is where the list's own line already is
            closeInsertion();
            view.setInsertion(null, 0);
            view.focusEntry(list);
            return;
        }
        insertList = list;
        insertIndex = at;
        view.setInsertion(list, at);
        view.focusEntry(Body.INSERT_KEY);
    }

    /// The add line among the cards, dismissed.
    public void onInsertClosed() {
        if (insertList != null) {
            closeInsertion();
            view.setInsertion(null, 0);
        }
    }

    private void closeInsertion() {
        insertList = null;
        insertIndex = 0;
    }

    public void onRemove(final String path) {
        if (readOnly) {
            return;
        }
        final Shift shift = editing != null && path.equals(editingPath)
                ? discard()
                : settle();
        if (shift == null) {
            return;
        }
        onInsertClosed();
        final int[] nodePath = Bodies.path(shift.path(path));
        final OutputNode node = Bodies.get(body, nodePath);
        if (node == null) {
            return;
        }
        final int held = Bodies.held(node);
        if (held == 0) {
            remove(nodePath);
            return;
        }
        final String message = "Remove this " + Instructions.kind(node) + " and the " + held + (held == 1
                ? " instruction"
                : " instructions") + " it holds?";
        ConfirmEvent.fire(this, message, ok -> {
            if (ok) {
                remove(nodePath);
            }
        });
    }

    // A row taken out, and the keyboard left on the list: on the row that has come up into its
    // place, or the one before it if it was the last, or the list's add line if it was the only.
    private void remove(final int[] nodePath) {
        final int[] list = Bodies.parent(nodePath);
        final int index = nodePath[nodePath.length - 1];
        apply(Bodies.remove(body, nodePath));
        final List<OutputNode> left = Bodies.list(body, list);
        if (left == null) {
            return;
        }
        if (left.isEmpty()) {
            view.focusEntry(Bodies.path(list));
        } else {
            view.focusCard(Bodies.path(Bodies.child(list, Math.min(index, left.size() - 1))));
        }
    }

    public void onMove(final String path, final int by) {
        if (readOnly) {
            return;
        }
        final Shift shift = settle();
        if (shift == null) {
            return;
        }
        onInsertClosed();
        final int[] from = Bodies.path(shift.path(path));
        final List<OutputNode> next = Bodies.move(body, from, by);
        if (next != body) {
            apply(next);
            // The keyboard goes with the row, so that it can be moved again
            view.focusCard(Bodies.path(Bodies.child(Bodies.parent(from), from[from.length - 1] + by)));
        }
    }

    public void onDrop(final String fromPath, final String toListPath, final int index) {
        if (readOnly) {
            return;
        }
        final Shift shift = settle();
        if (shift == null) {
            return;
        }
        onInsertClosed();
        // A row dragged somewhere else: the selection is not followed there
        view.select(null);
        final List<OutputNode> next = Bodies.moveTo(body, Bodies.path(shift.path(fromPath)),
                Bodies.path(shift.path(toListPath)), shift.index(toListPath, index));
        if (next != body) {
            apply(next);
        }
    }

    /// A line typed into a list, added as the cards it describes (design 47 A, ruled
    /// 2026-11-17). One edit however many cards the line is, so a templated line that splits
    /// into five arrives — and can be taken back — as one thing.
    ///
    /// @param listPath The list the line was typed into.
    /// @param index Where in it the cards go.
    /// @param line The line, as typed.
    public void onEntry(final String listPath, final int index, final String line) {
        if (readOnly) {
            return;
        }
        final List<OutputNode> cards = Instructions.cards(line);
        if (cards.isEmpty()) {
            return;
        }
        final Shift shift = settle();
        if (shift == null) {
            return;
        }
        final String at = shift.path(listPath);
        final int from = shift.index(listPath, index);
        final int[] list = Bodies.path(at);
        List<OutputNode> next = body;
        for (int i = 0; i < cards.size(); i++) {
            next = Bodies.insert(next, list, from + i, cards.get(i));
        }
        if (at.equals(insertList) && from <= insertIndex) {
            // Typed into the line among the cards, or above it in the same list: the line
            // stays after what was put in, ready for the next
            insertIndex += cards.size();
        }
        apply(next);
        // What was just added is the selected row, as a grid selects the row it has been given:
        // the toolbar's next press is about it, though the keyboard stays in the line
        view.select(Bodies.path(Bodies.child(list, from + cards.size() - 1)));
        if (insertList != null) {
            view.setInsertion(insertList, insertIndex);
        }
    }

    /// The kinds a new card can be, grouped by category, for a list path and an index in it.
    public void onAdd(final String listPath, final int index, final int x, final int y) {
        if (readOnly) {
            return;
        }
        final List<Item> items = new ArrayList<>();
        int priority = 0;
        Category group = null;
        for (final String kind : InstructionEditController.kinds()) {
            final Category category = kind.startsWith("(")
                    ? null
                    : Instructions.category(kind);
            if (category != group) {
                group = category;
                items.add(InfoMenuItem.builder().text(SafeHtmlUtils.fromString(category == null
                        ? "any"
                        : category.label())).build());
            }
            items.add(new IconMenuItem.Builder()
                    .priority(priority++)
                    .text(kind)
                    .command(() -> openNew(listPath, index, kind))
                    .build());
        }
        ShowMenuEvent.builder()
                .items(items)
                .popupPosition(new PopupPosition(x, y))
                .fire(this);
    }

    // ---- the branches ----

    public void onEditBranch(final String path, final int branch) {
        final Shift shift = settle();
        if (shift == null) {
            return;
        }
        final int[] nodePath = Bodies.path(shift.path(path));
        final OutputNode node = Bodies.get(body, nodePath);
        if (readOnly || !(node instanceof Holder holder) || !Bodies.branchIsOwn(holder, branch)) {
            return;
        }
        if (node instanceof Choose choose) {
            final ConditionEditController editor = conditionEditorProvider.get();
            editor.read(choose.when().get(branch).test(), names());
            editor.show(this, "Edit when", e -> {
                if (e.isOk()) {
                    final Condition test = editor.write();
                    if (test != null) {
                        apply(Bodies.replace(body, nodePath, Bodies.withWhen(choose, branch, test)));
                        e.hide();
                    } else {
                        // Nothing was written, so the dialog stays open — and its OK button has to come back
                        // out of its busy state, or the alert leaves it spinning for good.
                        e.reset();
                    }
                } else {
                    e.hide();
                }
            });
        } else if (node instanceof Switch s) {
            view.promptCase(s.cases().get(branch).value(), value -> {
                if (value != null && !value.trim().isEmpty()) {
                    apply(Bodies.replace(body, nodePath, Bodies.withCase(s, branch, value.trim())));
                }
            });
        }
    }

    public void onAddBranch(final String path) {
        final Shift shift = settle();
        if (shift == null) {
            return;
        }
        final int[] nodePath = Bodies.path(shift.path(path));
        final OutputNode node = Bodies.get(body, nodePath);
        if (readOnly) {
            return;
        }
        if (node instanceof Choose choose) {
            final ConditionEditController editor = conditionEditorProvider.get();
            editor.read(new Condition.IsFirst(), names());
            editor.show(this, "New when", e -> {
                if (e.isOk()) {
                    final Condition test = editor.write();
                    if (test != null) {
                        apply(Bodies.replace(body, nodePath, Bodies.addWhen(choose, test)));
                        e.hide();
                    } else {
                        // Nothing was written, so the dialog stays open — and its OK button has to come back
                        // out of its busy state, or the alert leaves it spinning for good.
                        e.reset();
                    }
                } else {
                    e.hide();
                }
            });
        } else if (node instanceof Switch s) {
            view.promptCase("", value -> {
                if (value != null && !value.trim().isEmpty()) {
                    apply(Bodies.replace(body, nodePath, Bodies.addCase(s, value.trim())));
                }
            });
        }
    }

    public void onRemoveBranch(final String path, final int branch) {
        final Shift shift = settle();
        if (shift == null) {
            return;
        }
        final int[] nodePath = Bodies.path(shift.path(path));
        final OutputNode node = Bodies.get(body, nodePath);
        if (readOnly || !(node instanceof Holder holder) || !Bodies.branchIsOwn(holder, branch)) {
            return;
        }
        final int held = holder.bodies().get(branch).size();
        final Runnable remove = () -> {
            if (node instanceof Choose choose) {
                apply(Bodies.replace(body, nodePath, Bodies.removeWhen(choose, branch)));
            } else if (node instanceof Switch s) {
                apply(Bodies.replace(body, nodePath, Bodies.removeCase(s, branch)));
            }
        };
        if (held == 0) {
            remove.run();
            return;
        }
        final String message = "Prune this branch and the " + held + (held == 1
                ? " instruction"
                : " instructions") + " it holds?";
        ConfirmEvent.fire(this, message, ok -> {
            if (ok) {
                remove.run();
            }
        });
    }

    // One place that says what read-only means here, called when the context changes and
    // when the pane is filled, so a lock change alone reaches the view.
    private void applyReadOnly() {
        final boolean locked = readOnly || host == null || host.template(templateId) == null;
        if (locked) {
            // Nothing can be taken into a body that cannot be changed
            dropDraft();
            onInsertClosed();
        }
        view.setReadOnly(locked);
    }

    /// How paths held from before a draft was taken read afterwards. Taking a new card puts it
    /// into a list, and whatever the author pointed at further down that list — the card they
    /// clicked, the place they dropped something — is one further on than it was.
    private static final class Shift {

        private static final Shift NONE = new Shift(null, 0);

        private final int[] list;
        private final int index;

        private Shift(final int[] list, final int index) {
            this.list = list;
            this.index = index;
        }

        // A node path or a list path, as it reads now.
        private String path(final String path) {
            return list == null
                    ? path
                    : Bodies.path(Bodies.shiftForInsert(Bodies.path(path), list, index));
        }

        // A position in a list, given the list's path as it read before.
        private int index(final String listPath, final int at) {
            return list != null && Bodies.path(list).equals(listPath) && at >= index
                    ? at + 1
                    : at;
        }
    }

}
