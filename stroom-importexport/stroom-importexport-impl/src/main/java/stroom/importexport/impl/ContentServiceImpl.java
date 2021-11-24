/*
 * Copyright 2017-2021 Crown Copyright
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

package stroom.importexport.impl;

import stroom.explorer.shared.ExplorerConstants;
import stroom.importexport.api.ContentService;
import stroom.importexport.shared.Dependency;
import stroom.importexport.shared.DependencyCriteria;
import stroom.importexport.shared.ImportState;
import stroom.resource.api.ResourceStore;
import stroom.security.api.SecurityContext;
import stroom.security.shared.PermissionNames;
import stroom.util.shared.DocRefs;
import stroom.util.shared.Message;
import stroom.util.shared.PermissionException;
import stroom.util.shared.QuickFilterResultPage;
import stroom.util.shared.ResourceGeneration;
import stroom.util.shared.ResourceKey;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
class ContentServiceImpl implements ContentService {

    private final ImportExportService importExportService;
    private final ResourceStore resourceStore;
    private final DependencyService dependencyService;
    private final SecurityContext securityContext;
    private final ExportConfig exportConfig;

    @Inject
    ContentServiceImpl(final ImportExportService importExportService,
                       final ExportConfig exportConfig,
                       final ResourceStore resourceStore,
                       final DependencyService dependencyService,
                       final SecurityContext securityContext) {
        this.importExportService = importExportService;
        this.resourceStore = resourceStore;
        this.dependencyService = dependencyService;
        this.securityContext = securityContext;
        this.exportConfig = exportConfig;
    }

    @Override
    public ResourceKey performImport(final ResourceKey resourceKey, final List<ImportState> confirmList) {
        return securityContext.secureResult(PermissionNames.IMPORT_CONFIGURATION, () -> {
            // Import file.
            final Path file = resourceStore.getTempFile(resourceKey);

            boolean foundOneAction = false;
            for (final ImportState importState : confirmList) {
                if (importState.isAction()) {
                    foundOneAction = true;
                    break;
                }
            }
            if (!foundOneAction) {
                return resourceKey;
            }

            importExportService.performImportWithConfirmation(file, confirmList);

            // Delete the import if it was successful
            resourceStore.deleteTempFile(resourceKey);

            return resourceKey;
        });
    }

    @Override
    public List<ImportState> confirmImport(final ResourceKey resourceKey) {
        return securityContext.secureResult(PermissionNames.IMPORT_CONFIGURATION, () -> {
            try {
                final Path tempPath = resourceStore.getTempFile(resourceKey);
                return importExportService.createImportConfirmationList(tempPath);
            } catch (final RuntimeException rex) {
                // In case of error delete the temp file
                resourceStore.deleteTempFile(resourceKey);
                throw rex;
            }
        });
    }

    @Override
    public ResourceGeneration exportContent(final DocRefs docRefs) {
        Objects.requireNonNull(docRefs);

        return securityContext.secureResult(PermissionNames.EXPORT_CONFIGURATION, () -> {
            final List<Message> messageList = new ArrayList<>();
            ResourceStore resourceStore = this.resourceStore;
            final ResourceKey guiKey = resourceStore.createTempFile("StroomConfig.zip");
            final Path file = resourceStore.getTempFile(guiKey);
            importExportService.exportConfig(docRefs.getDocRefs(), file, messageList);

            return new ResourceGeneration(guiKey, messageList);
        });
    }

    @Override
    public QuickFilterResultPage<Dependency> fetchDependencies(final DependencyCriteria criteria) {
        return securityContext.secureResult(() -> dependencyService.getDependencies(criteria));
    }

    @Override
    public ResourceKey exportAll() {
        if (!securityContext.hasAppPermission("Export Configuration")) {
            throw new PermissionException(securityContext.getUserId(),
                    "You do not have permission to export all config");
        }
        if (!exportConfig.isEnabled()) {
            throw new PermissionException(securityContext.getUserId(), "Export is not enabled");
        }

        final ResourceKey tempResourceKey = resourceStore.createTempFile("StroomConfig.zip");

        final Path tempFile = resourceStore.getTempFile(tempResourceKey);
        importExportService.exportConfig(Set.of(ExplorerConstants.ROOT_DOC_REF), tempFile, new ArrayList<>());

        return tempResourceKey;
    }


}
