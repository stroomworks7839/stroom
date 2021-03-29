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

package stroom.widget.util.client;

import com.google.gwt.user.cellview.client.HasSelection;
import com.google.gwt.view.client.SelectionModel.AbstractSelectionModel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class MultiSelectionModelImpl<T> extends AbstractSelectionModel<T>
        implements MultiSelectionModel<T>, HasSelection<T> {

    private Selection<T> selection = new Selection<>();
    private final Set<T> changes = new HashSet<>();

    public MultiSelectionModelImpl() {
        super(null);
    }

    @Override
    public Selection<T> getSelection() {
        return new Selection<T>(selection);
    }

    @Override
    public void setSelection(final Selection<T> selection, final SelectionType selectionType) {
        selection.getSelectedItems().forEach(selected -> {
            if (!this.selection.isSelected(selected)) {
                changes.add(selected);
            }
        });
        this.selection.getSelectedItems().forEach(selected -> {
            if (!selection.isSelected(selected)) {
                changes.add(selected);
            }
        });
        this.selection = selection;

        if (changes.size() > 0) {
            fireSelectionChangeEvent();
            fireChange(selectionType);
        } else if (selectionType.isDoubleSelect() || selectionType.isRightClick()) {
            fireChange(selectionType);
        }
    }

    /**
     * Get a list of all selected items.
     */
    @Override
    public List<T> getSelectedItems() {
        return selection.getSelectedItems();
    }

    /**
     * Tests if the specified item is selected.
     */
    @Override
    public boolean isSelected(final T item) {
        return selection.isSelected(item);
    }

    @Override
    public void setSelected(final T item, final boolean selected) {
        setSelected(item, selected, new SelectionType(false, false));
    }

    @Override
    public void setSelected(final T item) {
        setSelected(item, new SelectionType(false, false));
    }

    /**
     * Sets the specified item as the only selected item, i.e. clears the current
     * selection and sets a single item selected.
     */
    @Override
    public void setSelected(final T item, final SelectionType selectionType) {
        if (item == null) {
            clear();

        } else {
            final boolean currentlySelected = isSelected(item);
            if (!currentlySelected || selection.size() != 1) {
                List<T> items = selection.getSelectedItems();

                // Mark changes.
                if (currentlySelected) {
                    items.stream().filter(t -> !t.equals(item)).forEach(changes::add);
                } else {
                    changes.addAll(items);
                    changes.add(item);
                }

                selection.setSelected(item);

                fireSelectionChangeEvent();
                fireChange(selectionType);
            }
        }
    }

    /**
     * Sets the selected state of the specified item.
     */
    @Override
    public void setSelected(final T item, final boolean selected, final SelectionType selectionType) {
        if (item != null) {
            final boolean currentlySelected = isSelected(item);
            selection.setSelected(item, selected);
            if (currentlySelected != selected) {
                changes.add(item);
                fireSelectionChangeEvent();
                fireChange(selectionType);
            }
        }
    }

    /**
     * Gets the most recently selected item or only selected item if an item is selected, null otherwise.
     */
    @Override
    public T getSelected() {
        return selection.getSelected();
    }

    /**
     * Clears all selected items.
     */
    @Override
    public void clear() {
        if (selection.size() > 0) {
            changes.addAll(selection.getSelectedItems());
            selection.clear();
            fireSelectionChangeEvent();
            fireChange(new SelectionType(false, false));
        }
    }

    protected void fireChange(final SelectionType selectionType) {
    }

    @Override
    public boolean hasSelectionChanged(final T item) {
        return changes.remove(item);
    }
}
