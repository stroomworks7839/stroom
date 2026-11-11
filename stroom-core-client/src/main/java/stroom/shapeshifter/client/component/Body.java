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

import stroom.shapeshifter.client.model.CardNote;
import stroom.shapeshifter.config.OutputNode;
import stroom.widget.component.client.Component;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;

import java.util.List;
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
        public void onAdd(final String listPath, final int index, final int x, final int y) {
            Body.this.addHandler.onAdd(listPath, index, x, y);
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
    private AddHandler addHandler = (listPath, index, x, y) -> {
    };
    private BiConsumer<String, Integer> onEditBranch = (path, branch) -> {
    };
    private Consumer<String> onAddBranch = path -> {
    };
    private BiConsumer<String, Integer> onRemoveBranch = (path, branch) -> {
    };

    @UiField
    FlowPanel cards;

    private boolean enabled = true;
    private CardList list;

    @Inject
    public Body() {
        initWidget(BINDER.createAndBindUi(this));
    }


    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        this.enabled = !readOnly;
    }

    public void setBody(final List<OutputNode> body, final List<CardNote> notes) {
        cards.clear();
        list = new CardList(new int[0], body, notes, cardActions, enabled, false);
        cards.add(list);
    }

    public void setHot(final int index) {
        if (list != null) {
            list.setHot(index);
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

    /// @param addHandler Told when add.
    public void setOnAdd(final AddHandler addHandler) {
        this.addHandler = addHandler;
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

    /// What [#setOnAdd] is given.
    public interface AddHandler {

        /// @param listPath The list path.
        /// @param index The index.
        /// @param x The x.
        /// @param y The y.

        void onAdd(final String listPath, final int index, final int x, final int y);
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

        /// @param listPath The list to add to.
        /// @param index    Where in it.
        /// @param x        Where the menu goes.
        /// @param y        Where the menu goes.
        void onAdd(String listPath, int index, int x, int y);

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
