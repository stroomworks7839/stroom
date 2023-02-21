/*
 * This file is generated by jOOQ.
 */
package stroom.processor.impl.db.jooq.tables;


import stroom.processor.impl.db.jooq.Keys;
import stroom.processor.impl.db.jooq.Stroom;
import stroom.processor.impl.db.jooq.tables.records.ProcessorFilterTrackerRecord;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Identity;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Row13;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class ProcessorFilterTracker extends TableImpl<ProcessorFilterTrackerRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>stroom.processor_filter_tracker</code>
     */
    public static final ProcessorFilterTracker PROCESSOR_FILTER_TRACKER = new ProcessorFilterTracker();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<ProcessorFilterTrackerRecord> getRecordType() {
        return ProcessorFilterTrackerRecord.class;
    }

    /**
     * The column <code>stroom.processor_filter_tracker.id</code>.
     */
    public final TableField<ProcessorFilterTrackerRecord, Integer> ID = createField(DSL.name("id"), SQLDataType.INTEGER.nullable(false).identity(true), this, "");

    /**
     * The column <code>stroom.processor_filter_tracker.version</code>.
     */
    public final TableField<ProcessorFilterTrackerRecord, Integer> VERSION = createField(DSL.name("version"), SQLDataType.INTEGER.nullable(false), this, "");

    /**
     * The column <code>stroom.processor_filter_tracker.min_meta_id</code>.
     */
    public final TableField<ProcessorFilterTrackerRecord, Long> MIN_META_ID = createField(DSL.name("min_meta_id"), SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * The column <code>stroom.processor_filter_tracker.min_event_id</code>.
     */
    public final TableField<ProcessorFilterTrackerRecord, Long> MIN_EVENT_ID = createField(DSL.name("min_event_id"), SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * The column
     * <code>stroom.processor_filter_tracker.min_meta_create_ms</code>.
     */
    public final TableField<ProcessorFilterTrackerRecord, Long> MIN_META_CREATE_MS = createField(DSL.name("min_meta_create_ms"), SQLDataType.BIGINT, this, "");

    /**
     * The column
     * <code>stroom.processor_filter_tracker.max_meta_create_ms</code>.
     */
    public final TableField<ProcessorFilterTrackerRecord, Long> MAX_META_CREATE_MS = createField(DSL.name("max_meta_create_ms"), SQLDataType.BIGINT, this, "");

    /**
     * The column <code>stroom.processor_filter_tracker.meta_create_ms</code>.
     */
    public final TableField<ProcessorFilterTrackerRecord, Long> META_CREATE_MS = createField(DSL.name("meta_create_ms"), SQLDataType.BIGINT, this, "");

    /**
     * The column <code>stroom.processor_filter_tracker.last_poll_ms</code>.
     */
    public final TableField<ProcessorFilterTrackerRecord, Long> LAST_POLL_MS = createField(DSL.name("last_poll_ms"), SQLDataType.BIGINT, this, "");

    /**
     * The column
     * <code>stroom.processor_filter_tracker.last_poll_task_count</code>.
     */
    public final TableField<ProcessorFilterTrackerRecord, Integer> LAST_POLL_TASK_COUNT = createField(DSL.name("last_poll_task_count"), SQLDataType.INTEGER, this, "");

    /**
     * The column <code>stroom.processor_filter_tracker.message</code>.
     */
    public final TableField<ProcessorFilterTrackerRecord, String> MESSAGE = createField(DSL.name("message"), SQLDataType.VARCHAR(255), this, "");

    /**
     * The column <code>stroom.processor_filter_tracker.meta_count</code>.
     */
    public final TableField<ProcessorFilterTrackerRecord, Long> META_COUNT = createField(DSL.name("meta_count"), SQLDataType.BIGINT, this, "");

    /**
     * The column <code>stroom.processor_filter_tracker.event_count</code>.
     */
    public final TableField<ProcessorFilterTrackerRecord, Long> EVENT_COUNT = createField(DSL.name("event_count"), SQLDataType.BIGINT, this, "");

    /**
     * The column <code>stroom.processor_filter_tracker.status</code>.
     */
    public final TableField<ProcessorFilterTrackerRecord, Byte> STATUS = createField(DSL.name("status"), SQLDataType.TINYINT.nullable(false).defaultValue(DSL.inline("0", SQLDataType.TINYINT)), this, "");

    private ProcessorFilterTracker(Name alias, Table<ProcessorFilterTrackerRecord> aliased) {
        this(alias, aliased, null);
    }

    private ProcessorFilterTracker(Name alias, Table<ProcessorFilterTrackerRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>stroom.processor_filter_tracker</code> table
     * reference
     */
    public ProcessorFilterTracker(String alias) {
        this(DSL.name(alias), PROCESSOR_FILTER_TRACKER);
    }

    /**
     * Create an aliased <code>stroom.processor_filter_tracker</code> table
     * reference
     */
    public ProcessorFilterTracker(Name alias) {
        this(alias, PROCESSOR_FILTER_TRACKER);
    }

    /**
     * Create a <code>stroom.processor_filter_tracker</code> table reference
     */
    public ProcessorFilterTracker() {
        this(DSL.name("processor_filter_tracker"), null);
    }

    public <O extends Record> ProcessorFilterTracker(Table<O> child, ForeignKey<O, ProcessorFilterTrackerRecord> key) {
        super(child, key, PROCESSOR_FILTER_TRACKER);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Stroom.STROOM;
    }

    @Override
    public Identity<ProcessorFilterTrackerRecord, Integer> getIdentity() {
        return (Identity<ProcessorFilterTrackerRecord, Integer>) super.getIdentity();
    }

    @Override
    public UniqueKey<ProcessorFilterTrackerRecord> getPrimaryKey() {
        return Keys.KEY_PROCESSOR_FILTER_TRACKER_PRIMARY;
    }

    @Override
    public TableField<ProcessorFilterTrackerRecord, Integer> getRecordVersion() {
        return VERSION;
    }

    @Override
    public ProcessorFilterTracker as(String alias) {
        return new ProcessorFilterTracker(DSL.name(alias), this);
    }

    @Override
    public ProcessorFilterTracker as(Name alias) {
        return new ProcessorFilterTracker(alias, this);
    }

    /**
     * Rename this table
     */
    @Override
    public ProcessorFilterTracker rename(String name) {
        return new ProcessorFilterTracker(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public ProcessorFilterTracker rename(Name name) {
        return new ProcessorFilterTracker(name, null);
    }

    // -------------------------------------------------------------------------
    // Row13 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row13<Integer, Integer, Long, Long, Long, Long, Long, Long, Integer, String, Long, Long, Byte> fieldsRow() {
        return (Row13) super.fieldsRow();
    }
}
