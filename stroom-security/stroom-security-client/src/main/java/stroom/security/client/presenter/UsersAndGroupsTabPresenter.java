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

package stroom.security.client.presenter;

import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.Rest;
import stroom.dispatch.client.RestFactory;
import stroom.security.client.api.ClientSecurityContext;
import stroom.security.shared.FindUserCriteria;
import stroom.security.shared.PermissionNames;
import stroom.security.shared.User;
import stroom.security.shared.UserResource;
import stroom.svg.client.SvgPresets;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MouseUtil;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.shared.HasHandlers;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import javax.inject.Inject;

public class UsersAndGroupsTabPresenter extends
        MyPresenterWidget<UserListView> implements HasHandlers {

    private static final UserResource USER_RESOURCE = GWT.create(UserResource.class);

    public static final String LIST = "LIST";
    private final SelectGroupPresenter listPresenter;
    private final Provider<UserEditPresenter> userEditPresenterProvider;
    private final Provider<GroupEditPresenter> groupEditPresenterProvider;
    private final Provider<SelectUserPresenter> selectUserPresenterProvider;
    private final ManageNewEntityPresenter newPresenter;
    private final RestFactory restFactory;
    private final ButtonView newButton;
    private final ButtonView openButton;
    private final ButtonView deleteButton;
    private final FindUserCriteria criteria = new FindUserCriteria();

    @Inject
    public UsersAndGroupsTabPresenter(final EventBus eventBus,
                                      final SelectGroupPresenter listPresenter,
                                      final Provider<UserEditPresenter> userEditPresenterProvider,
                                      final Provider<GroupEditPresenter> groupEditPresenterProvider,
                                      final Provider<SelectUserPresenter> selectUserPresenterProvider,
                                      final ManageNewEntityPresenter newPresenter,
                                      final RestFactory restFactory,
                                      final ClientSecurityContext securityContext) {
        super(eventBus, listPresenter.getView());
        this.listPresenter = listPresenter;
        this.userEditPresenterProvider = userEditPresenterProvider;
        this.groupEditPresenterProvider = groupEditPresenterProvider;
        this.selectUserPresenterProvider = selectUserPresenterProvider;
        this.restFactory = restFactory;
        this.newPresenter = newPresenter;

        setInSlot(LIST, listPresenter);

        newButton = listPresenter.addButton(SvgPresets.NEW_ITEM);
        openButton = listPresenter.addButton(SvgPresets.EDIT);
        deleteButton = listPresenter.addButton(SvgPresets.DELETE);

        final boolean updatePerm = securityContext.hasAppPermission(PermissionNames.MANAGE_USERS_PERMISSION);

        if (!updatePerm) {
            deleteButton.setVisible(false);
        }
        if (!updatePerm) {
            newButton.setVisible(false);
        }

    }

    @Override
    protected void onBind() {
        registerHandler(listPresenter.getSelectionModel().addSelectionHandler(event -> {
            enableButtons();
            if (event.getSelectionType().isDoubleSelect()) {
                onOpen();
            }
        }));
        registerHandler(newButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onNew();
            }
        }));
        registerHandler(openButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onOpen();
            }
        }));
        registerHandler(deleteButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onDelete();
            }
        }));

        super.onBind();
    }

    public void setGroup(final boolean group) {
        criteria.setGroup(group);
        listPresenter.setup(criteria);
    }

    private void enableButtons() {
        final boolean enabled = listPresenter.getSelectionModel().getSelected() != null;
        openButton.setEnabled(enabled);
        deleteButton.setEnabled(enabled);
    }

    private void onOpen() {
        final User e = listPresenter.getSelectionModel().getSelected();
        onEdit(e);
    }

    public void onEdit(final User userRef) {
        if (userRef != null) {
            edit(userRef);
        }
    }

    private void onDelete() {
        final User userRef = listPresenter.getSelectionModel().getSelected();
        if (userRef != null) {
            ConfirmEvent.fire(UsersAndGroupsTabPresenter.this,
                    "Are you sure you want to delete the selected " + getTypeName() + "?",
                    ok -> {
                        if (ok) {
                            final Rest<Boolean> rest = restFactory.create();
                            rest
                                    .onSuccess(result -> {
                                        listPresenter.refresh();
                                        listPresenter.getSelectionModel().clear();
                                    })
                                    .call(USER_RESOURCE)
                                    .delete(userRef.getUuid());
                        }
                    });
        }
    }

    private void onNew() {
        if (criteria.isGroup()) {
            newPresenter.show(e -> {
                if (e.isOk()) {
                    final Rest<User> rest = restFactory.create();
                    rest
                            .onSuccess(result -> {
                                e.hide();
                                edit(result);
                            })
                            .call(USER_RESOURCE)
                            .create(newPresenter.getName(), criteria.isGroup());
                } else {
                    e.hide();
                }
            });

        } else {
            selectUserPresenterProvider.get().show(this::edit);
        }
    }

    private void edit(final User userRef) {
        if (userRef.isGroup()) {
            if (groupEditPresenterProvider != null) {
                final GroupEditPresenter groupEditPresenter = groupEditPresenterProvider.get();
                groupEditPresenter.show(userRef, listPresenter::refresh);
            }
        } else {
            if (userEditPresenterProvider != null) {
                final UserEditPresenter userEditPresenter = userEditPresenterProvider.get();
                userEditPresenter.show(userRef, listPresenter::refresh);
            }
        }
    }

    private String getTypeName() {
        if (criteria.isGroup()) {
            return "group";
        }

        return "user";
    }
}
