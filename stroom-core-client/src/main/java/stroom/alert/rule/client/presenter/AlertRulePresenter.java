/*
 * Copyright 2022 Crown Copyright
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
 *
 */

package stroom.alert.rule.client.presenter;

import stroom.alert.rule.shared.AlertRuleDoc;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.ContentCallback;
import stroom.entity.client.presenter.DocumentEditTabPresenter;
import stroom.entity.client.presenter.LinkTabPanelView;
import stroom.security.client.api.ClientSecurityContext;
import stroom.widget.tab.client.presenter.TabData;
import stroom.widget.tab.client.presenter.TabDataImpl;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

public class AlertRulePresenter extends DocumentEditTabPresenter<LinkTabPanelView, AlertRuleDoc> {

    private static final TabData SETTINGS_TAB = new TabDataImpl("Settings");
    private static final TabData PROCESSING_TAB = new TabDataImpl("Processing");
    private final AlertRuleSettingsPresenter settingsPresenter;
    private final AlertRuleProcessingPresenter processPresenter;

    @Inject
    public AlertRulePresenter(final EventBus eventBus,
                              final LinkTabPanelView view,
                              final AlertRuleSettingsPresenter settingsPresenter,
                              final AlertRuleProcessingPresenter processPresenter,
                              final ClientSecurityContext securityContext) {
        super(eventBus, view, securityContext);
        this.settingsPresenter = settingsPresenter;
        this.processPresenter = processPresenter;

        settingsPresenter.addDirtyHandler(event -> {
            if (event.isDirty()) {
                setDirty(true);
            }
        });
        processPresenter.addDirtyHandler(event -> {
            if (event.isDirty()) {
                setDirty(true);
            }
        });

        addTab(SETTINGS_TAB);
        addTab(PROCESSING_TAB);
        selectTab(SETTINGS_TAB);
    }

    @Override
    protected void getContent(final TabData tab, final ContentCallback callback) {
        if (SETTINGS_TAB.equals(tab)) {
            callback.onReady(settingsPresenter);
        } else if (PROCESSING_TAB.equals(tab)) {
            callback.onReady(processPresenter);
        } else {
            callback.onReady(null);
        }
    }

    @Override
    public void onRead(final DocRef docRef, final AlertRuleDoc entity) {
        super.onRead(docRef, entity);
        settingsPresenter.read(docRef, entity);
        processPresenter.read(docRef, entity);
    }

    @Override
    protected AlertRuleDoc onWrite(final AlertRuleDoc entity) {
        AlertRuleDoc modified = entity;
        modified = settingsPresenter.write(modified);
        modified = processPresenter.write(modified);
        return modified;
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        super.onReadOnly(readOnly);
        settingsPresenter.onReadOnly(readOnly);
        processPresenter.onReadOnly(readOnly);
    }

    @Override
    public String getType() {
        return AlertRuleDoc.DOCUMENT_TYPE;
    }
}
