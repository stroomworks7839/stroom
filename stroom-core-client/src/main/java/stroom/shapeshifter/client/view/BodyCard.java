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

package stroom.shapeshifter.client.view;

import stroom.shapeshifter.client.presenter.Bodies;
import stroom.shapeshifter.client.presenter.BodyUiHandlers;
import stroom.shapeshifter.client.presenter.CardNote;
import stroom.shapeshifter.client.presenter.Instructions;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.Choose;
import stroom.shapeshifter.config.OutputNode.Holder;
import stroom.shapeshifter.config.OutputNode.Switch;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.DataTransfer;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/**
 * One card: kind · summary · actions, and for a holder its branches beneath — each a head
 * (then / when … / otherwise / case … / default), a nested card list, and an add line.
 */
public class BodyCard extends Composite {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    FocusPanel head;
    @UiField
    Label kind;
    @UiField
    Label summary;
    @UiField
    FlowPanel actions;
    @UiField
    Label up;
    @UiField
    Label down;
    @UiField
    Label addAfter;
    @UiField
    Label remove;
    @UiField
    Label swatch;
    @UiField
    Label note;
    @UiField
    FlowPanel branches;

    @UiField
    Label grip;

    private final int[] path;
    private final BodyUiHandlers handlers;
    /**
     * The card being dragged, across every card on the page. The drag data would be the tidier
     * home for it, but a drag within one page has one subject at a time and reading the data
     * during dragover is not allowed by the browser — which is exactly when the drop indicator
     * has to decide whether it may land here.
     */
    private static BodyCard dragging;

    public BodyCard(final int[] listPath, final int index, final OutputNode node, final int count,
                    final CardNote runNote, final BodyUiHandlers handlers, final boolean enabled,
                    final boolean topLevel) {
        this.path = Bodies.child(listPath, index);
        this.handlers = handlers;
        initWidget(BINDER.createAndBindUi(this));
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
        actions.setVisible(enabled);
        grip.setVisible(enabled);
        if (enabled) {
            makeDraggable();
        }
        // The run's word on the card (design 18 §5.6): its swatch, what it wrote or dispatched,
        // and for a dispatch that matched, the first frame - a click on the note descends.
        swatch.setVisible(runNote != null && runNote.getColour() != null);
        if (runNote != null && runNote.getColour() != null) {
            swatch.getElement().getStyle().setBackgroundColor(Colours.safe(runNote.getColour()));
        }
        note.setVisible(runNote != null && runNote.getText() != null);
        if (runNote != null && runNote.getText() != null) {
            note.setText(runNote.getText());
            if (runNote.getDescendTo() >= 0) {
                note.addStyleName("ss-card-note--link");
                note.setTitle("Descend to the first match");
                note.addClickHandler((ClickEvent e) -> {
                    e.stopPropagation();
                    handlers.onDescend(runNote.getDescendTo());
                });
            }
        }
        up.setVisible(index > 0);
        down.setVisible(index < count - 1);
        if (node instanceof Holder holder) {
            addStyleName("ss-card--cont");
            final List<List<OutputNode>> bodies = holder.bodies();
            for (int b = 0; b < bodies.size(); b++) {
                branches.add(new Branch(holder, b, bodies.get(b), enabled));
            }
            if (enabled && (node instanceof Choose || node instanceof Switch)) {
                final Label addBranch = new Label(node instanceof Choose
                        ? "+ when"
                        : "+ case");
                addBranch.setStyleName("ss-addbtn ss-add-branch");
                addBranch.addClickHandler((ClickEvent e) -> handlers.onAddBranch(Bodies.path(path)));
                branches.add(addBranch);
            }
        }
    }

    public void setHot(final boolean hot) {
        if (hot) {
            head.addStyleName(Marks.HOT_CLASS);
        } else {
            head.removeStyleName(Marks.HOT_CLASS);
        }
    }

    /**
     * Dragging (design 44 §5o). The grip is what starts it — the card itself stays clickable to
     * edit — and a card accepts a drop on its upper or lower half, landing the dragged card
     * before or after it. A card refuses itself and its own descendants: a holder cannot be put
     * inside what it contains, and the indicator says so by not appearing.
     */
    private void makeDraggable() {
        grip.addMouseDownHandler(e -> head.getElement().setDraggable(Element.DRAGGABLE_TRUE));
        grip.addMouseUpHandler(e -> head.getElement().setDraggable(Element.DRAGGABLE_FALSE));
        head.addDragStartHandler(e -> {
            dragging = this;
            e.setData("text", Bodies.path(path));
            e.getDataTransfer().setDropEffect(DataTransfer.DropEffect.MOVE);
            head.addStyleName("ss-card--dragging");
        });
        head.addDragEndHandler(e -> {
            dragging = null;
            head.getElement().setDraggable(Element.DRAGGABLE_FALSE);
            head.removeStyleName("ss-card--dragging");
            clearIndicator();
        });
        head.addDragOverHandler(e -> {
            if (!accepts()) {
                return;
            }
            e.preventDefault();
            clearIndicator();
            head.addStyleName(after(e.getNativeEvent().getClientY())
                    ? "ss-card--drop-after"
                    : "ss-card--drop-before");
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

    /** Whether this card may take the drop: something is being dragged, and it is not this one. */
    private boolean accepts() {
        return dragging != null && dragging != this;
    }

    /** Which half of the card the pointer is in, and so which side of it the card lands. */
    private boolean after(final int clientY) {
        return clientY > head.getAbsoluteTop() + head.getOffsetHeight() / 2;
    }

    private void clearIndicator() {
        head.removeStyleName("ss-card--drop-before");
        head.removeStyleName("ss-card--drop-after");
    }

    @UiHandler("head")
    void onHead(final ClickEvent e) {
        handlers.onEdit(Bodies.path(path));
    }

    @UiHandler("up")
    void onUp(final ClickEvent e) {
        e.stopPropagation();
        handlers.onMove(Bodies.path(path), -1);
    }

    @UiHandler("down")
    void onDown(final ClickEvent e) {
        e.stopPropagation();
        handlers.onMove(Bodies.path(path), 1);
    }

    @UiHandler("addAfter")
    void onAddAfter(final ClickEvent e) {
        e.stopPropagation();
        handlers.onAdd(Bodies.path(Bodies.parent(path)), path[path.length - 1] + 1, e.getClientX(),
                e.getClientY());
    }

    @UiHandler("remove")
    void onRemove(final ClickEvent e) {
        e.stopPropagation();
        handlers.onRemove(Bodies.path(path));
    }

    /** A holder's branch: its head line, the nested cards, the add line. */
    private final class Branch extends Composite {

        private Branch(final Holder holder, final int branch, final List<OutputNode> cards, final boolean enabled) {
            final FlowPanel panel = new FlowPanel();
            panel.setStyleName("ss-branch");
            final String label = Bodies.branchLabel(holder, branch);
            if (label != null) {
                final FlowPanel headLine = new FlowPanel();
                headLine.setStyleName("ss-br-head");
                final Label key = new Label(label);
                key.setStyleName("ss-br-key");
                headLine.add(key);
                if (enabled && Bodies.branchIsOwn(holder, branch)) {
                    final Label edit = new Label("✎");
                    edit.setStyleName("ss-act");
                    edit.setTitle("Edit this branch's " + (holder instanceof Choose
                            ? "condition"
                            : "value"));
                    edit.addClickHandler((ClickEvent e) -> handlers.onEditBranch(Bodies.path(path), branch));
                    headLine.add(edit);
                    final Label prune = new Label("✕");
                    prune.setStyleName("ss-act ss-act--del");
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
