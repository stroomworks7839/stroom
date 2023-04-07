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

package stroom.widget.menu.client.presenter;

import stroom.task.client.TaskEndEvent;
import stroom.task.client.TaskStartEvent;
import stroom.widget.menu.client.presenter.MenuPresenter.MenuView;
import stroom.widget.popup.client.event.HidePopupEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupPosition;
import stroom.widget.popup.client.presenter.PopupPosition.HorizontalLocation;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Element;
import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;
import java.util.Objects;

public class MenuPresenter
        extends MyPresenterWidget<MenuView>
        implements MenuUiHandlers {

    private static int HORIZONTAL_PADDING = 2;
    private static int VERTICAL_PADDING = 4;

    private final Provider<MenuPresenter> menuPresenterProvider;
    private MenuPresenter currentMenu;
    private MenuItem currentItem;

    private MenuPresenter parent;
    private MenuItem parentItem;

    @Inject
    public MenuPresenter(final EventBus eventBus,
                         final MenuView view,
                         final Provider<MenuPresenter> menuPresenterProvider) {
        super(eventBus, view);
        this.menuPresenterProvider = menuPresenterProvider;
        view.setUiHandlers(this);
    }

    @Override
    public void toggleSubMenu(final MenuItem menuItem, final Element element) {
        if (currentItem != null && Objects.equals(currentItem, menuItem)) {
            hideSubMenu();
        } else {
            showSubMenu(menuItem, element);
        }
    }

    @Override
    public void showSubMenu(final MenuItem menuItem, final Element element) {
        // Only change the popup if the item selected is changing and we have
        // some sub items.
        if (currentItem == null || !currentItem.equals(menuItem)) {
            // We are changing the highlighted item so close the current popup
            // if it is open.
            hideChildren(false, false);

            if (menuItem instanceof HasChildren) {
                // Try and get some sub items.
                final HasChildren hasChildren = (HasChildren) menuItem;

                hasChildren.getChildren().onSuccess(children -> {
                    if (children != null && children.size() > 0) {
                        // We are changing the highlighted item so close the current popup
                        // if it is open.
                        hideChildren(false, false);

                        final MenuPresenter presenter = menuPresenterProvider.get();
                        presenter.setParent(MenuPresenter.this, menuItem);
//                        presenter.setHighlightItems(getHighlightItems());
                        presenter.setData(children);

                        // Set the current presenter telling us that the
                        // popup is showing.
                        currentMenu = presenter;
                        currentItem = menuItem;

                        final PopupPosition popupPosition = new PopupPosition(
                                element.getAbsoluteRight() + HORIZONTAL_PADDING,
                                element.getAbsoluteLeft() - HORIZONTAL_PADDING,
                                element.getAbsoluteTop() + VERTICAL_PADDING + 30,
                                element.getAbsoluteTop() - VERTICAL_PADDING,
                                HorizontalLocation.RIGHT,
                                null);

                        ShowPopupEvent.builder(presenter)
                                .popupType(PopupType.POPUP)
                                .popupPosition(popupPosition)
                                .addAutoHidePartner(element)
                                .onHideRequest(e -> {
                                    presenter.hideChildren(e.isAutoClose(), e.isOk());
                                    presenter.hideSelf(e.isAutoClose(), e.isOk());
                                    currentMenu = null;
                                    currentItem = null;
                                })
                                .fire();
                    }
                });
            }
        }
    }

    @Override
    public void hideSubMenu() {
        hideChildren(false, false);
    }

    @Override
    public void ensureParentItemSelected() {
        if (parent != null && parentItem != null) {
            parent.getView().ensureItemSelected(parentItem);
        }
    }

    public void focus() {
        getView().focus();
    }

    @Override
    public void focusSubMenu() {
        if (currentMenu != null) {
            currentMenu.selectFirstItem();
        }
    }

    public void selectFirstItem() {
        getView().selectFirstItem();
    }

    @Override
    public void focusParent() {
        if (parent != null) {
            hideChildren(false, false);
            parent.getView().focus();
        }
    }

    @Override
    public void execute(final MenuItem menuItem) {
        if (menuItem != null && menuItem.getCommand() != null) {
            TaskStartEvent.fire(MenuPresenter.this);
            Scheduler.get().scheduleDeferred(() -> {
                try {
                    hideAll(false, false);
                    menuItem.getCommand().execute();
                } finally {
                    TaskEndEvent.fire(MenuPresenter.this);
                }
            });
        }
    }

    private void hideSelf(final boolean autoClose, final boolean ok) {
        HidePopupEvent.builder(this).autoClose(autoClose).ok(ok).fire();
    }

    private void hideChildren(final boolean autoClose, final boolean ok) {
        // First make sure all children are hidden.
        if (currentMenu != null) {
            currentMenu.hideChildren(autoClose, ok);
            currentMenu.hideSelf(autoClose, ok);
            currentMenu = null;
            currentItem = null;
        }
    }

    private void hideParent(final boolean autoClose, final boolean ok) {
        // First make sure all children are hidden.
        if (parent != null) {
            parent.hideSelf(autoClose, ok);
            parent.hideParent(autoClose, ok);
        }
    }

    @Override
    public void escape() {
        hideAll(true, false);
    }

    public void hideAll(final boolean autoClose, final boolean ok) {
        hideChildren(autoClose, ok);
        hideSelf(autoClose, ok);
        hideParent(autoClose, ok);
    }

    public void setParent(final MenuPresenter parent, final MenuItem parentItem) {
        this.parent = parent;
        this.parentItem = parentItem;
    }

    public void setData(final List<Item> items) {
        getView().setData(items);
    }

    public interface MenuView extends View, Focus, HasUiHandlers<MenuUiHandlers> {

        void ensureItemSelected(Item parentItem);

        void setData(List<Item> items);

        void selectFirstItem();
    }
}
