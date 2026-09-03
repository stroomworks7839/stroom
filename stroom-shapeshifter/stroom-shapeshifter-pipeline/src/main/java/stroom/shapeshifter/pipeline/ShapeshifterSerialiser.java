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

import stroom.docstore.api.DocumentSerialiser2;
import stroom.docstore.api.Serialiser2;
import stroom.docstore.api.Serialiser2Factory;
import stroom.docstore.shared.DocDataType;
import stroom.importexport.api.ByteArrayImportExportAsset;
import stroom.importexport.api.ImportExportDocument;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.util.string.EncodingUtil;

import jakarta.inject.Inject;

import java.io.IOException;

/**
 * The project JSON travels as its own asset beside the document's metadata, the way a
 * TextConverter's XML does, so an export is a readable {@code .json} file and an import round-trips
 * the bytes untouched.
 */
public class ShapeshifterSerialiser implements DocumentSerialiser2<ShapeshifterDoc> {

    private static final String JSON = "json";

    private final Serialiser2<ShapeshifterDoc> delegate;

    @Inject
    public ShapeshifterSerialiser(final Serialiser2Factory serialiser2Factory) {
        this.delegate = serialiser2Factory.createSerialiser(ShapeshifterDoc.class);
    }

    @Override
    public ShapeshifterDoc read(final ImportExportDocument importExportDocument) throws IOException {
        return delegate.read(importExportDocument)
                .copy()
                .data(EncodingUtil.asString(importExportDocument.getExtAssetData(JSON)))
                .build();
    }

    @Override
    public ImportExportDocument write(final ShapeshifterDoc document) throws IOException {
        final String json = document.getData();
        final ImportExportDocument importExportDocument = delegate.write(document.copy().data(null).build());
        if (json != null) {
            importExportDocument.addExtAsset(
                    new ByteArrayImportExportAsset(JSON, DocDataType.JSON, EncodingUtil.asBytes(json)));
        }
        return importExportDocument;
    }
}
