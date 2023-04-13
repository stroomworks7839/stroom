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
 */

package stroom.alert.rule.impl;

import stroom.alert.rule.shared.AlertRuleDoc;
import stroom.alert.rule.shared.AlertRuleResource;
import stroom.docref.DocRef;
import stroom.docstore.api.DocumentResourceHelper;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.util.shared.EntityServiceException;

import javax.inject.Inject;
import javax.inject.Provider;

@AutoLogged
class AlertRuleResourceImpl implements AlertRuleResource {

    private final Provider<AlertRuleStore> alertRuleStoreProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;

    @Inject
    AlertRuleResourceImpl(final Provider<AlertRuleStore> alertRuleStoreProvider,
                          final Provider<DocumentResourceHelper> documentResourceHelperProvider) {
        this.alertRuleStoreProvider = alertRuleStoreProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
    }

    @Override
    public AlertRuleDoc fetch(final String uuid) {
        return documentResourceHelperProvider.get().read(alertRuleStoreProvider.get(), getDocRef(uuid));
    }

    @Override
    public AlertRuleDoc update(final String uuid, final AlertRuleDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new EntityServiceException("The document UUID must match the update UUID");
        }
        return documentResourceHelperProvider.get().update(alertRuleStoreProvider.get(), doc);
    }

    private DocRef getDocRef(final String uuid) {
        return DocRef.builder()
                .uuid(uuid)
                .type(AlertRuleDoc.DOCUMENT_TYPE)
                .build();
    }
}
