/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.pipeline;

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentResourceHelper;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.util.shared.EntityServiceException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

@AutoLogged
class ShapeshifterResourceImpl implements ShapeshifterResource {

    private final Provider<ShapeshifterStore> storeProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;

    @Inject
    ShapeshifterResourceImpl(final Provider<ShapeshifterStore> storeProvider,
                             final Provider<DocumentResourceHelper> documentResourceHelperProvider) {
        this.storeProvider = storeProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
    }

    @Override
    public ShapeshifterDoc fetch(final String uuid) {
        return documentResourceHelperProvider.get().read(storeProvider.get(), ShapeshifterDoc.getDocRef(uuid));
    }

    @Override
    public ShapeshifterDoc update(final String uuid, final ShapeshifterDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new EntityServiceException("The document UUID must match the update UUID");
        }
        return documentResourceHelperProvider.get().update(storeProvider.get(), doc);
    }

    @Override
    public ShapeshifterDoc create(final String name) {
        final ShapeshifterStore store = storeProvider.get();
        final DocRef docRef = store.createDocument(name);
        return store.readDocument(docRef);
    }
}
