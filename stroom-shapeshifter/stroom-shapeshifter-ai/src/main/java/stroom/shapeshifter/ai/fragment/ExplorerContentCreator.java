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

package stroom.shapeshifter.ai.fragment;

import stroom.docref.DocRef;
import stroom.explorer.api.ExplorerService;
import stroom.explorer.shared.ExplorerNode;
import stroom.explorer.shared.PermissionInheritance;
import stroom.util.shared.DocPath;

import jakarta.inject.Inject;

/**
 * Creates learned content through the explorer, so it has a node in the tree and inherits the folder's
 * permissions. The folder path is ensured on every call; the explorer makes that cheap when it exists.
 */
public class ExplorerContentCreator implements ContentCreator {

    private final ExplorerService explorerService;

    @Inject
    ExplorerContentCreator(final ExplorerService explorerService) {
        this.explorerService = explorerService;
    }

    @Override
    public DocRef create(final DocPath folder, final String type, final String name) {
        final ExplorerNode folderNode = explorerService.ensureFolderPath(folder, PermissionInheritance.DESTINATION);
        return explorerService.create(type, name, folderNode, PermissionInheritance.DESTINATION).getDocRef();
    }
}
