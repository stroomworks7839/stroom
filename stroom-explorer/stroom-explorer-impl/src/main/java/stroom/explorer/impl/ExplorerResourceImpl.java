/*
 * Copyright 2024 Crown Copyright
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

package stroom.explorer.impl;

import stroom.docref.DocContentHighlights;
import stroom.docref.DocRef;
import stroom.docref.DocRefInfo;
import stroom.docrefinfo.api.DocRefInfoService;
import stroom.docstore.api.DocumentNotFoundException;
import stroom.docstore.shared.DocumentType;
import stroom.event.logging.api.StroomEventLoggingService;
import stroom.event.logging.api.StroomEventLoggingUtil;
import stroom.event.logging.api.ThreadLocalLogState;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.event.logging.rs.api.AutoLogged.OperationType;
import stroom.explorer.api.ExplorerNodePermissionsService;
import stroom.explorer.api.ExplorerNodeService;
import stroom.explorer.api.ExplorerService;
import stroom.explorer.shared.AddRemoveTagsRequest;
import stroom.explorer.shared.AdvancedDocumentFindRequest;
import stroom.explorer.shared.AdvancedDocumentFindWithPermissionsRequest;
import stroom.explorer.shared.BulkActionResult;
import stroom.explorer.shared.DecorateRequest;
import stroom.explorer.shared.DocumentFindRequest;
import stroom.explorer.shared.DocumentTypes;
import stroom.explorer.shared.ExplorerConstants;
import stroom.explorer.shared.ExplorerNode;
import stroom.explorer.shared.ExplorerNodeInfo;
import stroom.explorer.shared.ExplorerNodePermissions;
import stroom.explorer.shared.ExplorerResource;
import stroom.explorer.shared.ExplorerServiceCopyRequest;
import stroom.explorer.shared.ExplorerServiceCreateRequest;
import stroom.explorer.shared.ExplorerServiceDeleteRequest;
import stroom.explorer.shared.ExplorerServiceMoveRequest;
import stroom.explorer.shared.ExplorerServiceRenameRequest;
import stroom.explorer.shared.ExplorerTreeFilter;
import stroom.explorer.shared.FetchExplorerNodeResult;
import stroom.explorer.shared.FetchExplorerNodesRequest;
import stroom.explorer.shared.FetchHighlightsRequest;
import stroom.explorer.shared.FindInContentRequest;
import stroom.explorer.shared.FindInContentResult;
import stroom.explorer.shared.FindResult;
import stroom.explorer.shared.FindResultWithPermissions;
import stroom.security.api.DocumentPermissionService;
import stroom.security.shared.AbstractDocumentPermissionsChange;
import stroom.security.shared.AbstractDocumentPermissionsChange.AddAllDocumentUserCreatePermissions;
import stroom.security.shared.AbstractDocumentPermissionsChange.AddAllPermissionsFrom;
import stroom.security.shared.AbstractDocumentPermissionsChange.AddDocumentUserCreatePermission;
import stroom.security.shared.AbstractDocumentPermissionsChange.RemoveAllDocumentUserCreatePermissions;
import stroom.security.shared.AbstractDocumentPermissionsChange.RemoveAllPermissions;
import stroom.security.shared.AbstractDocumentPermissionsChange.RemoveDocumentUserCreatePermission;
import stroom.security.shared.AbstractDocumentPermissionsChange.RemovePermission;
import stroom.security.shared.AbstractDocumentPermissionsChange.SetAllPermissionsFrom;
import stroom.security.shared.AbstractDocumentPermissionsChange.SetDocumentUserCreatePermissions;
import stroom.security.shared.AbstractDocumentPermissionsChange.SetPermission;
import stroom.security.shared.BulkDocumentPermissionChangeRequest;
import stroom.security.shared.DocumentPermission;
import stroom.security.shared.SingleDocumentPermissionChangeRequest;
import stroom.util.NullSafe;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.PageRequest;
import stroom.util.shared.PermissionException;
import stroom.util.shared.ResultPage;
import stroom.util.shared.UserRef;

import com.google.common.base.Strings;
import event.logging.AddGroups;
import event.logging.AdvancedQuery;
import event.logging.AuthorisationActionType;
import event.logging.AuthoriseEventAction;
import event.logging.ComplexLoggedOutcome;
import event.logging.Group;
import event.logging.Permission;
import event.logging.PermissionAttribute;
import event.logging.Permissions;
import event.logging.Query;
import event.logging.RemoveGroups;
import event.logging.SearchEventAction;
import event.logging.Term;
import event.logging.TermCondition;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@AutoLogged(OperationType.MANUALLY_LOGGED)
class ExplorerResourceImpl implements ExplorerResource {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ExplorerResourceImpl.class);

    private final Provider<ExplorerService> explorerServiceProvider;
    private final Provider<ExplorerNodeService> explorerNodeServiceProvider;
    private final Provider<DocRefInfoService> docRefInfoServiceProvider;
    private final Provider<ExplorerNodePermissionsService> explorerNodePermissionsServiceProvider;
    private final Provider<StroomEventLoggingService> stroomEventLoggingServiceProvider;
    private final Provider<DocumentPermissionService> documentPermissionServiceProvider;

    @Inject
    ExplorerResourceImpl(final Provider<ExplorerService> explorerServiceProvider,
                         final Provider<ExplorerNodeService> explorerNodeServiceProvider,
                         final Provider<DocRefInfoService> docRefInfoServiceProvider,
                         final Provider<ExplorerNodePermissionsService> explorerNodePermissionsServiceProvider,
                         final Provider<StroomEventLoggingService> stroomEventLoggingServiceProvider,
                         final Provider<DocumentPermissionService> documentPermissionServiceProvider) {
        this.explorerServiceProvider = explorerServiceProvider;
        this.explorerNodeServiceProvider = explorerNodeServiceProvider;
        this.docRefInfoServiceProvider = docRefInfoServiceProvider;
        this.explorerNodePermissionsServiceProvider = explorerNodePermissionsServiceProvider;
        this.stroomEventLoggingServiceProvider = stroomEventLoggingServiceProvider;
        this.documentPermissionServiceProvider = documentPermissionServiceProvider;
    }

    @Override
    public ExplorerNode create(final ExplorerServiceCreateRequest request) {
        return explorerServiceProvider.get().create(
                request.getDocType(),
                request.getDocName(),
                request.getDestinationFolder(),
                request.getPermissionInheritance());
    }

    @Override
    public BulkActionResult delete(final ExplorerServiceDeleteRequest request) {
        final List<ExplorerNode> explorerNodes = request.getDocRefs()
                .stream()
                .map(docRef -> Objects.requireNonNull(
                        getFromDocRef(docRef),
                        () -> "No explorer node found for " + docRef))
                .toList();
        return explorerServiceProvider.get()
                .delete(explorerNodes);
    }

    @Override
    public BulkActionResult copy(final ExplorerServiceCopyRequest request) {
        return explorerServiceProvider.get().copy(
                request.getExplorerNodes(),
                request.getDestinationFolder(),
                request.isAllowRename(),
                request.getDocName(),
                request.getPermissionInheritance());
    }

    @Override
    public BulkActionResult move(final ExplorerServiceMoveRequest request) {
        return explorerServiceProvider.get().move(request.getExplorerNodes(),
                request.getDestinationFolder(),
                request.getPermissionInheritance());
    }

    @Override
    public ExplorerNode rename(final ExplorerServiceRenameRequest request) {
        return explorerServiceProvider.get().rename(request.getExplorerNode(), request.getDocName());
    }

    @Override
    public ExplorerNode updateNodeTags(final ExplorerNode explorerNode) {
        return explorerServiceProvider.get().updateTags(explorerNode);
    }

    @Override
    public void addTags(final AddRemoveTagsRequest request) {
        Objects.requireNonNull(request);
        explorerServiceProvider.get().addTags(request.getDocRefs(), request.getTags());
    }

    @Override
    public void removeTags(final AddRemoveTagsRequest request) {
        Objects.requireNonNull(request);
        explorerServiceProvider.get().removeTags(request.getDocRefs(), request.getTags());
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public ExplorerNodeInfo info(final DocRef docRef) {
        final DocRefInfo docRefInfo = docRefInfoServiceProvider.get()
                .info(docRef)
                .orElse(null);

        if (docRefInfo == null) {
            return null;
        } else {
            final ExplorerNode explorerNode = explorerServiceProvider.get().getFromDocRef(docRef)
                    .orElseThrow(() -> new RuntimeException("No explorerNode for " + docRef));
            return new ExplorerNodeInfo(explorerNode, docRefInfo);
        }
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public DocRef decorate(final DecorateRequest decorateRequest) {

        Objects.requireNonNull(decorateRequest);
        try {
            return NullSafe.get(decorateRequest,
                    req -> docRefInfoServiceProvider.get()
                            .decorate(req.getDocRef(),
                                    true,
                                    req.getRequiredPermissions()));
        } catch (DocumentNotFoundException | PermissionException e) {
            LOGGER.debug("docRef not found - {}", decorateRequest, e);
            return null;
        } catch (Exception e) {
            // Something unexpected
            LOGGER.error("Error decorating docRef - {}", decorateRequest, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public ExplorerNode getFromDocRef(final DocRef docRef) {
        return explorerServiceProvider.get().getFromDocRef(docRef).orElse(null);
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public Set<DocRef> fetchDocRefs(final Set<DocRef> docRefs) {
        final Set<DocRef> result = new HashSet<>();
        if (docRefs != null) {
            for (final DocRef docRef : docRefs) {
                explorerNodeServiceProvider.get().getNode(docRef)
                        .map(ExplorerNode::getDocRef)
                        .ifPresent(result::add);
            }
        }

        return result;
    }

    @Override
    @AutoLogged(OperationType.UNLOGGED)
    public DocumentTypes fetchDocumentTypes() {
        final ExplorerService explorerService = explorerServiceProvider.get();
        final List<DocumentType> types = explorerService.getTypes();
        final List<DocumentType> visibleTypes = explorerService.getVisibleTypes();
        return new DocumentTypes(types, visibleTypes);
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public Set<String> fetchExplorerNodeTags() {
        return explorerServiceProvider.get().getTags();
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public Set<String> fetchExplorerNodeTags(final List<DocRef> docRefs) {
        return explorerServiceProvider.get().getTags(docRefs, TagFetchMode.OR);
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public Set<ExplorerNodePermissions> fetchExplorerPermissions(final List<ExplorerNode> explorerNodes) {
        return explorerNodePermissionsServiceProvider.get().fetchExplorerPermissions(explorerNodes);
    }

    @Override
    @AutoLogged(value = OperationType.MANUALLY_LOGGED)
    public FetchExplorerNodeResult fetchExplorerNodes(final FetchExplorerNodesRequest request) {
        final boolean loggingRequired = !Strings.isNullOrEmpty(request.getFilter().getNameFilter());
        ThreadLocalLogState.setLogged(!loggingRequired);

        return stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "fetchExplorerNodes"))
                .withDescription("Fetch explorer nodes using filter")
                .withDefaultEventAction(SearchEventAction.builder()
                        .withQuery(buildRawQuery(request.getFilter()))
                        .build())
                .withComplexLoggedResult(searchEventAction -> {
                    // Do the work
                    final FetchExplorerNodeResult result = explorerServiceProvider.get().getData(request);

                    final ExplorerTreeFilter requestFilter = request.getFilter();
                    final ExplorerTreeFilter qualifiedFilter = requestFilter.withNameFilter(
                            result.getQualifiedFilterInput());

                    // Ignore the previous searchEventAction as it didn't have anything useful on it
                    final SearchEventAction newSearchEventAction = SearchEventAction.builder()
                            .withQuery(buildRawQuery(qualifiedFilter))
                            .build();

                    return ComplexLoggedOutcome.success(result, newSearchEventAction);
                })
                .withLoggingRequiredWhen(loggingRequired)
                .getResultAndLog();
    }

    private Query buildRawQuery(final ExplorerTreeFilter explorerTreeFilter) {
        final String rawQuery = LogUtil.message("Node matches: \"{}\", included types: {}, tags: {}",
                explorerTreeFilter.getNameFilter(),
                Objects.requireNonNullElse(explorerTreeFilter.getIncludedTypes(), "[]"),
                Objects.requireNonNullElse(explorerTreeFilter.getTags(), "[]"));

        return Query.builder()
                .withRaw(rawQuery)
                .build();
    }

    @Override
    @AutoLogged(value = OperationType.MANUALLY_LOGGED)
    public ResultPage<FindResult> find(final DocumentFindRequest request) {
        final boolean loggingRequired = !Strings.isNullOrEmpty(request.getFilter().getNameFilter());
        ThreadLocalLogState.setLogged(!loggingRequired);

        final Query query = buildRawQuery(request.getFilter());
        final SearchEventAction action = SearchEventAction.builder().withQuery(query).build();
        return stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "find"))
                .withDescription("Find documents using filter")
                .withDefaultEventAction(SearchEventAction.builder().withQuery(query).build())
                .withComplexLoggedResult(searchEventAction -> {
                    // Do the work
                    final ResultPage<FindResult> result = explorerServiceProvider.get().find(request);

                    return ComplexLoggedOutcome.success(result, action);
                })
                .withLoggingRequiredWhen(loggingRequired)
                .getResultAndLog();
    }

    @Override
    public ResultPage<FindResult> advancedFind(final AdvancedDocumentFindRequest request) {
        final Query query = StroomEventLoggingUtil.convertExpression(request.getExpression());
        final SearchEventAction action = SearchEventAction.builder().withQuery(query).build();
        return stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "advancedFind"))
                .withDescription("Advanced find documents using filter")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    // Do the work
                    final ResultPage<FindResult> result = explorerServiceProvider.get().advancedFind(request);
                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();

//        final Query query = StroomEventLoggingUtil.convertExpression(request.getExpression());
//        final SearchEventAction action = SearchEventAction.builder().withQuery(query).build();
//        final Consumer<ComplexLoggedOutcome> consumer = complexLoggedOutcome -> {
//            stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
//                    .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "advancedFind"))
//                    .withDescription("Advanced find documents using filter")
//                    .withDefaultEventAction(action)
//                    .withComplexLoggedResult(searchEventAction -> {
//                        return complexLoggedOutcome;
//                    })
//                    .getResultAndLog();
//        };
//
//        try {
//            final ResultPage<FindResult> result = explorerServiceProvider.get().advancedFind(request);
//            consumer.accept(ComplexLoggedOutcome.success(result, action));
//            return result;
//        } catch (final RuntimeException e) {
//            consumer.accept(ComplexLoggedOutcome.failure(action, e.getMessage()));
//            throw e;
//        }
    }

    @Override
    public ResultPage<FindResultWithPermissions> advancedFindWithPermissions(
            final AdvancedDocumentFindWithPermissionsRequest request) {
        final Query query = StroomEventLoggingUtil.convertExpression(request.getExpression());
        final SearchEventAction action = SearchEventAction.builder().withQuery(query).build();
        return stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "advancedFindWithPermissions"))
                .withDescription("Advanced find documents plus permissions using filter")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    // Do the work
                    final ResultPage<FindResultWithPermissions> result =
                            explorerServiceProvider.get().advancedFindWithPermissions(request);

                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();
    }

    @Override
    public ResultPage<FindInContentResult> findInContent(final FindInContentRequest request) {
        final Query query = Query.builder().withRaw(request.getFilter().getPattern()).build();
        final SearchEventAction action = SearchEventAction.builder().withQuery(query).build();
        return stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "findInContent"))
                .withDescription("Find documents with requested content")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    // Do the work
                    final ResultPage<FindInContentResult> result =
                            explorerServiceProvider.get().findInContent(request);

                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();
    }

    @Override
    public DocContentHighlights fetchHighlights(final FetchHighlightsRequest request) {
        final Query query = Query.builder().withAdvanced(AdvancedQuery.builder()
                        .addTerm(Term
                                .builder()
                                .withName("docType")
                                .withCondition(TermCondition.EQUALS)
                                .withValue(request.getDocRef().getType())
                                .build())
                        .addTerm(Term
                                .builder()
                                .withName("docUuid")
                                .withCondition(TermCondition.EQUALS)
                                .withValue(request.getDocRef().getUuid())
                                .build())
                        .addTerm(Term
                                .builder()
                                .withName("docName")
                                .withCondition(TermCondition.EQUALS)
                                .withValue(request.getDocRef().getName())
                                .build())
                        .addTerm(Term
                                .builder()
                                .withName("text")
                                .withCondition(TermCondition.CONTAINS)
                                .withValue(request.getFilter().getPattern())
                                .build())
                        .build())
                .build();

        final SearchEventAction action = SearchEventAction.builder().withQuery(query).build();
        return stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "fetchHighlights"))
                .withDescription("Fetch highlights in content")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    // Do the work
                    final DocContentHighlights result =
                            explorerServiceProvider.get().fetchHighlights(request);

                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();
    }

    @Override
    public Boolean changeDocumentPermissions(final BulkDocumentPermissionChangeRequest request) {
        final AdvancedDocumentFindRequest advancedDocumentFindRequest = new AdvancedDocumentFindRequest.Builder()
                .requiredPermissions(Set.of(DocumentPermission.OWNER))
                .expression(request.getExpression())
                .pageRequest(PageRequest.unlimited())
                .build();
        final ResultPage<FindResult> resultPage =
                explorerServiceProvider.get().advancedFind(advancedDocumentFindRequest);
        for (final FindResult findResult : resultPage.getValues()) {
            final SingleDocumentPermissionChangeRequest singleDocumentPermissionChangeRequest =
                    new SingleDocumentPermissionChangeRequest(findResult.getDocRef(), request.getChange());
            changeDocumentPermissions(singleDocumentPermissionChangeRequest);
        }
        return true;
    }

    private PermissionAttribute getPermissionAttribute(final DocumentPermission permission) {
        if (permission == null) {
            return null;
        }
        return switch (permission) {
            case USE -> PermissionAttribute.READ;
            case VIEW -> PermissionAttribute.READ;
            case EDIT -> PermissionAttribute.WRITE;
            case DELETE -> PermissionAttribute.WRITE;
            case OWNER -> PermissionAttribute.OWNER;
        };
    }

    private Group createGroup(final UserRef userRef,
                              final DocumentPermission documentPermission) {
        final PermissionAttribute permissionAttribute = getPermissionAttribute(documentPermission);
        final Permission permission = Permission
                .builder()
                .withAllowAttributes(permissionAttribute)
                .withUser(StroomEventLoggingUtil.createUser(userRef))
                .build();
        return Group
                .builder()
                .withPermissions(Permissions.builder().addPermissions(permission).build())
                .build();
    }

    private void changeDocumentPermissions(final SingleDocumentPermissionChangeRequest request) {
        final AbstractDocumentPermissionsChange change = request.getChange();

        if (change instanceof final SetPermission setPermission) {
            setPermission(request, setPermission);

        } else if (change instanceof final RemovePermission removePermission) {
            removePermission(request, removePermission);

        } else if (change instanceof final AddDocumentUserCreatePermission addDocumentUserCreatePermission) {
            addDocumentUserCreatePermission(request, addDocumentUserCreatePermission);

        } else if (change instanceof final RemoveDocumentUserCreatePermission removeDocumentUserCreatePermission) {
            removeDocumentUserCreatePermission(request, removeDocumentUserCreatePermission);

        } else if (change instanceof final SetDocumentUserCreatePermissions setDocumentUserCreatePermissions) {
            setDocumentUserCreatePermissions(request, setDocumentUserCreatePermissions);

        } else if (change instanceof final AddAllDocumentUserCreatePermissions addAllDocumentUserCreatePermissions) {
            addAllDocumentUserCreatePermissions(request, addAllDocumentUserCreatePermissions);

        } else if (change instanceof final RemoveAllDocumentUserCreatePermissions
                removeAllDocumentUserCreatePermissions) {
            removeAllDocumentUserCreatePermissions(request, removeAllDocumentUserCreatePermissions);

        } else if (change instanceof final AddAllPermissionsFrom addAllPermissionsFrom) {
            addAllPermissionsFrom(request, addAllPermissionsFrom);

        } else if (change instanceof final SetAllPermissionsFrom setAllPermissionsFrom) {
            setAllPermissionsFrom(request, setAllPermissionsFrom);

        } else if (change instanceof final RemoveAllPermissions removeAllPermissions) {
            removeAllPermissions(request, removeAllPermissions);

        } else {
            throw new RuntimeException("Unexpected change " + change.getClass().getName());
        }
    }

    private void setPermission(final SingleDocumentPermissionChangeRequest request,
                               final SetPermission setPermission) {
        final Group group = createGroup(setPermission.getUserRef(), setPermission.getPermission());
        final AuthoriseEventAction action = AuthoriseEventAction
                .builder()
                .withAction(AuthorisationActionType.MODIFY)
                .addObject(StroomEventLoggingUtil.createOtherObject(request.getDocRef()))
                .withAddGroups(AddGroups.builder().addGroups(group).build())
                .build();
        stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "changeDocumentPermissions"))
                .withDescription("Set document permission")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    final DocumentPermissionService documentPermissionService =
                            documentPermissionServiceProvider.get();
                    final Boolean result = documentPermissionService.changeDocumentPermissions(request);
                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();
    }

    private void removePermission(final SingleDocumentPermissionChangeRequest request,
                                  final RemovePermission removePermission) {
        final Group group = createGroup(removePermission.getUserRef(), null);
        final AuthoriseEventAction action;
        action = AuthoriseEventAction
                .builder()
                .withAction(AuthorisationActionType.MODIFY)
                .addObject(StroomEventLoggingUtil.createOtherObject(request.getDocRef()))
                .withAddGroups(AddGroups.builder().addGroups(group).build())
                .build();
        stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "changeDocumentPermissions"))
                .withDescription("Remove document permission")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    final DocumentPermissionService documentPermissionService =
                            documentPermissionServiceProvider.get();
                    final Boolean result = documentPermissionService.changeDocumentPermissions(request);
                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();
    }

    private void addDocumentUserCreatePermission(final SingleDocumentPermissionChangeRequest request,
                                                 final AddDocumentUserCreatePermission
                                                         addDocumentUserCreatePermission) {
        final Permission permission = Permission
                .builder()
                .withAllowAttributes(PermissionAttribute.AUTHOR)
                .withUser(StroomEventLoggingUtil.createUser(addDocumentUserCreatePermission.getUserRef()))
                .withGroup(Group
                        .builder()
                        .withType(addDocumentUserCreatePermission.getDocumentType())
                        .build())
                .build();
        final Group group = Group
                .builder()
                .withPermissions(Permissions.builder().addPermissions(permission).build())
                .build();
        final AuthoriseEventAction action = AuthoriseEventAction
                .builder()
                .withAction(AuthorisationActionType.MODIFY)
                .addObject(StroomEventLoggingUtil.createOtherObject(request.getDocRef()))
                .withAddGroups(AddGroups.builder().addGroups(group).build())
                .build();
        stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "changeDocumentPermissions"))
                .withDescription("Add document create permission for folder")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    final DocumentPermissionService documentPermissionService =
                            documentPermissionServiceProvider.get();
                    final Boolean result = documentPermissionService.changeDocumentPermissions(request);
                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();
    }

    private void removeDocumentUserCreatePermission(final SingleDocumentPermissionChangeRequest request,
                                                    final RemoveDocumentUserCreatePermission
                                                            removeDocumentUserCreatePermission) {
        final Permission permission = Permission
                .builder()
                .withAllowAttributes(PermissionAttribute.AUTHOR)
                .withUser(StroomEventLoggingUtil.createUser(removeDocumentUserCreatePermission.getUserRef()))
                .withGroup(Group
                        .builder()
                        .withType(removeDocumentUserCreatePermission.getDocumentType())
                        .build())
                .build();
        final Group group = Group
                .builder()
                .withPermissions(Permissions.builder().addPermissions(permission).build())
                .build();
        final AuthoriseEventAction action = AuthoriseEventAction
                .builder()
                .withAction(AuthorisationActionType.MODIFY)
                .addObject(StroomEventLoggingUtil.createOtherObject(request.getDocRef()))
                .withRemoveGroups(RemoveGroups.builder().addGroups(group).build())
                .build();
        stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "changeDocumentPermissions"))
                .withDescription("Remove document create permission for folder")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    final DocumentPermissionService documentPermissionService =
                            documentPermissionServiceProvider.get();
                    final Boolean result = documentPermissionService.changeDocumentPermissions(request);
                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();
    }

    private void setDocumentUserCreatePermissions(final SingleDocumentPermissionChangeRequest request,
                                                  final SetDocumentUserCreatePermissions
                                                          setDocumentUserCreatePermissions) {
        final Permission permission = Permission
                .builder()
                .withAllowAttributes(PermissionAttribute.AUTHOR)
                .withUser(StroomEventLoggingUtil.createUser(setDocumentUserCreatePermissions.getUserRef()))
                .withGroup(Group
                        .builder()
                        .withType(String.join(",", setDocumentUserCreatePermissions.getDocumentTypes()))
                        .build())
                .build();
        final Group group = Group
                .builder()
                .withPermissions(Permissions.builder().addPermissions(permission).build())
                .build();
        final AuthoriseEventAction action = AuthoriseEventAction
                .builder()
                .withAction(AuthorisationActionType.MODIFY)
                .addObject(StroomEventLoggingUtil.createOtherObject(request.getDocRef()))
                .withRemoveGroups(RemoveGroups.builder().addGroups(group).build())
                .build();
        stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "changeDocumentPermissions"))
                .withDescription("Remove document create permission for folder")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    final DocumentPermissionService documentPermissionService =
                            documentPermissionServiceProvider.get();
                    final Boolean result = documentPermissionService.changeDocumentPermissions(request);
                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();
    }

    private void addAllDocumentUserCreatePermissions(final SingleDocumentPermissionChangeRequest request,
                                                     final AddAllDocumentUserCreatePermissions
                                                             addAllDocumentUserCreatePermissions) {
        final Permission permission = Permission
                .builder()
                .withAllowAttributes(PermissionAttribute.AUTHOR)
                .withUser(StroomEventLoggingUtil.createUser(addAllDocumentUserCreatePermissions.getUserRef()))
                .withGroup(Group.builder().withType(ExplorerConstants.ALL_CREATE_PERMISSIONS).build())
                .build();
        final Group group = Group
                .builder()
                .withPermissions(Permissions.builder().addPermissions(permission).build())
                .build();
        final AuthoriseEventAction action = AuthoriseEventAction
                .builder()
                .withAction(AuthorisationActionType.MODIFY)
                .addObject(StroomEventLoggingUtil.createOtherObject(request.getDocRef()))
                .withAddGroups(AddGroups.builder().addGroups(group).build())
                .build();
        stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "changeDocumentPermissions"))
                .withDescription("Add all document create permissions for folder")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    final DocumentPermissionService documentPermissionService =
                            documentPermissionServiceProvider.get();
                    final Boolean result = documentPermissionService.changeDocumentPermissions(request);
                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();
    }

    private void removeAllDocumentUserCreatePermissions(final SingleDocumentPermissionChangeRequest request,
                                                        final RemoveAllDocumentUserCreatePermissions
                                                                removeAllDocumentUserCreatePermissions) {
        final Permission permission = Permission
                .builder()
                .withAllowAttributes(PermissionAttribute.AUTHOR)
                .withUser(StroomEventLoggingUtil.createUser(removeAllDocumentUserCreatePermissions.getUserRef()))
                .withGroup(Group.builder().withType(ExplorerConstants.ALL_CREATE_PERMISSIONS).build())
                .build();
        final Group group = Group
                .builder()
                .withPermissions(Permissions.builder().addPermissions(permission).build())
                .build();
        final AuthoriseEventAction action = AuthoriseEventAction
                .builder()
                .withAction(AuthorisationActionType.MODIFY)
                .addObject(StroomEventLoggingUtil.createOtherObject(request.getDocRef()))
                .withRemoveGroups(RemoveGroups.builder().addGroups(group).build())
                .build();
        stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "changeDocumentPermissions"))
                .withDescription("Remove all document create permissions for folder")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    final DocumentPermissionService documentPermissionService =
                            documentPermissionServiceProvider.get();
                    final Boolean result = documentPermissionService.changeDocumentPermissions(request);
                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();
    }

    private void addAllPermissionsFrom(final SingleDocumentPermissionChangeRequest request,
                                       final AddAllPermissionsFrom
                                               addAllPermissionsFrom) {
        final Group group = Group
                .builder()
                .withType(addAllPermissionsFrom.getSourceDocRef().getType())
                .withId(addAllPermissionsFrom.getSourceDocRef().getUuid())
                .withName(addAllPermissionsFrom.getSourceDocRef().getName())
                .build();
        final AuthoriseEventAction action = AuthoriseEventAction
                .builder()
                .withAction(AuthorisationActionType.MODIFY)
                .addObject(StroomEventLoggingUtil.createOtherObject(request.getDocRef()))
                .withAddGroups(AddGroups.builder().addGroups(group).build())
                .build();
        stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "changeDocumentPermissions"))
                .withDescription("Copy all document permissions from other document")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    final DocumentPermissionService documentPermissionService =
                            documentPermissionServiceProvider.get();
                    final Boolean result = documentPermissionService.changeDocumentPermissions(request);
                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();
    }

    private void setAllPermissionsFrom(final SingleDocumentPermissionChangeRequest request,
                                       final SetAllPermissionsFrom
                                               setAllPermissionsFrom) {
        final Group group = Group
                .builder()
                .withType(setAllPermissionsFrom.getSourceDocRef().getType())
                .withId(setAllPermissionsFrom.getSourceDocRef().getUuid())
                .withName(setAllPermissionsFrom.getSourceDocRef().getName())
                .build();
        final AuthoriseEventAction action = AuthoriseEventAction
                .builder()
                .withAction(AuthorisationActionType.MODIFY)
                .addObject(StroomEventLoggingUtil.createOtherObject(request.getDocRef()))
                .withAddGroups(AddGroups.builder().addGroups(group).build())
                .build();
        stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "changeDocumentPermissions"))
                .withDescription("Set all document permissions from other document")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    final DocumentPermissionService documentPermissionService =
                            documentPermissionServiceProvider.get();
                    final Boolean result = documentPermissionService.changeDocumentPermissions(request);
                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();
    }

    private void removeAllPermissions(final SingleDocumentPermissionChangeRequest request,
                                      final RemoveAllPermissions
                                              removeAllPermissions) {
        final AuthoriseEventAction action = AuthoriseEventAction
                .builder()
                .withAction(AuthorisationActionType.MODIFY)
                .addObject(StroomEventLoggingUtil.createOtherObject(request.getDocRef()))
                .build();
        stroomEventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "changeDocumentPermissions"))
                .withDescription("Remove all permissions from document")
                .withDefaultEventAction(action)
                .withComplexLoggedResult(searchEventAction -> {
                    final DocumentPermissionService documentPermissionService =
                            documentPermissionServiceProvider.get();
                    final Boolean result = documentPermissionService.changeDocumentPermissions(request);
                    return ComplexLoggedOutcome.success(result, action);
                })
                .getResultAndLog();
    }
}
