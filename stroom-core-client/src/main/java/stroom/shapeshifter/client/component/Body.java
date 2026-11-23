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

import stroom.shapeshifter.client.model.Bodies;
import stroom.shapeshifter.client.model.CardNote;
import stroom.shapeshifter.config.OutputNode;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.Toolbar;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;
import stroom.widget.util.client.ContentFocus;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class Body extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    // The cards are nested widgets of this component; they reach it through one object rather
    // than ten constructor arguments, and it passes what they do on to the callbacks above.
    private final CardActions cardActions = new CardActions() {
        @Override
        public void onEdit(final String path) {
            Body.this.onEdit.accept(path);
        }

        @Override
        public void onDescend(final long frameId) {
            Body.this.onDescend.accept(frameId);
        }

        @Override
        public void onHover(final int index) {
            Body.this.onHover.accept(index);
        }

        @Override
        public void onRemove(final String path) {
            Body.this.onRemove.accept(path);
        }

        @Override
        public void onMove(final String path, final int by) {
            Body.this.onMove.accept(path, by);
        }

        @Override
        public void onDrop(final String fromPath, final String toListPath, final int index) {
            Body.this.dropHandler.onDrop(fromPath, toListPath, index);
        }

        @Override
        public void onCardShown(final String path, final Row row) {
            shown.put(path, row);
        }

        @Override
        public void onCardFocused(final String path) {
            select(path);
        }

        @Override
        public void onNavigate(final String path, final Step step) {
            final List<String> order = new ArrayList<>(shown.keySet());
            final int at = order.indexOf(path);
            if (at < 0) {
                return;
            }
            final int to;
            switch (step) {
                case PREVIOUS:
                    to = at - 1;
                    break;
                case NEXT:
                    to = at + 1;
                    break;
                case FIRST:
                    to = 0;
                    break;
                default:
                    to = order.size() - 1;
                    break;
            }
            if (to >= 0 && to < order.size()) {
                shown.get(order.get(to)).focus();
            }
        }

        @Override
        public void onEditKind(final String path) {
            Body.this.onEditKind.accept(path);
        }

        @Override
        public void onNew(final String listPath, final int index) {
            // From a list's own add link: the keyboard goes back to the link if it is dropped
            Body.this.newHandler.onNew(listPath, index, null);
        }

        @Override
        public void onAddShown(final String path, final Element link) {
            links.put(path, link);
        }

        @Override
        public Widget editorOf(final String path) {
            return path.equals(editorPath)
                    ? editor
                    : null;
        }

        @Override
        public Widget newEditorAt(final String listPath, final int index) {
            return editor != null && editorPath == null && listPath.equals(editorList)
                   && index == editorIndex
                    ? editor
                    : null;
        }

        @Override
        public void onEditBranch(final String path, final int branch) {
            Body.this.onEditBranch.accept(path, branch);
        }

        @Override
        public void onAddBranch(final String path) {
            Body.this.onAddBranch.accept(path);
        }

        @Override
        public void onRemoveBranch(final String path, final int branch) {
            Body.this.onRemoveBranch.accept(path, branch);
        }
    };

    private Consumer<String> onEdit = path -> {
    };
    private Consumer<Long> onDescend = frameId -> {
    };
    private IntConsumer onHover = index -> {
    };
    private Consumer<String> onRemove = path -> {
    };
    private BiConsumer<String, Integer> onMove = (path, by) -> {
    };
    private DropHandler dropHandler = (fromPath, toListPath, index) -> {
    };
    private Consumer<String> onEditKind = path -> {
    };
    private NewHandler newHandler = (listPath, index, from) -> {
    };
    // Every row in the drawing on screen, by its path, in the order they are drawn: a holder's
    // rows come after it and before the row that follows it, which is the order the arrow keys
    // walk them in.
    private final Map<String, Row> shown = new LinkedHashMap<>();
    // Every list's add link in the drawing on screen, by the path the list's next card will
    // have, as what puts the keyboard on it. A link is not a row: it is not walked to with the
    // arrows and is never the selected one.
    private final Map<String, Element> links = new HashMap<>();
    // The selected row: the one the keyboard or the pointer was last on, or that is open. It is
    // what the toolbar acts on, and it is the rows' one tab stop between them (the first row,
    // until one has been selected). Kept as a path, so whoever changes the body says where the
    // selection has gone to ([#select]) or it would land on whatever came into its place.
    private String current;

    // The open card's editor (design 47 D): in place of the card at editorPath, or, when that
    // is null, as a new card at editorIndex of the list at editorList.
    private Widget editor;
    private String editorPath;
    private String editorList;
    private int editorIndex;
    // The row the keyboard goes to once the cards are next drawn.
    private String focusCardNext;
    private BiConsumer<String, Integer> onEditBranch = (path, branch) -> {
    };
    private Consumer<String> onAddBranch = path -> {
    };
    private BiConsumer<String, Integer> onRemoveBranch = (path, branch) -> {
    };

    @UiField
    FlowPanel cards;
    @UiField
    Toolbar toolbar;
    @UiField
    ToolbarButton add;
    @UiField
    ToolbarButton remove;
    @UiField
    ToolbarButton up;
    @UiField
    ToolbarButton down;

    private List<OutputNode> body = List.of();
    private List<CardNote> notes = List.of();
    private boolean enabled = true;
    private CardList list;

    @Inject
    public Body() {
        initWidget(BINDER.createAndBindUi(this));
        // Add, remove, up, down: the template panel's order (the owner, 2026-11-18). There is
        // one way to add from here and it is below — below the selected row, in the list it is
        // in, or at the end of the body when no row is selected — so Add is always there to
        // press, as the template panel's is.
        button(add, SvgImage.ADD, "Add an instruction below the selected one, or at the end");
        add.setOnClick(() -> {
            if (!enabled) {
                return;
            }
            if (current != null && shown.containsKey(current)) {
                newHandler.onNew(listOf(current), indexOf(current) + 1, current);
            } else {
                newHandler.onNew(Bodies.path(new int[0]), body.size(), null);
            }
        });
        action(remove, SvgImage.DELETE, "Remove the selected instruction", () -> onRemove.accept(current));
        action(up, SvgImage.UP, "Move the selected instruction up", () -> onMove.accept(current, -1));
        action(down, SvgImage.DOWN, "Move the selected instruction down", () -> onMove.accept(current, 1));
        enableActions();
    }

    // One of the toolbar's buttons. Pressing it does not take the keyboard off the row it is
    // about to act on.
    private static void button(final ToolbarButton button, final SvgImage icon, final String title) {
        button.setSvg(icon);
        button.setTitle(title);
        button.setFocusOnMouseDown(false);
    }

    // One of the toolbar's actions on the selected row.
    private void action(final ToolbarButton button, final SvgImage icon, final String title,
                        final Runnable act) {
        button(button, icon, title);
        button.setOnClick(() -> {
            if (enabled && current != null && shown.containsKey(current)) {
                act.run();
            }
        });
    }

    private static String listOf(final String path) {
        return Bodies.path(Bodies.parent(Bodies.path(path)));
    }

    private static int indexOf(final String path) {
        final int[] nodePath = Bodies.path(path);
        return nodePath[nodePath.length - 1];
    }

    /// Selects a row, or none for null: the row the toolbar acts on. Whoever changes the body
    /// calls this with where the selected row has gone to, since it is kept by its place.
    ///
    /// @param path The row's path, or null.
    public void select(final String path) {
        current = path;
        tabStops();
        enableActions();
    }

    /// @return The selected row's path, or null.
    public String getSelected() {
        return current;
    }

    // What the toolbar offers: nothing read only, and otherwise whatever can be done to the
    // selected row where it is.
    private void enableActions() {
        toolbar.setVisible(enabled);
        final Row row = current == null
                ? null
                : shown.get(current);
        final boolean selected = enabled && row != null;
        add.setEnabled(enabled);
        remove.setEnabled(selected);
        up.setEnabled(selected && !row.isFirst());
        down.setEnabled(selected && !row.isLast());
    }


    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        this.enabled = !readOnly;
        draw(true);
    }

    public void setBody(final List<OutputNode> body, final List<CardNote> notes) {
        this.body = body == null
                ? List.of()
                : body;
        this.notes = notes == null
                ? List.of()
                : notes;
        draw(true);
    }

    // The cards are built with read-only in force, so a change of it draws them again: the hook
    // only changing a flag would leave the last drawing in place, with the actions it had then.
    //
    // And a drawing is not only the author's own edits. A run's trace lands a moment after every
    // edit and redraws the cards to say what each wrote — so without care, a line being typed
    // would be wiped, and the keyboard taken away, by the run of the card before it.
    //
    // The open card's editor is therefore carried across, as the widget it is: it is a draft,
    // and drawing it afresh would lose what has been typed into it. If the keyboard was in it,
    // it goes back.
    //
    // A row the keyboard was on gets it back as well, unless a card is being opened: the
    // keyboard is then on its way into the card, and a row given it here would take the
    // selection with it — a row says it is selected while the card that is open is another.
    private void draw(final boolean keepRow) {
        final Element active = editor == null
                ? null
                : ContentFocus.activeElement();
        final boolean inEditor = active != null && editor.getElement().isOrHasChild(active);
        // ...and so is the row the keyboard is on, or a run landing would take it off the list
        String focusedRow = null;
        for (final Map.Entry<String, Row> row : shown.entrySet()) {
            if (row.getValue().isFocused()) {
                focusedRow = row.getKey();
            }
        }
        // ...and so is an add link the keyboard is on: Escape from a new card puts it there, and
        // the run the edit before it started could land a moment later and take it away
        final Element focusedElement = ContentFocus.activeElement();
        String focusedLink = null;
        for (final Map.Entry<String, Element> link : links.entrySet()) {
            if (link.getValue() == focusedElement) {
                focusedLink = link.getKey();
            }
        }
        shown.clear();
        links.clear();
        cards.clear();
        list = new CardList(new int[0], body, notes, cardActions, enabled, false, null);
        cards.add(list);
        tabStops();
        enableActions();
        if (inEditor) {
            active.focus();
        } else if (keepRow && focusedRow != null && shown.containsKey(focusedRow)) {
            shown.get(focusedRow).focus();
        } else if (keepRow && focusedLink != null && links.containsKey(focusedLink)) {
            links.get(focusedLink).focus();
        }
        focusPending();
    }

    // One tab stop for the rows between them: the selected row, or the first. The arrow keys
    // move among the rows; Tab leaves them. And the selected row says that it is.
    private void tabStops() {
        final String stop = current != null && shown.containsKey(current)
                ? current
                : shown.isEmpty()
                        ? null
                        : shown.keySet().iterator().next();
        for (final Map.Entry<String, Row> row : shown.entrySet()) {
            row.getValue().setTabStop(row.getKey().equals(stop));
            row.getValue().setSelected(row.getKey().equals(current));
        }
    }

    // The keyboard, put where it was last asked to go, if that is on screen yet.
    private void focusPending() {
        if (focusCardNext != null && shown.containsKey(focusCardNext)) {
            shown.get(focusCardNext).focus();
            focusCardNext = null;
        } else if (focusCardNext != null && links.containsKey(focusCardNext)) {
            links.get(focusCardNext).focus();
            focusCardNext = null;
        }
    }

    /// Shows an editor in place of a card (design 47 D).
    ///
    /// @param path The card's path.
    /// @param editor The editor.
    public void setEditor(final String path, final Widget editor) {
        // The open row is the selected one: the toolbar acts on it
        current = path;
        this.editor = editor;
        // The keyboard is going into the editor, not wherever it was last asked to go
        focusCardNext = null;
        this.editorPath = path;
        this.editorList = null;
        draw(false);
        // All of it, and not only the field the keyboard is about to go into: the body is a
        // short pane, and the way out of the card is at its end
        editor.getElement().scrollIntoView();
    }

    /// Shows an editor for a card that does not exist yet, where it will go: among the cards,
    /// or in place of the list's add link when that is where it goes.
    ///
    /// @param listPath The list it will go in.
    /// @param index Where in it.
    /// @param editor The editor.
    public void setNewEditor(final String listPath, final int index, final Widget editor) {
        // What is open is what is selected, and this is not a row yet: no row says it is the
        // selected one while the keyboard is in a card that is not it.
        current = null;
        this.editor = editor;
        // The keyboard is going into the editor, not wherever it was last asked to go
        focusCardNext = null;
        this.editorPath = null;
        this.editorList = listPath;
        this.editorIndex = index;
        draw(false);
        // All of it, and not only the field the keyboard is about to go into: the body is a
        // short pane, and the way out of the card is at its end
        editor.getElement().scrollIntoView();
    }

    /// Takes the editor away, leaving the cards as the body has them.
    public void clearEditor() {
        if (editor != null) {
            editor = null;
            editorPath = null;
            editorList = null;
            draw(true);
        }
    }

    /// Puts the keyboard on a row, now if it is on screen and otherwise when it next is.
    ///
    /// @param path The row's path: a card's, or for a list's add link the path its next card
    ///             will have.
    public void focusCard(final String path) {
        focusCardNext = path;
        focusPending();
    }

    public void setHot(final int index) {
        if (list != null) {
            list.setHot(index);
        }
    }

    /// Bring a top-level card into view: what clicking the output it wrote asks for (design 18
    /// §5.13.3).
    ///
    /// **Scrolling is the whole of it.** Pointing at that output already lights the card
    /// through the `highlight` vocabulary, and lighting it from here as well would be undone a
    /// moment later: `highlight` is the pointer's channel, so the pointer leaving the output
    /// clears it. What the click adds is reaching a card that hover cannot say anything about,
    /// because it is off the bottom of a long body.
    ///
    /// On a body short enough to be wholly on screen the click therefore does nothing at all,
    /// which is the right amount: the card is already in front of the reader and already lit.
    ///
    /// @param index The card's position in the body.
    public void reveal(final int index) {
        if (list != null) {
            list.reveal(index);
        }
    }

    public void promptCase(final String current, final Consumer<String> then) {
        // A case is one value; the browser's prompt is the smallest honest dialog for it.
        then.accept(Window.prompt("Case value", current));
    }

    /// @param onEdit Told when edit.
    public void setOnEdit(final Consumer<String> onEdit) {
        this.onEdit = onEdit;
    }

    /// @param onDescend Told when descend.
    public void setOnDescend(final Consumer<Long> onDescend) {
        this.onDescend = onDescend;
    }

    /// @param onHover Told when hover.
    public void setOnHover(final IntConsumer onHover) {
        this.onHover = onHover;
    }

    /// @param onRemove Told when remove.
    public void setOnRemove(final Consumer<String> onRemove) {
        this.onRemove = onRemove;
    }

    /// @param onMove Told when move.
    public void setOnMove(final BiConsumer<String, Integer> onMove) {
        this.onMove = onMove;
    }

    /// @param dropHandler Told when drop.
    public void setOnDrop(final DropHandler dropHandler) {
        this.dropHandler = dropHandler;
    }

    /// @param onEditKind Told when a card's kind is clicked: open it, with the kinds offered.
    public void setOnEditKind(final Consumer<String> onEditKind) {
        this.onEditKind = onEditKind;
    }

    /// @param newHandler Told when a new card is asked for: a list's add link pressed, or the
    ///                   toolbar's Add.
    public void setOnNew(final NewHandler newHandler) {
        this.newHandler = newHandler;
    }

    /// @param onEditBranch Told when editbranch.
    public void setOnEditBranch(final BiConsumer<String, Integer> onEditBranch) {
        this.onEditBranch = onEditBranch;
    }

    /// @param onAddBranch Told when addbranch.
    public void setOnAddBranch(final Consumer<String> onAddBranch) {
        this.onAddBranch = onAddBranch;
    }

    /// @param onRemoveBranch Told when removebranch.
    public void setOnRemoveBranch(final BiConsumer<String, Integer> onRemoveBranch) {
        this.onRemoveBranch = onRemoveBranch;
    }


    /// What [#setOnDrop] is given.
    public interface DropHandler {

        /// @param fromPath The from path.
        /// @param toListPath The to list path.
        /// @param index The index.

        void onDrop(final String fromPath, final String toListPath, final int index);
    }

    /// What [#setOnNew] is given.
    public interface NewHandler {

        /// @param listPath The list the card is to go in.
        /// @param index Where in it.
        /// @param from The row the author was on, to go back to if the card is dropped; null
        ///             when it was asked for from the list's own add link.
        void onNew(String listPath, int index, String from);
    }

    /// A card's row, as the body needs it: enough to put the keyboard on it, to know it is
    /// there, and to say whether Tab stops at it.
    public interface Row {

        /// Puts the keyboard on the row.
        void focus();

        /// @return Whether the keyboard is on the row.
        boolean isFocused();

        /// @param stop Whether Tab stops at the row.
        void setTabStop(boolean stop);

        /// @param selected Whether the row is the selected one.
        void setSelected(boolean selected);

        /// @return Whether the row is the first of its list, and so cannot be moved up.
        boolean isFirst();

        /// @return Whether the row is the last of its list, and so cannot be moved down.
        boolean isLast();
    }

    /// Where an arrow key, Home or End takes the keyboard among the rows.
    public enum Step {
        PREVIOUS,
        NEXT,
        FIRST,
        LAST
    }

    /// What a card deep in the body tells the component, which passes it on to its controller
    /// through the callbacks above. The cards are nested, so this travels down the tree with them.
    public interface CardActions {

        /// @param path The node's path.
        void onEdit(String path);

        /// @param frameId The frame to descend into.
        void onDescend(long frameId);

        /// @param index The card hovered, or -1 for none.
        void onHover(int index);

        /// @param path The node's path.
        void onRemove(String path);

        /// @param path The node's path.
        /// @param by   How far to move it.
        void onMove(String path, int by);

        /// @param fromPath   What was dragged.
        /// @param toListPath The list it was dropped in.
        /// @param index      Where in that list.
        void onDrop(String fromPath, String toListPath, int index);

        /// @param path The path of a card that has been drawn.
        /// @param row  The card's row, as the body needs it.
        void onCardShown(String path, Row row);

        /// @param path The path of the card the keyboard has arrived on.
        void onCardFocused(String path);

        /// @param path The path of the card the keyboard is on.
        /// @param step Which row it is to go to.
        void onNavigate(String path, Step step);

        /// @param path The node's path: open it, with the kinds offered.
        void onEditKind(String path);

        /// @param listPath The list whose add link was pressed.
        /// @param index    How many cards it has, which is where the new one goes.
        void onNew(String listPath, int index);

        /// @param path The path the next card of a list will have.
        /// @param link That list's add link, which the keyboard can be put on.
        void onAddShown(String path, Element link);

        /// @param path A card's path.
        /// @return The editor open in its place, or null.
        Widget editorOf(String path);

        /// @param listPath A list's path.
        /// @param index    A position in it.
        /// @return The editor of a new card that will go there, or null.
        Widget newEditorAt(String listPath, int index);

        /// @param path   The node's path.
        /// @param branch Which branch.
        void onEditBranch(String path, int branch);

        /// @param path The node's path.
        void onAddBranch(String path);

        /// @param path   The node's path.
        /// @param branch Which branch.
        void onRemoveBranch(String path, int branch);
    }

    interface Binder extends UiBinder<Widget, Body> {

    }
}
