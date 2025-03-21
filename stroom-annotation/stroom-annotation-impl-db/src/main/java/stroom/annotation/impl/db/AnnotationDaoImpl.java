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

package stroom.annotation.impl.db;

import stroom.annotation.impl.AnnotationConfig;
import stroom.annotation.impl.AnnotationDao;
import stroom.annotation.impl.db.jooq.tables.records.AnnotationRecord;
import stroom.annotation.shared.AbstractAnnotationChange;
import stroom.annotation.shared.AddTag;
import stroom.annotation.shared.Annotation;
import stroom.annotation.shared.AnnotationDecorationFields;
import stroom.annotation.shared.AnnotationEntry;
import stroom.annotation.shared.AnnotationEntryType;
import stroom.annotation.shared.AnnotationFields;
import stroom.annotation.shared.AnnotationTag;
import stroom.annotation.shared.AnnotationTagType;
import stroom.annotation.shared.ChangeAssignedTo;
import stroom.annotation.shared.ChangeComment;
import stroom.annotation.shared.ChangeDescription;
import stroom.annotation.shared.ChangeRetentionPeriod;
import stroom.annotation.shared.ChangeSubject;
import stroom.annotation.shared.ChangeTitle;
import stroom.annotation.shared.CreateAnnotationRequest;
import stroom.annotation.shared.EntryValue;
import stroom.annotation.shared.EventId;
import stroom.annotation.shared.LinkEvents;
import stroom.annotation.shared.RemoveTag;
import stroom.annotation.shared.SetTag;
import stroom.annotation.shared.SingleAnnotationChangeRequest;
import stroom.annotation.shared.StringEntryValue;
import stroom.annotation.shared.UnlinkEvents;
import stroom.annotation.shared.UserRefEntryValue;
import stroom.db.util.ExpressionMapper;
import stroom.db.util.ExpressionMapperFactory;
import stroom.db.util.JooqUtil;
import stroom.db.util.ValueMapper;
import stroom.db.util.ValueMapper.Mapper;
import stroom.docref.DocRef;
import stroom.entity.shared.ExpressionCriteria;
import stroom.query.api.v2.ExpressionOperator;
import stroom.query.common.v2.DateExpressionParser;
import stroom.query.language.functions.FieldIndex;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValDate;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValNull;
import stroom.query.language.functions.ValString;
import stroom.query.language.functions.ValuesConsumer;
import stroom.security.user.api.UserRefLookup;
import stroom.util.NullSafe;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.UserRef;
import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;
import stroom.util.time.SimpleDurationUtil;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.jooq.Condition;
import org.jooq.Cursor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SelectJoinStep;

import java.text.ParseException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static stroom.annotation.impl.db.jooq.tables.Annotation.ANNOTATION;
import static stroom.annotation.impl.db.jooq.tables.AnnotationDataLink.ANNOTATION_DATA_LINK;
import static stroom.annotation.impl.db.jooq.tables.AnnotationEntry.ANNOTATION_ENTRY;
import static stroom.annotation.impl.db.jooq.tables.AnnotationTag.ANNOTATION_TAG;
import static stroom.annotation.impl.db.jooq.tables.AnnotationTagLink.ANNOTATION_TAG_LINK;

// Make this a singleton so we don't keep recreating the mappers.
@Singleton
class AnnotationDaoImpl implements AnnotationDao {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(AnnotationDaoImpl.class);

    private static final int DATA_RETENTION_BATCH_SIZE = 1000;
    private static final stroom.annotation.impl.db.jooq.tables.AnnotationTag STATUS =
            ANNOTATION_TAG.as("status");
    private static final stroom.annotation.impl.db.jooq.tables.AnnotationTag LABEL =
            ANNOTATION_TAG.as("label");
    private static final stroom.annotation.impl.db.jooq.tables.AnnotationTag COLLECTION =
            ANNOTATION_TAG.as("collection");
    private static stroom.annotation.impl.db.jooq.tables.AnnotationEntry COMMENT = ANNOTATION_ENTRY.as("comment");
    private static stroom.annotation.impl.db.jooq.tables.AnnotationEntry HISTORY = ANNOTATION_ENTRY.as("history");


    private final AnnotationDbConnProvider connectionProvider;
    private final ExpressionMapper expressionMapper;
    private final ValueMapper valueMapper;
    private final UserRefLookup userRefLookup;
    private final Provider<AnnotationConfig> annotationConfigProvider;
    private final AnnotationTagDaoImpl annotationTagDao;

    @Inject
    AnnotationDaoImpl(final AnnotationDbConnProvider connectionProvider,
                      final ExpressionMapperFactory expressionMapperFactory,
                      final UserRefLookup userRefLookup,
                      final Provider<AnnotationConfig> annotationConfigProvider,
                      final AnnotationTagDaoImpl annotationTagDao) {
        this.connectionProvider = connectionProvider;
        this.userRefLookup = userRefLookup;
        this.expressionMapper = createExpressionMapper(expressionMapperFactory, userRefLookup);
        this.valueMapper = createValueMapper();
        this.annotationConfigProvider = annotationConfigProvider;
        this.annotationTagDao = annotationTagDao;
    }

    private ExpressionMapper createExpressionMapper(final ExpressionMapperFactory expressionMapperFactory,
                                                    final UserRefLookup userRefLookup) {
        final ExpressionMapper expressionMapper = expressionMapperFactory.create();

        // Decoration
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_ID_FIELD, ANNOTATION.ID, Long::valueOf);
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_UUID_FIELD, ANNOTATION.UUID, value -> value);
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_CREATED_ON_FIELD,
                ANNOTATION.CREATE_TIME_MS,
                value -> DateExpressionParser.getMs(AnnotationDecorationFields.ANNOTATION_CREATED_ON, value));
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_CREATED_BY_FIELD,
                ANNOTATION.CREATE_USER,
                value -> value);
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_UPDATED_ON_FIELD,
                ANNOTATION.UPDATE_TIME_MS,
                value -> DateExpressionParser.getMs(AnnotationDecorationFields.ANNOTATION_UPDATED_ON, value));
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_UPDATED_BY_FIELD,
                ANNOTATION.UPDATE_USER,
                value -> value);
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_TITLE_FIELD, ANNOTATION.TITLE, value -> value);
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_SUBJECT_FIELD, ANNOTATION.SUBJECT, value -> value);
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_STATUS_FIELD, STATUS.NAME, value -> value);
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_ASSIGNED_TO_FIELD,
                ANNOTATION.ASSIGNED_TO_UUID,
                uuid ->
                        userRefLookup.getByUuid(uuid)
                                .map(UserRef::getUuid)
                                .orElse(null));
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_LABEL_FIELD, LABEL.NAME, value -> value);
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_COLLECTION_FIELD, COLLECTION.NAME, value -> value);
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_COMMENT_FIELD, COMMENT.DATA, value -> value);
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_HISTORY_FIELD, HISTORY.DATA, value -> value);
        expressionMapper.map(AnnotationDecorationFields.ANNOTATION_DESCRIPTION_FIELD,
                ANNOTATION.DESCRIPTION,
                value -> value);

        // Direct fields
        expressionMapper.map(AnnotationFields.ID_FIELD, ANNOTATION.ID, Long::valueOf);
        expressionMapper.map(AnnotationFields.UUID_FIELD, ANNOTATION.UUID, value -> value);
        expressionMapper.map(AnnotationFields.CREATED_ON_FIELD,
                ANNOTATION.CREATE_TIME_MS,
                value -> DateExpressionParser.getMs(AnnotationFields.CREATED_ON, value));
        expressionMapper.map(AnnotationFields.CREATED_BY_FIELD, ANNOTATION.CREATE_USER, value -> value);
        expressionMapper.map(AnnotationFields.UPDATED_ON_FIELD,
                ANNOTATION.UPDATE_TIME_MS,
                value -> DateExpressionParser.getMs(AnnotationFields.UPDATED_ON, value));
        expressionMapper.map(AnnotationFields.UPDATED_BY_FIELD, ANNOTATION.UPDATE_USER, value -> value);
        expressionMapper.map(AnnotationFields.TITLE_FIELD, ANNOTATION.TITLE, value -> value);
        expressionMapper.map(AnnotationFields.SUBJECT_FIELD, ANNOTATION.SUBJECT, value -> value);
        expressionMapper.map(AnnotationFields.STATUS_FIELD, STATUS.NAME, value -> value);
        expressionMapper.map(AnnotationFields.ASSIGNED_TO_FIELD, ANNOTATION.ASSIGNED_TO_UUID, uuid ->
                userRefLookup.getByUuid(uuid)
                        .map(UserRef::getUuid)
                        .orElse(null));
        expressionMapper.map(AnnotationFields.LABEL_FIELD, LABEL.NAME, value -> value);
        expressionMapper.map(AnnotationFields.COLLECTION_FIELD, COLLECTION.NAME, value -> value);
        expressionMapper.map(AnnotationFields.COMMENT_FIELD, COMMENT.DATA, value -> value);
        expressionMapper.map(AnnotationFields.HISTORY_FIELD, HISTORY.DATA, value -> value);
        expressionMapper.map(AnnotationFields.DESCRIPTION_FIELD, ANNOTATION.DESCRIPTION, value -> value);
        expressionMapper.map(AnnotationFields.STREAM_ID_FIELD, ANNOTATION_DATA_LINK.STREAM_ID, Long::valueOf);
        expressionMapper.map(AnnotationFields.EVENT_ID_FIELD, ANNOTATION_DATA_LINK.EVENT_ID, Long::valueOf);

        return expressionMapper;
    }

    private ValueMapper createValueMapper() {
        final ValueMapper valueMapper = new ValueMapper();

        // Decoration
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_ID_FIELD, ANNOTATION.ID, ValLong::create);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_UUID_FIELD, ANNOTATION.UUID, ValString::create);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_CREATED_ON_FIELD,
                ANNOTATION.CREATE_TIME_MS,
                ValDate::create);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_CREATED_BY_FIELD,
                ANNOTATION.CREATE_USER,
                ValString::create);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_UPDATED_ON_FIELD,
                ANNOTATION.UPDATE_TIME_MS,
                ValDate::create);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_UPDATED_BY_FIELD,
                ANNOTATION.UPDATE_USER,
                ValString::create);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_TITLE_FIELD, ANNOTATION.TITLE, ValString::create);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_SUBJECT_FIELD, ANNOTATION.SUBJECT, ValString::create);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_STATUS_FIELD, STATUS.NAME, ValString::create);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_ASSIGNED_TO_FIELD,
                ANNOTATION.ASSIGNED_TO_UUID,
                this::mapUserUuidToValString);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_LABEL_FIELD, LABEL.NAME, ValString::create);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_COLLECTION_FIELD, COLLECTION.NAME, ValString::create);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_COMMENT_FIELD, COMMENT.DATA, ValString::create);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_HISTORY_FIELD, HISTORY.DATA, ValString::create);
        valueMapper.map(AnnotationDecorationFields.ANNOTATION_DESCRIPTION_FIELD,
                ANNOTATION.DESCRIPTION,
                ValString::create);

        // Direct fields
        valueMapper.map(AnnotationFields.ID_FIELD, ANNOTATION.ID, ValLong::create);
        valueMapper.map(AnnotationFields.UUID_FIELD, ANNOTATION.UUID, ValString::create);
        valueMapper.map(AnnotationFields.CREATED_ON_FIELD, ANNOTATION.CREATE_TIME_MS, ValDate::create);
        valueMapper.map(AnnotationFields.CREATED_BY_FIELD, ANNOTATION.CREATE_USER, ValString::create);
        valueMapper.map(AnnotationFields.UPDATED_ON_FIELD, ANNOTATION.UPDATE_TIME_MS, ValDate::create);
        valueMapper.map(AnnotationFields.UPDATED_BY_FIELD, ANNOTATION.UPDATE_USER, ValString::create);
        valueMapper.map(AnnotationFields.TITLE_FIELD, ANNOTATION.TITLE, ValString::create);
        valueMapper.map(AnnotationFields.SUBJECT_FIELD, ANNOTATION.SUBJECT, ValString::create);
        valueMapper.map(AnnotationFields.STATUS_FIELD, STATUS.NAME, ValString::create);
        valueMapper.map(AnnotationFields.ASSIGNED_TO_FIELD, ANNOTATION.ASSIGNED_TO_UUID, this::mapUserUuidToValString);
        valueMapper.map(AnnotationFields.LABEL_FIELD, LABEL.NAME, ValString::create);
        valueMapper.map(AnnotationFields.COLLECTION_FIELD, COLLECTION.NAME, ValString::create);
        valueMapper.map(AnnotationFields.COMMENT_FIELD, COMMENT.DATA, ValString::create);
        valueMapper.map(AnnotationFields.HISTORY_FIELD, HISTORY.DATA, ValString::create);
        valueMapper.map(AnnotationFields.DESCRIPTION_FIELD, ANNOTATION.DESCRIPTION, ValString::create);
        valueMapper.map(AnnotationFields.STREAM_ID_FIELD, ANNOTATION_DATA_LINK.STREAM_ID, ValLong::create);
        valueMapper.map(AnnotationFields.EVENT_ID_FIELD, ANNOTATION_DATA_LINK.EVENT_ID, ValLong::create);

        return valueMapper;
    }

    private Optional<Long> getId(final DocRef docRef) {
        return JooqUtil.contextResult(connectionProvider, context -> context
                .select(ANNOTATION.ID)
                .from(ANNOTATION)
                .where(ANNOTATION.UUID.eq(docRef.getUuid()))
                .fetchOptional(ANNOTATION.ID));
    }

    @Override
    public Optional<Annotation> getAnnotationById(final long id) {
        return JooqUtil.contextResult(connectionProvider, context -> context
                        .select()
                        .from(ANNOTATION)
                        .where(ANNOTATION.ID.eq(id))
                        .fetchOptional())
                .map(this::mapToAnnotation);
    }

    @Override
    public Optional<Annotation> getAnnotationByDocRef(final DocRef annotationRef) {
        return JooqUtil.contextResult(connectionProvider, context -> context
                        .select()
                        .from(ANNOTATION)
                        .where(ANNOTATION.UUID.eq(annotationRef.getUuid()))
                        .fetchOptional())
                .map(this::mapToAnnotation);
    }

    @Override
    public List<DocRef> idListToDocRefs(final List<Long> idList) {
        return JooqUtil.contextResult(connectionProvider, context -> context
                        .select(ANNOTATION.UUID)
                        .from(ANNOTATION)
                        .where(ANNOTATION.ID.in(idList))
                        .fetch(ANNOTATION.UUID))
                .stream()
                .map(uuid -> new DocRef(Annotation.TYPE, uuid))
                .toList();
    }

    @Override
    public List<AnnotationEntry> getAnnotationEntries(final DocRef annotationRef) {
        return JooqUtil.contextResult(connectionProvider, context -> context
                        .select(ANNOTATION_ENTRY.ID,
                                ANNOTATION_ENTRY.ENTRY_TIME_MS,
                                ANNOTATION_ENTRY.ENTRY_USER_UUID,
                                ANNOTATION_ENTRY.TYPE_ID,
                                ANNOTATION_ENTRY.DATA)
                        .from(ANNOTATION_ENTRY)
                        .join(ANNOTATION).on(ANNOTATION_ENTRY.FK_ANNOTATION_ID.eq(ANNOTATION.ID))
                        .where(ANNOTATION.UUID.eq(annotationRef.getUuid()))
                        .orderBy(ANNOTATION_ENTRY.ENTRY_TIME_MS)
                        .fetch())
                .map(this::mapToAnnotationEntry);
    }

    @Override
    public List<Annotation> getAnnotationsForEvents(final EventId eventId) {
        return JooqUtil.contextResult(connectionProvider, context -> context
                        .select()
                        .from(ANNOTATION)
                        .join(ANNOTATION_DATA_LINK).on(ANNOTATION_DATA_LINK.FK_ANNOTATION_ID.eq(ANNOTATION.ID))
                        .where(ANNOTATION_DATA_LINK.STREAM_ID.eq(eventId.getStreamId())
                                .and(ANNOTATION_DATA_LINK.EVENT_ID.eq(eventId.getEventId())))
                        .fetch())
                .map(this::mapToAnnotation);
    }

    private AnnotationEntry mapToAnnotationEntry(final Record record) {
        final AnnotationEntry entry = new AnnotationEntry();
        entry.setId(record.get(ANNOTATION_ENTRY.ID));
        entry.setEntryTime(record.get(ANNOTATION_ENTRY.ENTRY_TIME_MS));
        entry.setEntryUser(getUserRef(record.get(ANNOTATION_ENTRY.ENTRY_USER_UUID)));

        final byte typeId = record.get(ANNOTATION_ENTRY.TYPE_ID);
        final AnnotationEntryType type = AnnotationEntryType.PRIMITIVE_VALUE_CONVERTER.fromPrimitiveValue(typeId);
        entry.setEntryType(type);
        final String data = record.get(ANNOTATION_ENTRY.DATA);
        if (data != null) {
            final EntryValue entryValue = AnnotationEntryType.ASSIGNED.equals(type)
                    ? UserRefEntryValue.of(getUserRef(data))
                    : StringEntryValue.of(data);
            entry.setEntryValue(entryValue);
        } else {
            entry.setEntryValue(null);
        }
        return entry;
    }

    private UserRef getUserRef(final String uuid) {
        if (uuid == null) {
            return null;
        }
        return userRefLookup.getByUuid(uuid)
                .orElse(UserRef.builder()
                        .uuid(uuid)
                        .build());
    }

    private String getComment(final long id) {
        return JooqUtil.contextResult(connectionProvider, context -> context
                        .select(ANNOTATION_ENTRY.DATA)
                        .from(ANNOTATION_ENTRY)
                        .where(ANNOTATION_ENTRY.FK_ANNOTATION_ID.eq(id))
                        .and(ANNOTATION_ENTRY.TYPE_ID.eq(AnnotationEntryType.COMMENT.getPrimitiveValue()))
                        .orderBy(ANNOTATION_ENTRY.ID.desc())
                        .limit(1)
                        .fetchOptional(ANNOTATION_ENTRY.DATA))
                .orElse(null);
    }

    private String getHistory(final long id) {
        final List<String> allComments = JooqUtil.contextResult(connectionProvider, context -> context
                .select(ANNOTATION_ENTRY.DATA)
                .from(ANNOTATION_ENTRY)
                .where(ANNOTATION_ENTRY.FK_ANNOTATION_ID.eq(id))
                .and(ANNOTATION_ENTRY.TYPE_ID.eq(AnnotationEntryType.COMMENT.getPrimitiveValue()))
                .orderBy(ANNOTATION_ENTRY.ID)
                .fetch(ANNOTATION_ENTRY.DATA));
        return String.join("|", allComments);
    }

    private Annotation mapToAnnotation(final Record record) {
        final long id = record.get(ANNOTATION.ID);

        final Map<AnnotationTagType, List<AnnotationTag>> tags = JooqUtil
                .contextResult(connectionProvider, context -> context
                        .select(ANNOTATION_TAG_LINK.FK_ANNOTATION_TAG_ID)
                        .from(ANNOTATION_TAG_LINK)
                        .where(ANNOTATION_TAG_LINK.FK_ANNOTATION_ID.eq(id))
                        .fetch(ANNOTATION_TAG_LINK.FK_ANNOTATION_TAG_ID))
                .stream()
                .map(annotationTagDao::get)
                .collect(Collectors.groupingBy(AnnotationTag::getType,
                        Collectors.mapping(Function.identity(), Collectors.toList())));

        final List<AnnotationTag> statuses = tags.get(AnnotationTagType.STATUS);
        final AnnotationTag status = statuses == null || statuses.isEmpty()
                ? null
                : statuses.getFirst();
        final List<AnnotationTag> labels = tags.get(AnnotationTagType.LABEL);
        final List<AnnotationTag> collections = tags.get(AnnotationTagType.COLLECTION);

        final String comment = getComment(id);
        final String history = getHistory(id);

        return Annotation.builder()
                .type(Annotation.TYPE)
                .uuid(record.get(ANNOTATION.UUID))
                .name(record.get(ANNOTATION.TITLE))
                .version("" + record.get(ANNOTATION.VERSION))
                .createTimeMs(record.get(ANNOTATION.CREATE_TIME_MS))
                .createUser(record.get(ANNOTATION.CREATE_USER))
                .updateTimeMs(record.get(ANNOTATION.UPDATE_TIME_MS))
                .updateUser(record.get(ANNOTATION.UPDATE_USER))
                .id(record.get(ANNOTATION.ID))
                .subject(record.get(ANNOTATION.SUBJECT))
                .status(status)
                .assignedTo(getUserRef(record.get(ANNOTATION.ASSIGNED_TO_UUID)))
                .labels(labels)
                .collections(collections)
                .comment(comment)
                .history(history)
                .description(record.get(ANNOTATION.DESCRIPTION))
                .retentionPeriod(mapToSimpleDuration(record))
                .build();
    }

    private SimpleDuration mapToSimpleDuration(final Record record) {
        final Long time = record.get(ANNOTATION.RETENTION_TIME);
        final Byte unit = record.get(ANNOTATION.RETENTION_UNIT);
        if (time != null && unit != null) {
            return new SimpleDuration(time, TimeUnit.PRIMITIVE_VALUE_CONVERTER.fromPrimitiveValue(unit));
        }
        return null;
    }

    private SimpleDuration getDefaultRetentionPeriod() {
        try {
            return SimpleDurationUtil.parse(annotationConfigProvider.get().getDefaultRetentionPeriod());
        } catch (final ParseException | RuntimeException e) {
            LOGGER.debug(e::getMessage, e);
        }
        return null;
    }

    @Override
    public Annotation createAnnotation(final CreateAnnotationRequest request,
                                       final UserRef currentUser) {
        final Instant now = Instant.now();
        final long nowMs = now.toEpochMilli();

        final Optional<AnnotationTag> annotationTag =
                annotationTagDao.findAnnotationTag(AnnotationTagType.STATUS, request.getStatus());

        Annotation.Builder builder = Annotation.builder()
                .type(Annotation.TYPE)
                .uuid(UUID.randomUUID().toString())
                .name(request.getTitle())
                .subject(request.getSubject())
                .status(annotationTag.orElse(null));

        final SimpleDuration retentionPeriod = getDefaultRetentionPeriod();
        final Long retainUntilTimeMs = calculateRetainUntilTimeMs(retentionPeriod, nowMs);

        Annotation annotation = builder
                .createTimeMs(nowMs)
                .createUser(currentUser.toDisplayString())
                .updateTimeMs(nowMs)
                .updateUser(currentUser.toDisplayString())
                .retentionPeriod(retentionPeriod)
                .retainUntilTimeMs(retainUntilTimeMs)
                .build();
        annotation = create(annotation);

        // Create default entries.
        if (annotation.getStatus() != null) {
            createEntry(
                    annotation.getId(),
                    currentUser,
                    now,
                    AnnotationEntryType.STATUS,
                    NullSafe.get(annotation.getStatus(), AnnotationTag::getName));
        }

        if (annotation.getAssignedTo() != null) {
            createEntry(
                    annotation.getId(),
                    currentUser,
                    now,
                    AnnotationEntryType.ASSIGNED,
                    NullSafe.get(annotation.getAssignedTo(), UserRef::getUuid));
        }

        if (!NullSafe.isEmptyCollection(request.getLinkedEvents())) {
            final long annotationId = annotation.getId();
            request.getLinkedEvents().forEach(eventID ->
                    createEventLink(now, currentUser, annotationId, eventID));
        }

        // Now select everything back to provide refreshed details.
        return annotation;
    }

    private Long calculateRetainUntilTimeMs(final SimpleDuration retentionPeriod,
                                            final long createTimeMs) {
        if (retentionPeriod != null && retentionPeriod.getTimeUnit() != null) {
            return SimpleDurationUtil
                    .plus(Instant.ofEpochMilli(createTimeMs), retentionPeriod).toEpochMilli();
        }
        return null;
    }

    @Override
    public boolean change(final SingleAnnotationChangeRequest request, final UserRef currentUser) {
        try {
            final Instant now = Instant.now();
            final long nowMs = now.toEpochMilli();

            // Update parent if we need to.
            final Optional<Long> optionalId = getId(request.getAnnotationRef());
            if (optionalId.isEmpty()) {
                throw new RuntimeException("Unable to create entry for unknown annotation");
            }

            final long annotationId = optionalId.get();
            final AbstractAnnotationChange change = request.getChange();

            if (change instanceof final ChangeTitle changeTitle) {
                JooqUtil.context(connectionProvider, context -> context
                        .update(ANNOTATION)
                        .set(ANNOTATION.TITLE, changeTitle.getTitle())
                        .set(ANNOTATION.UPDATE_USER, currentUser.toDisplayString())
                        .set(ANNOTATION.UPDATE_TIME_MS, nowMs)
                        .where(ANNOTATION.ID.eq(annotationId))
                        .execute());

                // Create history entry.
                createEntry(annotationId, currentUser, now, AnnotationEntryType.TITLE,
                        changeTitle.getTitle());

            } else if (change instanceof final ChangeSubject changeSubject) {
                JooqUtil.context(connectionProvider, context -> context
                        .update(ANNOTATION)
                        .set(ANNOTATION.SUBJECT, changeSubject.getSubject())
                        .set(ANNOTATION.UPDATE_USER, currentUser.toDisplayString())
                        .set(ANNOTATION.UPDATE_TIME_MS, nowMs)
                        .where(ANNOTATION.ID.eq(annotationId))
                        .execute());
                // Create history entry.
                createEntry(annotationId, currentUser, now, AnnotationEntryType.SUBJECT,
                        changeSubject.getSubject());

            } else if (change instanceof final AddTag addTag) {
                // Add the new tag.
                JooqUtil.context(connectionProvider, context ->
                        addTag(context, annotationId, addTag.getTag()));
                // Mark the annotation as updated.
                JooqUtil.context(connectionProvider, context ->
                        updateAnnotation(context, annotationId, currentUser, nowMs));

                // Create history entry.
                final AnnotationEntryType entryType = addTag.getTag().getType() == AnnotationTagType.LABEL
                        ? AnnotationEntryType.ADD_LABEL
                        : AnnotationEntryType.ADD_TO_COLLECTION;
                createEntry(annotationId, currentUser, now, entryType,
                        addTag.getTag().getName());

            } else if (change instanceof final RemoveTag removeTag) {
                // Remove the tag.
                JooqUtil.context(connectionProvider, context ->
                        removeTag(context, annotationId, removeTag.getTag()));

                // Mark the annotation as updated.
                JooqUtil.context(connectionProvider, context ->
                        updateAnnotation(context, annotationId, currentUser, nowMs));

                // Create history entry.
                final AnnotationEntryType entryType = removeTag.getTag().getType() == AnnotationTagType.LABEL
                        ? AnnotationEntryType.REMOVE_LABEL
                        : AnnotationEntryType.REMOVE_FROM_COLLECTION;
                createEntry(annotationId, currentUser, now, entryType,
                        removeTag.getTag().getName());

            } else if (change instanceof final SetTag setTag) {
                // Delete any existing tags with the same type.
                JooqUtil.context(connectionProvider, context ->
                        removeAllTags(context, annotationId, setTag.getTag().getType()));

                // Add the new tag.
                JooqUtil.context(connectionProvider, context ->
                        addTag(context, annotationId, setTag.getTag()));

                // Mark the annotation as updated.
                JooqUtil.context(connectionProvider, context ->
                        updateAnnotation(context, annotationId, currentUser, nowMs));

                // Create history entry.
                createEntry(annotationId, currentUser, now, AnnotationEntryType.STATUS,
                        setTag.getTag().getName());

            } else if (change instanceof final ChangeAssignedTo changeAssignedTo) {
                final String assignedToUuid = NullSafe
                        .get(changeAssignedTo, ChangeAssignedTo::getUserRef, UserRef::getUuid);
                JooqUtil.context(connectionProvider, context ->
                        context
                                .update(ANNOTATION)
                                .set(ANNOTATION.ASSIGNED_TO_UUID, assignedToUuid)
                                .set(ANNOTATION.UPDATE_USER, currentUser.toDisplayString())
                                .set(ANNOTATION.UPDATE_TIME_MS, nowMs)
                                .where(ANNOTATION.ID.eq(annotationId))
                                .execute());
                // Create history entry.
                createEntry(annotationId, currentUser, now, AnnotationEntryType.ASSIGNED, assignedToUuid);

            } else if (change instanceof final ChangeComment changeComment) {
                JooqUtil.context(connectionProvider, context ->
                        context
                                .update(ANNOTATION)
                                //                        .set(ANNOTATION.COMMENT, changeComment.getComment())
                                //                        .set(ANNOTATION.HISTORY, DSL
                                //                                .when(ANNOTATION.HISTORY.isNull(),
                                //                                changeComment.getComment())
                                //                                .otherwise(DSL.concat(ANNOTATION.HISTORY, "  |  " +
                                //                                changeComment.getComment())))
                                .set(ANNOTATION.UPDATE_USER, currentUser.toDisplayString())
                                .set(ANNOTATION.UPDATE_TIME_MS, nowMs)
                                .where(ANNOTATION.ID.eq(annotationId))
                                .execute());
                // Create history entry.

                createEntry(annotationId, currentUser, now, AnnotationEntryType.COMMENT,
                        changeComment.getComment());

            } else if (change instanceof final ChangeDescription changeDescription) {
                JooqUtil.context(connectionProvider, context ->
                        context
                                .update(ANNOTATION)
                                .set(ANNOTATION.DESCRIPTION, changeDescription.getDescription())
                                .set(ANNOTATION.UPDATE_USER, currentUser.toDisplayString())
                                .set(ANNOTATION.UPDATE_TIME_MS, nowMs)
                                .where(ANNOTATION.ID.eq(annotationId))
                                .execute());

            } else if (change instanceof final ChangeRetentionPeriod changeRetentionPeriod) {
                final SimpleDuration retentionPeriod = changeRetentionPeriod.getRetentionPeriod();
                final Long retainUntilTimeMs;
                if (retentionPeriod != null && retentionPeriod.getTimeUnit() != null) {
                    // Read the annotation to get the creation time.
                    final Optional<Annotation> optionalAnnotation = getAnnotationById(annotationId);
                    final Annotation annotation = optionalAnnotation
                            .orElseThrow(() -> new RuntimeException("Annotation not found"));
                    final long createTimeMs = annotation.getCreateTimeMs();
                    retainUntilTimeMs = calculateRetainUntilTimeMs(retentionPeriod, createTimeMs);
                } else {
                    retainUntilTimeMs = null;
                }

                JooqUtil.context(connectionProvider, context -> context
                        .update(ANNOTATION)
                        .set(ANNOTATION.RETENTION_TIME, NullSafe.get(
                                retentionPeriod,
                                SimpleDuration::getTime))
                        .set(ANNOTATION.RETENTION_UNIT, NullSafe.get(
                                retentionPeriod,
                                SimpleDuration::getTimeUnit,
                                TimeUnit::getPrimitiveValue))
                        .set(ANNOTATION.RETAIN_UNTIL_MS, retainUntilTimeMs)
                        .set(ANNOTATION.UPDATE_USER, currentUser.toDisplayString())
                        .set(ANNOTATION.UPDATE_TIME_MS, nowMs)
                        .where(ANNOTATION.ID.eq(annotationId))
                        .execute());

                // Create history entry.
                createEntry(annotationId, currentUser, now, AnnotationEntryType.RETENTION_PERIOD,
                        NullSafe.getOrElse(retentionPeriod, SimpleDuration::toString, "Forever"));

            } else if (change instanceof final LinkEvents linkEvents) {
                for (final EventId eventId : linkEvents.getEvents()) {
                    createEventLink(now, currentUser, annotationId, eventId);
                }

            } else if (change instanceof final UnlinkEvents unlinkEvents) {
                for (final EventId eventId : unlinkEvents.getEvents()) {
                    removeEventLink(now, currentUser, annotationId, eventId);
                }
            }
        } catch (final RuntimeException e) {
            LOGGER.error(e::getMessage, e);
            throw e;
        }

        // Now select everything back to provide refreshed details.
        return true;
    }

    private void removeAllTags(final DSLContext context,
                               final long annotationId,
                               final AnnotationTagType annotationTagType) {
        context.deleteFrom(ANNOTATION_TAG_LINK)
                .where(ANNOTATION_TAG_LINK.FK_ANNOTATION_ID.eq(annotationId)
                        .and(ANNOTATION_TAG_LINK.FK_ANNOTATION_TAG_ID.in(
                                context
                                        .select(ANNOTATION_TAG.ID)
                                        .from(ANNOTATION_TAG)
                                        .where(ANNOTATION_TAG.TYPE_ID.eq(
                                                annotationTagType.getPrimitiveValue())))
                        )).execute();
    }

    private void removeTag(final DSLContext context, final long annotationId, final AnnotationTag tag) {
        context
                .deleteFrom(ANNOTATION_TAG_LINK)
                .where(ANNOTATION_TAG_LINK.FK_ANNOTATION_ID.eq(annotationId))
                .and(ANNOTATION_TAG_LINK.FK_ANNOTATION_TAG_ID.eq(tag.getId()))
                .execute();
    }

    private void addTag(final DSLContext context, final long annotationId, final AnnotationTag tag) {
        context
                .insertInto(ANNOTATION_TAG_LINK,
                        ANNOTATION_TAG_LINK.FK_ANNOTATION_ID,
                        ANNOTATION_TAG_LINK.FK_ANNOTATION_TAG_ID)
                .values(annotationId,
                        tag.getId())
                .onDuplicateKeyUpdate()
                .set(ANNOTATION_TAG_LINK.FK_ANNOTATION_TAG_ID, tag.getId())
                .execute();
    }

    private void updateAnnotation(final DSLContext context,
                                  final long annotationId,
                                  final UserRef currentUser,
                                  final long nowMs) {
        context
                .update(ANNOTATION)
                .set(ANNOTATION.UPDATE_USER, currentUser.toDisplayString())
                .set(ANNOTATION.UPDATE_TIME_MS, nowMs)
                .where(ANNOTATION.ID.eq(annotationId))
                .execute();
    }

    private void createEntry(final long annotationId,
                             final UserRef currentUser,
                             final Instant now,
                             final AnnotationEntryType type,
                             final String fieldValue) {
        // Create entry.
        final int count = JooqUtil.contextResult(connectionProvider, context ->
                createEntry(context, annotationId, currentUser, now, type, fieldValue));
        if (count != 1) {
            throw new RuntimeException("Unable to create annotation entry");
        }
    }

    private int createEntry(final DSLContext context,
                            final long annotationId,
                            final UserRef currentUser,
                            final Instant now,
                            final AnnotationEntryType type,
                            final String fieldValue) {
        return context.insertInto(ANNOTATION_ENTRY,
                        ANNOTATION_ENTRY.ENTRY_TIME_MS,
                        ANNOTATION_ENTRY.ENTRY_USER_UUID,
                        ANNOTATION_ENTRY.FK_ANNOTATION_ID,
                        ANNOTATION_ENTRY.TYPE_ID,
                        ANNOTATION_ENTRY.DATA)
                .values(now.toEpochMilli(),
                        currentUser.getUuid(),
                        annotationId,
                        type.getPrimitiveValue(),
                        fieldValue)
                .execute();
    }

    private Annotation create(final Annotation annotation) {
        final String userUuid = getUserUuid(annotation.getAssignedTo());
        final Long retentionTime = NullSafe.get(
                annotation.getRetentionPeriod(),
                SimpleDuration::getTime);
        final Byte retentionTimeUnit = NullSafe.get(
                annotation.getRetentionPeriod(),
                SimpleDuration::getTimeUnit,
                TimeUnit::getPrimitiveValue);

        final Optional<Long> optional = JooqUtil.contextResult(connectionProvider, context -> context
                        .insertInto(ANNOTATION,
                                ANNOTATION.UUID,
                                ANNOTATION.VERSION,
                                ANNOTATION.CREATE_USER,
                                ANNOTATION.CREATE_TIME_MS,
                                ANNOTATION.UPDATE_USER,
                                ANNOTATION.UPDATE_TIME_MS,
                                ANNOTATION.TITLE,
                                ANNOTATION.SUBJECT,
                                ANNOTATION.ASSIGNED_TO_UUID,
                                ANNOTATION.DESCRIPTION,
                                ANNOTATION.RETENTION_TIME,
                                ANNOTATION.RETENTION_UNIT,
                                ANNOTATION.RETAIN_UNTIL_MS)
                        .values(
                                annotation.getUuid(),
                                1,
                                annotation.getCreateUser(),
                                annotation.getCreateTimeMs(),
                                annotation.getUpdateUser(),
                                annotation.getUpdateTimeMs(),
                                annotation.getName(),
                                annotation.getSubject(),
                                userUuid,
                                annotation.getDescription(),
                                retentionTime,
                                retentionTimeUnit,
                                annotation.getRetainUntilTimeMs())
                        .returning(ANNOTATION.ID)
                        .fetchOptional())
                .map(AnnotationRecord::getId);

        return optional
                .map(id -> annotation.copy().id(id).version("1").build())
                .orElse(null);
    }

    private void createEventLink(final Instant now,
                                 final UserRef currentUser,
                                 final long annotationId,
                                 final EventId eventId) {
        try {
            // Create event link.
            try {
                JooqUtil.onDuplicateKeyIgnore(() ->
                        JooqUtil.context(connectionProvider, context -> context
                                .insertInto(ANNOTATION_DATA_LINK,
                                        ANNOTATION_DATA_LINK.FK_ANNOTATION_ID,
                                        ANNOTATION_DATA_LINK.STREAM_ID,
                                        ANNOTATION_DATA_LINK.EVENT_ID)
                                .values(annotationId,
                                        eventId.getStreamId(),
                                        eventId.getEventId())
                                .execute()));
            } catch (final Exception e) {
                throw new RuntimeException("Unable to create event link", e);
            }

            // Record this link.
            createEntry(annotationId, currentUser, now, AnnotationEntryType.LINK, eventId.toString());

        } catch (final RuntimeException e) {
            LOGGER.debug(e::getMessage, e);
        }
    }

    private void removeEventLink(final Instant now,
                                 final UserRef currentUser,
                                 final long annotationId,
                                 final EventId eventId) {
        try {
            // Remove event link.
            final int count = JooqUtil.contextResult(connectionProvider, context -> context
                    .deleteFrom(ANNOTATION_DATA_LINK)
                    .where(ANNOTATION_DATA_LINK.FK_ANNOTATION_ID.eq(annotationId))
                    .and(ANNOTATION_DATA_LINK.STREAM_ID.eq(eventId.getStreamId()))
                    .and(ANNOTATION_DATA_LINK.EVENT_ID.eq(eventId.getEventId()))
                    .execute());

            if (count != 1) {
                throw new RuntimeException("Unable to remove event link");
            }

            // Record this link.
            createEntry(annotationId, currentUser, now, AnnotationEntryType.UNLINK, eventId.toString());

        } catch (final RuntimeException e) {
            LOGGER.debug(e::getMessage, e);
        }
    }

    @Override
    public List<EventId> getLinkedEvents(final DocRef annotationRef) {
        final Optional<Long> optionalId = getId(annotationRef);
        return optionalId.map(this::getLinkedEvents).orElse(Collections.emptyList());

    }

    private List<EventId> getLinkedEvents(final long annotationId) {
        return JooqUtil.contextResult(connectionProvider, context -> context
                        .select(ANNOTATION_DATA_LINK.STREAM_ID, ANNOTATION_DATA_LINK.EVENT_ID)
                        .from(ANNOTATION_DATA_LINK)
                        .where(ANNOTATION_DATA_LINK.FK_ANNOTATION_ID.eq(annotationId))
                        .orderBy(ANNOTATION_DATA_LINK.STREAM_ID, ANNOTATION_DATA_LINK.EVENT_ID)
                        .fetch())
                .map(r -> new EventId(r.get(ANNOTATION_DATA_LINK.STREAM_ID),
                        r.get(ANNOTATION_DATA_LINK.EVENT_ID)));
    }

    @Override
    public void search(final ExpressionCriteria criteria,
                       final FieldIndex fieldIndex,
                       final ValuesConsumer consumer,
                       final Predicate<String> uuidPredicate) {
        final String[] fieldNames = fieldIndex.getFields();
        final Condition condition = createCondition(criteria.getExpression())
                .and(ANNOTATION.DELETED.isFalse());
        final Set<Field<?>> dbFields = new HashSet<>(valueMapper.getDbFieldsByName(fieldNames));
        final Mapper<?>[] mappers = valueMapper.getMappersForFieldNames(fieldNames);
        dbFields.add(ANNOTATION.UUID);

        JooqUtil.context(connectionProvider, context -> {
            SelectJoinStep<?> select = context.select(dbFields)
                    .from(ANNOTATION);

            if (dbFields.contains(ANNOTATION_DATA_LINK.STREAM_ID) ||
                dbFields.contains(ANNOTATION_DATA_LINK.EVENT_ID)) {
                select = select
                        .leftOuterJoin(ANNOTATION_DATA_LINK)
                        .on(ANNOTATION_DATA_LINK.FK_ANNOTATION_ID.eq(ANNOTATION.ID));
            }

            try (final Cursor<?> cursor = select
                    .where(condition)
                    .fetchLazy()) {

                while (cursor.hasNext()) {
                    final Result<?> result = cursor.fetchNext(1000);
                    result.forEach(r -> {
                        final String uuid = r.get(ANNOTATION.UUID);
                        if (uuidPredicate.test(uuid)) {
                            final Val[] arr = new Val[fieldNames.length];
                            for (int i = 0; i < fieldNames.length; i++) {
                                Val val = ValNull.INSTANCE;
                                final Mapper<?> mapper = mappers[i];
                                if (mapper != null) {
                                    val = mapper.map(r);
                                }
                                arr[i] = val;
                            }
                            consumer.accept(Val.of(arr));
                        }
                    });
                }
            }
        });
    }

    @Override
    public List<Annotation> fetchByAssignedUser(final String userUuid) {
        Objects.requireNonNull(userUuid);
        return JooqUtil.contextResult(connectionProvider, context -> context
                        .select()
                        .from(ANNOTATION)
                        .where(ANNOTATION.ASSIGNED_TO_UUID.eq(userUuid))
                        .fetch())
                .map(this::mapToAnnotation);
    }

    private Val mapUserUuidToValString(final String userUuid) {
        if (userUuid == null) {
            return ValNull.INSTANCE;
        } else if (NullSafe.isBlankString(userUuid)) {
            return ValString.create(userUuid);
        } else {
            return NullSafe.getAsOptional(
                            userUuid,
                            this::getUserRef,
                            UserRef::toDisplayString,
                            value -> (Val) ValString.create(value))
                    .orElse(ValNull.INSTANCE);
        }
    }

    private String getUserUuid(final UserRef userRef) {
        if (userRef == null) {
            return null;
        } else {
            return userRef.getUuid();
        }
    }

    private Condition createCondition(final ExpressionOperator expression) {
        return expressionMapper.apply(expression);
    }

    @Override
    public boolean logicalDelete(final DocRef annotationRef,
                                 final UserRef currentUser) {
        final Instant now = Instant.now();
        final Optional<Long> optionalId = getId(annotationRef);
        return optionalId.map(id -> logicalDelete(id, currentUser, now, "Deleted by user")).orElse(false);
    }

    @Override
    public void markDeletedByDataRetention(final UserRef currentUser) {
        final Instant now = Instant.now();
        boolean keepGoing = true;
        while (keepGoing) {
            final List<Long> list = getAnnotationsPastRetention(now, DATA_RETENTION_BATCH_SIZE);
            keepGoing = list.size() == DATA_RETENTION_BATCH_SIZE;
            for (final long id : list) {
                logicalDelete(id, currentUser, now, "Deleted by data retention");
            }
        }
    }

    private List<Long> getAnnotationsPastRetention(final Instant now, final int batchSize) {
        return JooqUtil.contextResult(connectionProvider, context -> context
                .select(ANNOTATION.ID)
                .from(ANNOTATION)
                .where(ANNOTATION.DELETED.isFalse())
                .and(ANNOTATION.RETAIN_UNTIL_MS.isNotNull())
                .and(ANNOTATION.RETAIN_UNTIL_MS.lt(now.toEpochMilli()))
                .limit(batchSize)
                .fetch(ANNOTATION.ID));
    }

    private boolean logicalDelete(final long annotationId,
                                  final UserRef currentUser,
                                  final Instant now,
                                  final String message) {
        final boolean success = JooqUtil.contextResult(connectionProvider, context -> context
                .update(ANNOTATION)
                .set(ANNOTATION.DELETED, true)
                .set(ANNOTATION.UPDATE_USER, currentUser.toDisplayString())
                .set(ANNOTATION.UPDATE_TIME_MS, now.toEpochMilli())
                .where(ANNOTATION.ID.eq(annotationId))
                .and(ANNOTATION.DELETED.eq(false))
                .execute()) > 0;

        // Remember that this annotation was marked deleted.
        if (success) {
            createEntry(
                    annotationId,
                    currentUser,
                    now,
                    AnnotationEntryType.DELETE,
                    message);
        }
        return success;
    }

    @Override
    public void physicallyDelete(final Instant age) {
        JooqUtil.transaction(connectionProvider, context -> context
                .select(ANNOTATION.ID)
                .from(ANNOTATION)
                .where(ANNOTATION.DELETED.eq(true))
                .and(ANNOTATION.UPDATE_TIME_MS.lt(age.toEpochMilli()))
                .forEach(r -> {
                    final long id = r.get(ANNOTATION.ID);
                    context
                            .delete(ANNOTATION_DATA_LINK)
                            .where(ANNOTATION_DATA_LINK.FK_ANNOTATION_ID.eq(id))
                            .execute();
                    context
                            .delete(ANNOTATION_ENTRY)
                            .where(ANNOTATION_ENTRY.FK_ANNOTATION_ID.eq(id))
                            .execute();
                    context
                            .delete(ANNOTATION_TAG_LINK)
                            .where(ANNOTATION_TAG_LINK.FK_ANNOTATION_ID.eq(id))
                            .execute();
                    context
                            .delete(ANNOTATION)
                            .where(ANNOTATION.ID.eq(id))
                            .execute();
                }));
    }
}
