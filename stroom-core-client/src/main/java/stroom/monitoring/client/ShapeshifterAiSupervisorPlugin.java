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

package stroom.monitoring.client;

import stroom.core.client.ContentManager;
import stroom.core.client.MenuKeys;
import stroom.core.client.presenter.MonitoringPlugin;
import stroom.document.client.DocumentPluginRegistry;
import stroom.security.client.api.ClientSecurityContext;
import stroom.security.shared.AppPermission;
import stroom.shapeshifter.client.presenter.SupervisorPresenter;
import stroom.svg.client.IconColour;
import stroom.svg.shared.SvgImage;
import stroom.widget.menu.client.presenter.IconMenuItem;
import stroom.widget.util.client.KeyBinding.Action;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import javax.inject.Singleton;

/// The Supervisor of ruling A28 in the menu: a screen of its own beside Jobs and the other monitoring
/// screens, not a tab on one Shapeshifter AI document, because what a person wants to see is every
/// attempt every document has made.
@Singleton
public class ShapeshifterAiSupervisorPlugin extends MonitoringPlugin<SupervisorPresenter> {

    @Inject
    public ShapeshifterAiSupervisorPlugin(final EventBus eventBus,
                                          final ContentManager contentManager,
                                          final Provider<SupervisorPresenter> presenterProvider,
                                          final ClientSecurityContext securityContext,
                                          final DocumentPluginRegistry documentPluginRegistry) {
        super(eventBus, contentManager, presenterProvider, securityContext, documentPluginRegistry);
    }

    @Override
    protected void addChildItems(final stroom.menubar.client.event.BeforeRevealMenubarEvent event) {
        if (getSecurityContext().hasAppPermission(getRequiredAppPermission())) {
            event.getMenuItems().addMenuItem(MenuKeys.MONITORING_MENU,
                    new IconMenuItem.Builder()
                            .priority(12)
                            .icon(SvgImage.AI)
                            .iconColour(IconColour.GREY)
                            .text("Shapeshifter AI Attempts")
                            .command(this::open)
                            .build());
        }
    }

    @Override
    protected AppPermission getRequiredAppPermission() {
        return AppPermission.VIEW_DATA_PERMISSION;
    }

    @Override
    protected Action getOpenAction() {
        return null;
    }

    @Override
    public String getType() {
        return SupervisorPresenter.TAB_TYPE;
    }
}
