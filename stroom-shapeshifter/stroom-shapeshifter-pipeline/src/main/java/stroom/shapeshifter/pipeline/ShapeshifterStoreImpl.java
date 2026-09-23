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
import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.StoreFactory;
import stroom.importexport.api.ImportExportDocument;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.util.shared.Message;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

@Singleton
class ShapeshifterStoreImpl
        extends AbstractDocumentStore<ShapeshifterDoc>
        implements ShapeshifterStore {

    @Inject
    ShapeshifterStoreImpl(final StoreFactory storeFactory,
                          final SecurityContext securityContext,
                          final ShapeshifterSerialiser serialiser) {
        super(storeFactory,
                securityContext,
                serialiser,
                ShapeshifterDoc.TYPE,
                ShapeshifterDoc::builder,
                ShapeshifterDoc::copy);
    }

    /**
     * The sample reference never leaves the environment (design 44 §5j). It is a pointer to a
     * stream on this installation: somewhere else the same id is a different stream, or none, and
     * an export that silently pointed at unrelated data would be worse than one that pointed at
     * nothing. Stripped at the seam {@code omitAuditFields} already uses, which is also the seam
     * the git repository export goes through.
     */
    @Override
    public ImportExportDocument exportDocument(final DocRef docRef,
                                               final boolean omitAuditFields,
                                               final List<Message> messageList) {
        checkDocumentPermission(docRef, DocumentPermission.VIEW);
        return getStore().exportDocument(docRef, omitAuditFields, messageList,
                doc -> doc.copy().sample(null).build());
    }
}
