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
import stroom.shapeshifter.client.model.Instructions;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.Choose;
import stroom.shapeshifter.config.OutputNode.Holder;
import stroom.shapeshifter.config.OutputNode.Switch;
import stroom.widget.text.client.Label;
import stroom.widget.util.client.CssProperties;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.DataTransfer;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.DragLeaveEvent;
import com.google.gwt.event.dom.client.DragOverEvent;
import com.google.gwt.event.dom.client.DropEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/// One instruction, as a row of the body's list: kind · summary · the run's note, and for a holder its
/// branches beneath — each a head (then / when … / otherwise / case … / default) and a nested
/// list. The rows are one list with no gaps between them (the owner,
/// 2026-11-18): the keyboard is on one of them at a time, and the arrow keys move it.
///
/// Clicked, or Enter pressed on it, the card opens: its line gives way to its editor, in place
/// (design 47 D). Clicking its kind opens it with the kinds offered.
public class BodyCard extends Composite {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    FocusPanel head;
    @UiField
    Label kind;
    @UiField
    Label summary;
    @UiField
    SimplePanel editing;
    @UiField
    Label swatch;
    @UiField
    Label note;
    @UiField
    FlowPanel branches;

    @UiField
    Label grip;

    private final int[] path;
    private final Body.CardActions handlers;
    /// The card being dragged, across every card on the page. The drag data would be the tidier
    /// home for it, but a drag within one page has one subject at a time and reading the data
    /// during dragover is not allowed by the browser — which is exactly when the drop indicator
    /// has to decide whether it may land here.
    private static BodyCard dragging;

    // The add line a dragged card would land at the end of, marked as a card's half is.
    private static final String DROP_AT_END = "body-card__add--drop";

    public BodyCard(final int[] listPath, final int index, final OutputNode node, final int count,
                    final CardNote runNote, final Body.CardActions handlers, final boolean enabled,
                    final boolean topLevel) {
        this.path = Bodies.child(listPath, index);
        this.handlers = handlers;
        initWidget(BINDER.createAndBindUi(this));
        row(index, count);
        final Widget editor = enabled
                ? handlers.editorOf(Bodies.path(path))
                : null;
        head.setVisible(editor == null);
        editing.setVisible(editor != null);
        if (editor != null) {
            editing.setWidget(editor);
        }
        if (topLevel) {
            // A top-level card is what the run attributes output to (design 18 §5.5).
            head.addMouseOverHandler(event -> handlers.onHover(index));
            head.addMouseOutHandler(event -> handlers.onHover(-1));
        }
        final String kindName = Instructions.kind(node);
        kind.setText(kindName);
        summary.setText(Instructions.describe(node));
        summary.setTitle(Instructions.describe(node));
        head.setTitle("Click to edit this " + kindName);
        if (enabled) {
            kind.setTitle("Click to change what this " + kindName + " is");
        }
        grip.setVisible(enabled);
        if (enabled) {
            makeDraggable();
        }
        // The run's word on the card (design 18 §5.6): its swatch, what it wrote or dispatched,
        // and for a dispatch that matched, the first frame - a click on the note descends.
        swatch.setVisible(runNote != null && runNote.getColour() != null);
        if (runNote != null && runNote.getColour() != null) {
            CssProperties.set(swatch.getElement(), "--swatch__colour",
                    Colours.safe(runNote.getColour()));
        }
        note.setVisible(runNote != null && runNote.getText() != null);
        if (runNote != null && runNote.getText() != null) {
            note.setText(runNote.getText());
            if (runNote.getDescendTo() >= 0) {
                note.addStyleName("body-card__note--link");
                note.setTitle("Descend to the first match");
                note.addClickHandler((ClickEvent e) -> {
                    e.stopPropagation();
                    handlers.onDescend(runNote.getDescendTo());
                });
            }
        }
        if (node instanceof Holder holder) {
            final List<List<OutputNode>> bodies = holder.bodies();
            for (int b = 0; b < bodies.size(); b++) {
                branches.add(new Branch(holder, b, bodies.get(b), enabled));
            }
            if (enabled && (node instanceof Choose || node instanceof Switch)) {
                final Label addBranch = new Label(node instanceof Choose
                        ? "+ when"
                        : "+ case");
                addBranch.setStyleName("add-button body-card__add-branch");
                addBranch.addClickHandler((ClickEvent e) -> handlers.onAddBranch(Bodies.path(path)));
                branches.add(addBranch);
            }
        }
    }

    // What makes this a row of the list: the pointer and the keys, and what the body asks of it.
    private void row(final int index, final int count) {
        head.addClickHandler(event -> onHead(event));
        head.addKeyDownHandler(event -> onKey(event));
        final boolean[] focused = {false};
        head.addFocusHandler(event -> {
            focused[0] = true;
            handlers.onCardFocused(Bodies.path(path));
        });
        head.addBlurHandler(event -> focused[0] = false);
        kind.addClickHandler(event -> onKind(event));
        handlers.onCardShown(Bodies.path(path), new Body.Row() {
            @Override
            public void focus() {
                // The element's own focus, which is immediate. The panel's setFocus is put off
                // to a timer in this browser, and would then take the keyboard back from
                // whatever was given it next — a field of the row just opened.
                head.getElement().focus();
            }

            @Override
            public boolean isFocused() {
                return focused[0];
            }

            @Override
            public void setTabStop(final boolean stop) {
                head.setTabIndex(stop
                        ? 0
                        : -1);
            }

            @Override
            public void setSelected(final boolean selected) {
                head.setStyleName("body-card__card--selected", selected);
            }

            @Override
            public boolean isFirst() {
                return index == 0;
            }

            @Override
            public boolean isLast() {
                return index == count - 1;
            }
        });
    }

    public void setHot(final boolean hot) {
        if (hot) {
            head.addStyleName(Marks.HOT_CLASS);
        } else {
            head.removeStyleName(Marks.HOT_CLASS);
        }
    }

    /// Dragging (design 44 §5o). The grip is what starts it — the card itself stays clickable to
    /// edit — and a card accepts a drop on its upper or lower half, landing the dragged card
    /// before or after it. A card refuses itself and its own descendants: a holder cannot be put
    /// inside what it contains, and the indicator says so by not appearing.
    private void makeDraggable() {
        grip.addMouseDownHandler(e -> head.getElement().setDraggable(Element.DRAGGABLE_TRUE));
        grip.addMouseUpHandler(e -> head.getElement().setDraggable(Element.DRAGGABLE_FALSE));
        head.addDragStartHandler(e -> {
            dragging = this;
            e.setData("text", Bodies.path(path));
            e.getDataTransfer().setDropEffect(DataTransfer.DropEffect.MOVE);
            head.addStyleName("body-card__card--dragging");
        });
        head.addDragEndHandler(e -> {
            dragging = null;
            head.getElement().setDraggable(Element.DRAGGABLE_FALSE);
            head.removeStyleName("body-card__card--dragging");
            clearIndicator();
        });
        head.addDragOverHandler(e -> {
            if (!accepts()) {
                return;
            }
            e.preventDefault();
            clearIndicator();
            head.addStyleName(after(e.getNativeEvent().getClientY())
                    ? "body-card__card--drop-after"
                    : "body-card__card--drop-before");
        });
        head.addDragLeaveHandler(e -> clearIndicator());
        head.addDropHandler(e -> {
            e.preventDefault();
            if (!accepts()) {
                return;
            }
            final int[] listPath = Bodies.parent(path);
            final int at = path[path.length - 1] + (after(e.getNativeEvent().getClientY())
                    ? 1
                    : 0);
            final String from = Bodies.path(dragging.path);
            clearIndicator();
            handlers.onDrop(from, Bodies.path(listPath), at);
        });
    }

    /// Makes a list's add line take a dropped card, which lands at the end of that list (design
    /// 18 §5.6) — also how an empty branch gets its first card by dragging (design 47 §7.2,
    /// finding 17). A holder is refused a drop into its own lists, as a card refuses one.
    ///
    /// @param line The add line.
    /// @param listPath The list it ends.
    /// @param count How many cards the list holds: where a dropped card goes.
    /// @param handlers Told of the drop.
    static void acceptDropsAtEnd(final Widget line, final int[] listPath, final int count,
                                 final Body.CardActions handlers) {
        line.addDomHandler(e -> {
            if (dragging != null && !Bodies.isWithin(listPath, dragging.path)) {
                e.preventDefault();
                line.addStyleName(DROP_AT_END);
            }
        }, DragOverEvent.getType());
        line.addDomHandler(e -> line.removeStyleName(DROP_AT_END), DragLeaveEvent.getType());
        line.addDomHandler(e -> {
            e.preventDefault();
            line.removeStyleName(DROP_AT_END);
            if (dragging != null && !Bodies.isWithin(listPath, dragging.path)) {
                handlers.onDrop(Bodies.path(dragging.path), Bodies.path(listPath), count);
            }
        }, DropEvent.getType());
    }

    /// Whether this card may take the drop: something is being dragged, and it is not this one.
    private boolean accepts() {
        return dragging != null && dragging != this;
    }

    /// Which half of the card the pointer is in, and so which side of it the card lands.
    private boolean after(final int clientY) {
        return clientY > head.getAbsoluteTop() + head.getOffsetHeight() / 2;
    }

    private void clearIndicator() {
        head.removeStyleName("body-card__card--drop-before");
        head.removeStyleName("body-card__card--drop-after");
    }

    private void onHead(final ClickEvent e) {
        handlers.onEdit(Bodies.path(path));
    }

    // The keys of a row in a list. The arrows, Home and End move among the rows; Enter opens
    // the row; Delete removes it. Only when the row itself has the keyboard: a key pressed in a
    // field of a row open below would otherwise act on this one. And only bare keys: Alt and
    // an arrow walks the frames of the run, wherever in the tab the keyboard is
    // (ShapeshifterDesignController), and a row does not take that for itself.
    private void onKey(final KeyDownEvent event) {
        if (!event.getNativeEvent().getEventTarget().equals(head.getElement())
            || event.isAltKeyDown() || event.isControlKeyDown() || event.isMetaKeyDown()) {
            return;
        }
        final int key = event.getNativeKeyCode();
        final String at = Bodies.path(path);
        if (key == KeyCodes.KEY_ENTER) {
            handlers.onEdit(at);
        } else if (key == KeyCodes.KEY_DELETE) {
            handlers.onRemove(at);
        } else if (key == KeyCodes.KEY_UP) {
            handlers.onNavigate(at, Body.Step.PREVIOUS);
        } else if (key == KeyCodes.KEY_DOWN) {
            handlers.onNavigate(at, Body.Step.NEXT);
        } else if (key == KeyCodes.KEY_HOME) {
            handlers.onNavigate(at, Body.Step.FIRST);
        } else if (key == KeyCodes.KEY_END) {
            handlers.onNavigate(at, Body.Step.LAST);
        } else {
            return;
        }
        event.preventDefault();
    }

    private void onKind(final ClickEvent e) {
        e.stopPropagation();
        handlers.onEditKind(Bodies.path(path));
    }

    /// A holder's branch: its head line and the nested list.
    private final class Branch extends Composite {

        private Branch(final Holder holder, final int branch, final List<OutputNode> cards, final boolean enabled) {
            final FlowPanel panel = new FlowPanel();
            final String label = Bodies.branchLabel(holder, branch);
            if (label != null) {
                final FlowPanel headLine = new FlowPanel();
                headLine.setStyleName("body-card__branch-head");
                final Label key = new Label(label);
                key.setStyleName("body-card__branch-key");
                headLine.add(key);
                if (enabled && Bodies.branchIsOwn(holder, branch)) {
                    final Label edit = new Label("✎");
                    edit.setStyleName("item-action");
                    edit.setTitle("Edit this branch's " + (holder instanceof Choose
                            ? "condition"
                            : "value"));
                    edit.addClickHandler((ClickEvent e) -> handlers.onEditBranch(Bodies.path(path), branch));
                    headLine.add(edit);
                    final Label prune = new Label("✕");
                    prune.setStyleName("item-action item-action--delete");
                    prune.setTitle("Prune this branch");
                    prune.addClickHandler((ClickEvent e) -> handlers.onRemoveBranch(Bodies.path(path), branch));
                    headLine.add(prune);
                }
                panel.add(headLine);
            }
            final int[] listPath = Bodies.branch(Bodies.parent(path), path[path.length - 1], branch);
            panel.add(new CardList(listPath, cards, List.of(), handlers, enabled, true));
            initWidget(panel);
        }
    }

    interface Binder extends UiBinder<Widget, BodyCard> {

    }
}
