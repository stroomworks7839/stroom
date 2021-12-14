/*
 * This file is generated by jOOQ.
 */
package stroom.meta.impl.db.jooq.tables;


import java.util.Arrays;
import java.util.List;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Index;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Row3;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.TableImpl;

import stroom.meta.impl.db.jooq.Indexes;
import stroom.meta.impl.db.jooq.Keys;
import stroom.meta.impl.db.jooq.Stroom;
import stroom.meta.impl.db.jooq.tables.records.MetaRetentionTrackerRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class MetaRetentionTracker extends TableImpl<MetaRetentionTrackerRecord> {

    private static final long serialVersionUID = 310388399;

    /**
     * The reference instance of <code>stroom.meta_retention_tracker</code>
     */
    public static final MetaRetentionTracker META_RETENTION_TRACKER = new MetaRetentionTracker();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<MetaRetentionTrackerRecord> getRecordType() {
        return MetaRetentionTrackerRecord.class;
    }

    /**
     * The column <code>stroom.meta_retention_tracker.retention_rules_version</code>.
     */
    public final TableField<MetaRetentionTrackerRecord, String> RETENTION_RULES_VERSION = createField(DSL.name("retention_rules_version"), org.jooq.impl.SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.meta_retention_tracker.rule_age</code>.
     */
    public final TableField<MetaRetentionTrackerRecord, String> RULE_AGE = createField(DSL.name("rule_age"), org.jooq.impl.SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.meta_retention_tracker.last_run_time</code>.
     */
    public final TableField<MetaRetentionTrackerRecord, Long> LAST_RUN_TIME = createField(DSL.name("last_run_time"), org.jooq.impl.SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * Create a <code>stroom.meta_retention_tracker</code> table reference
     */
    public MetaRetentionTracker() {
        this(DSL.name("meta_retention_tracker"), null);
    }

    /**
     * Create an aliased <code>stroom.meta_retention_tracker</code> table reference
     */
    public MetaRetentionTracker(String alias) {
        this(DSL.name(alias), META_RETENTION_TRACKER);
    }

    /**
     * Create an aliased <code>stroom.meta_retention_tracker</code> table reference
     */
    public MetaRetentionTracker(Name alias) {
        this(alias, META_RETENTION_TRACKER);
    }

    private MetaRetentionTracker(Name alias, Table<MetaRetentionTrackerRecord> aliased) {
        this(alias, aliased, null);
    }

    private MetaRetentionTracker(Name alias, Table<MetaRetentionTrackerRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""));
    }

    public <O extends Record> MetaRetentionTracker(Table<O> child, ForeignKey<O, MetaRetentionTrackerRecord> key) {
        super(child, key, META_RETENTION_TRACKER);
    }

    @Override
    public Schema getSchema() {
        return Stroom.STROOM;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.<Index>asList(Indexes.META_RETENTION_TRACKER_PRIMARY);
    }

    @Override
    public UniqueKey<MetaRetentionTrackerRecord> getPrimaryKey() {
        return Keys.KEY_META_RETENTION_TRACKER_PRIMARY;
    }

    @Override
    public List<UniqueKey<MetaRetentionTrackerRecord>> getKeys() {
        return Arrays.<UniqueKey<MetaRetentionTrackerRecord>>asList(Keys.KEY_META_RETENTION_TRACKER_PRIMARY);
    }

    @Override
    public MetaRetentionTracker as(String alias) {
        return new MetaRetentionTracker(DSL.name(alias), this);
    }

    @Override
    public MetaRetentionTracker as(Name alias) {
        return new MetaRetentionTracker(alias, this);
    }

    /**
     * Rename this table
     */
    @Override
    public MetaRetentionTracker rename(String name) {
        return new MetaRetentionTracker(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public MetaRetentionTracker rename(Name name) {
        return new MetaRetentionTracker(name, null);
    }

    // -------------------------------------------------------------------------
    // Row3 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row3<String, String, Long> fieldsRow() {
        return (Row3) super.fieldsRow();
    }
}
