/*
 * This file is generated by jOOQ.
 */
package stroom.annotation.impl.db.jooq.tables;


import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Identity;
import org.jooq.InverseForeignKey;
import org.jooq.Name;
import org.jooq.Path;
import org.jooq.PlainSQL;
import org.jooq.QueryPart;
import org.jooq.Record;
import org.jooq.SQL;
import org.jooq.Schema;
import org.jooq.Select;
import org.jooq.Stringly;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

import stroom.annotation.impl.db.jooq.Keys;
import stroom.annotation.impl.db.jooq.Stroom;
import stroom.annotation.impl.db.jooq.tables.AnnotationDataLink.AnnotationDataLinkPath;
import stroom.annotation.impl.db.jooq.tables.AnnotationEntry.AnnotationEntryPath;
import stroom.annotation.impl.db.jooq.tables.AnnotationLink.AnnotationLinkPath;
import stroom.annotation.impl.db.jooq.tables.AnnotationSubscription.AnnotationSubscriptionPath;
import stroom.annotation.impl.db.jooq.tables.AnnotationTag.AnnotationTagPath;
import stroom.annotation.impl.db.jooq.tables.AnnotationTagLink.AnnotationTagLinkPath;
import stroom.annotation.impl.db.jooq.tables.records.AnnotationRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class Annotation extends TableImpl<AnnotationRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>stroom.annotation</code>
     */
    public static final Annotation ANNOTATION = new Annotation();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<AnnotationRecord> getRecordType() {
        return AnnotationRecord.class;
    }

    /**
     * The column <code>stroom.annotation.id</code>.
     */
    public final TableField<AnnotationRecord, Long> ID = createField(DSL.name("id"), SQLDataType.BIGINT.nullable(false).identity(true), this, "");

    /**
     * The column <code>stroom.annotation.version</code>.
     */
    public final TableField<AnnotationRecord, Integer> VERSION = createField(DSL.name("version"), SQLDataType.INTEGER.nullable(false), this, "");

    /**
     * The column <code>stroom.annotation.create_time_ms</code>.
     */
    public final TableField<AnnotationRecord, Long> CREATE_TIME_MS = createField(DSL.name("create_time_ms"), SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * The column <code>stroom.annotation.create_user</code>.
     */
    public final TableField<AnnotationRecord, String> CREATE_USER = createField(DSL.name("create_user"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.annotation.update_time_ms</code>.
     */
    public final TableField<AnnotationRecord, Long> UPDATE_TIME_MS = createField(DSL.name("update_time_ms"), SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * The column <code>stroom.annotation.update_user</code>.
     */
    public final TableField<AnnotationRecord, String> UPDATE_USER = createField(DSL.name("update_user"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.annotation.title</code>.
     */
    public final TableField<AnnotationRecord, String> TITLE = createField(DSL.name("title"), SQLDataType.CLOB, this, "");

    /**
     * The column <code>stroom.annotation.subject</code>.
     */
    public final TableField<AnnotationRecord, String> SUBJECT = createField(DSL.name("subject"), SQLDataType.CLOB, this, "");

    /**
     * The column <code>stroom.annotation.assigned_to_uuid</code>.
     */
    public final TableField<AnnotationRecord, String> ASSIGNED_TO_UUID = createField(DSL.name("assigned_to_uuid"), SQLDataType.VARCHAR(255), this, "");

    /**
     * The column <code>stroom.annotation.uuid</code>.
     */
    public final TableField<AnnotationRecord, String> UUID = createField(DSL.name("uuid"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.annotation.deleted</code>.
     */
    public final TableField<AnnotationRecord, Boolean> DELETED = createField(DSL.name("deleted"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline("0", SQLDataType.BOOLEAN)), this, "");

    /**
     * The column <code>stroom.annotation.description</code>.
     */
    public final TableField<AnnotationRecord, String> DESCRIPTION = createField(DSL.name("description"), SQLDataType.CLOB, this, "");

    /**
     * The column <code>stroom.annotation.retention_time</code>.
     */
    public final TableField<AnnotationRecord, Long> RETENTION_TIME = createField(DSL.name("retention_time"), SQLDataType.BIGINT, this, "");

    /**
     * The column <code>stroom.annotation.retention_unit</code>.
     */
    public final TableField<AnnotationRecord, Byte> RETENTION_UNIT = createField(DSL.name("retention_unit"), SQLDataType.TINYINT, this, "");

    /**
     * The column <code>stroom.annotation.retain_until_ms</code>.
     */
    public final TableField<AnnotationRecord, Long> RETAIN_UNTIL_MS = createField(DSL.name("retain_until_ms"), SQLDataType.BIGINT, this, "");

    /**
     * The column <code>stroom.annotation.parent_id</code>.
     */
    public final TableField<AnnotationRecord, Long> PARENT_ID = createField(DSL.name("parent_id"), SQLDataType.BIGINT, this, "");

    private Annotation(Name alias, Table<AnnotationRecord> aliased) {
        this(alias, aliased, (Field<?>[]) null, null);
    }

    private Annotation(Name alias, Table<AnnotationRecord> aliased, Field<?>[] parameters, Condition where) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table(), where);
    }

    /**
     * Create an aliased <code>stroom.annotation</code> table reference
     */
    public Annotation(String alias) {
        this(DSL.name(alias), ANNOTATION);
    }

    /**
     * Create an aliased <code>stroom.annotation</code> table reference
     */
    public Annotation(Name alias) {
        this(alias, ANNOTATION);
    }

    /**
     * Create a <code>stroom.annotation</code> table reference
     */
    public Annotation() {
        this(DSL.name("annotation"), null);
    }

    public <O extends Record> Annotation(Table<O> path, ForeignKey<O, AnnotationRecord> childPath, InverseForeignKey<O, AnnotationRecord> parentPath) {
        super(path, childPath, parentPath, ANNOTATION);
    }

    /**
     * A subtype implementing {@link Path} for simplified path-based joins.
     */
    public static class AnnotationPath extends Annotation implements Path<AnnotationRecord> {
        public <O extends Record> AnnotationPath(Table<O> path, ForeignKey<O, AnnotationRecord> childPath, InverseForeignKey<O, AnnotationRecord> parentPath) {
            super(path, childPath, parentPath);
        }
        private AnnotationPath(Name alias, Table<AnnotationRecord> aliased) {
            super(alias, aliased);
        }

        @Override
        public AnnotationPath as(String alias) {
            return new AnnotationPath(DSL.name(alias), this);
        }

        @Override
        public AnnotationPath as(Name alias) {
            return new AnnotationPath(alias, this);
        }

        @Override
        public AnnotationPath as(Table<?> alias) {
            return new AnnotationPath(alias.getQualifiedName(), this);
        }
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Stroom.STROOM;
    }

    @Override
    public Identity<AnnotationRecord, Long> getIdentity() {
        return (Identity<AnnotationRecord, Long>) super.getIdentity();
    }

    @Override
    public UniqueKey<AnnotationRecord> getPrimaryKey() {
        return Keys.KEY_ANNOTATION_PRIMARY;
    }

    @Override
    public List<UniqueKey<AnnotationRecord>> getUniqueKeys() {
        return Arrays.asList(Keys.KEY_ANNOTATION_ANNOTATION_UUID);
    }

    private transient AnnotationDataLinkPath _annotationDataLink;

    /**
     * Get the implicit to-many join path to the
     * <code>stroom.annotation_data_link</code> table
     */
    public AnnotationDataLinkPath annotationDataLink() {
        if (_annotationDataLink == null)
            _annotationDataLink = new AnnotationDataLinkPath(this, null, Keys.ANNOTATION_DATA_LINK_FK_ANNOTATION_ID.getInverseKey());

        return _annotationDataLink;
    }

    private transient AnnotationEntryPath _annotationEntry;

    /**
     * Get the implicit to-many join path to the
     * <code>stroom.annotation_entry</code> table
     */
    public AnnotationEntryPath annotationEntry() {
        if (_annotationEntry == null)
            _annotationEntry = new AnnotationEntryPath(this, null, Keys.ANNOTATION_ENTRY_FK_ANNOTATION_ID.getInverseKey());

        return _annotationEntry;
    }

    private transient AnnotationLinkPath _annotationLinkFkAnnotationDstId;

    /**
     * Get the implicit to-many join path to the
     * <code>stroom.annotation_link</code> table, via the
     * <code>annotation_link_fk_annotation_dst_id</code> key
     */
    public AnnotationLinkPath annotationLinkFkAnnotationDstId() {
        if (_annotationLinkFkAnnotationDstId == null)
            _annotationLinkFkAnnotationDstId = new AnnotationLinkPath(this, null, Keys.ANNOTATION_LINK_FK_ANNOTATION_DST_ID.getInverseKey());

        return _annotationLinkFkAnnotationDstId;
    }

    private transient AnnotationLinkPath _annotationLinkFkAnnotationSrcId;

    /**
     * Get the implicit to-many join path to the
     * <code>stroom.annotation_link</code> table, via the
     * <code>annotation_link_fk_annotation_src_id</code> key
     */
    public AnnotationLinkPath annotationLinkFkAnnotationSrcId() {
        if (_annotationLinkFkAnnotationSrcId == null)
            _annotationLinkFkAnnotationSrcId = new AnnotationLinkPath(this, null, Keys.ANNOTATION_LINK_FK_ANNOTATION_SRC_ID.getInverseKey());

        return _annotationLinkFkAnnotationSrcId;
    }

    private transient AnnotationSubscriptionPath _annotationSubscription;

    /**
     * Get the implicit to-many join path to the
     * <code>stroom.annotation_subscription</code> table
     */
    public AnnotationSubscriptionPath annotationSubscription() {
        if (_annotationSubscription == null)
            _annotationSubscription = new AnnotationSubscriptionPath(this, null, Keys.ANNOTATION_SUBSCRIPTION_FK_ANNOTATION_ID.getInverseKey());

        return _annotationSubscription;
    }

    private transient AnnotationTagLinkPath _annotationTagLink;

    /**
     * Get the implicit to-many join path to the
     * <code>stroom.annotation_tag_link</code> table
     */
    public AnnotationTagLinkPath annotationTagLink() {
        if (_annotationTagLink == null)
            _annotationTagLink = new AnnotationTagLinkPath(this, null, Keys.ANNOTATION_TAG_LINK_FK_ANNOTATION_ID.getInverseKey());

        return _annotationTagLink;
    }

    /**
     * Get the implicit many-to-many join path to the
     * <code>stroom.annotation_tag</code> table
     */
    public AnnotationTagPath annotationTag() {
        return annotationTagLink().annotationTag();
    }

    @Override
    public TableField<AnnotationRecord, Integer> getRecordVersion() {
        return VERSION;
    }

    @Override
    public Annotation as(String alias) {
        return new Annotation(DSL.name(alias), this);
    }

    @Override
    public Annotation as(Name alias) {
        return new Annotation(alias, this);
    }

    @Override
    public Annotation as(Table<?> alias) {
        return new Annotation(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public Annotation rename(String name) {
        return new Annotation(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public Annotation rename(Name name) {
        return new Annotation(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public Annotation rename(Table<?> name) {
        return new Annotation(name.getQualifiedName(), null);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Annotation where(Condition condition) {
        return new Annotation(getQualifiedName(), aliased() ? this : null, null, condition);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Annotation where(Collection<? extends Condition> conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Annotation where(Condition... conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Annotation where(Field<Boolean> condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public Annotation where(SQL condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public Annotation where(@Stringly.SQL String condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public Annotation where(@Stringly.SQL String condition, Object... binds) {
        return where(DSL.condition(condition, binds));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public Annotation where(@Stringly.SQL String condition, QueryPart... parts) {
        return where(DSL.condition(condition, parts));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Annotation whereExists(Select<?> select) {
        return where(DSL.exists(select));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Annotation whereNotExists(Select<?> select) {
        return where(DSL.notExists(select));
    }
}
