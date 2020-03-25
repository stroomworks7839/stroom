/*
 * This file is generated by jOOQ.
 */
package stroom.processor.impl.db.jooq.tables;


import java.util.Arrays;
import java.util.List;

import javax.annotation.processing.Generated;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Identity;
import org.jooq.Index;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Row12;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.TableImpl;

import stroom.processor.impl.db.jooq.Indexes;
import stroom.processor.impl.db.jooq.Keys;
import stroom.processor.impl.db.jooq.Stroom;
import stroom.processor.impl.db.jooq.tables.records.ProcessorFilterRecord;


/**
 * This class is generated by jOOQ.
 */
@Generated(
    value = {
        "http://www.jooq.org",
        "jOOQ version:3.12.3"
    },
    comments = "This class is generated by jOOQ"
)
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class ProcessorFilter extends TableImpl<ProcessorFilterRecord> {

    private static final long serialVersionUID = -422566393;

    /**
     * The reference instance of <code>stroom.processor_filter</code>
     */
    public static final ProcessorFilter PROCESSOR_FILTER = new ProcessorFilter();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<ProcessorFilterRecord> getRecordType() {
        return ProcessorFilterRecord.class;
    }

    /**
     * The column <code>stroom.processor_filter.id</code>.
     */
    public final TableField<ProcessorFilterRecord, Integer> ID = createField(DSL.name("id"), org.jooq.impl.SQLDataType.INTEGER.nullable(false).identity(true), this, "");

    /**
     * The column <code>stroom.processor_filter.version</code>.
     */
    public final TableField<ProcessorFilterRecord, Integer> VERSION = createField(DSL.name("version"), org.jooq.impl.SQLDataType.INTEGER.nullable(false), this, "");

    /**
     * The column <code>stroom.processor_filter.create_time_ms</code>.
     */
    public final TableField<ProcessorFilterRecord, Long> CREATE_TIME_MS = createField(DSL.name("create_time_ms"), org.jooq.impl.SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * The column <code>stroom.processor_filter.create_user</code>.
     */
    public final TableField<ProcessorFilterRecord, String> CREATE_USER = createField(DSL.name("create_user"), org.jooq.impl.SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.processor_filter.update_time_ms</code>.
     */
    public final TableField<ProcessorFilterRecord, Long> UPDATE_TIME_MS = createField(DSL.name("update_time_ms"), org.jooq.impl.SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * The column <code>stroom.processor_filter.update_user</code>.
     */
    public final TableField<ProcessorFilterRecord, String> UPDATE_USER = createField(DSL.name("update_user"), org.jooq.impl.SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.processor_filter.uuid</code>.
     */
    public final TableField<ProcessorFilterRecord, String> UUID = createField(DSL.name("uuid"), org.jooq.impl.SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.processor_filter.fk_processor_id</code>.
     */
    public final TableField<ProcessorFilterRecord, Integer> FK_PROCESSOR_ID = createField(DSL.name("fk_processor_id"), org.jooq.impl.SQLDataType.INTEGER.nullable(false), this, "");

    /**
     * The column <code>stroom.processor_filter.fk_processor_filter_tracker_id</code>.
     */
    public final TableField<ProcessorFilterRecord, Integer> FK_PROCESSOR_FILTER_TRACKER_ID = createField(DSL.name("fk_processor_filter_tracker_id"), org.jooq.impl.SQLDataType.INTEGER.nullable(false), this, "");

    /**
     * The column <code>stroom.processor_filter.data</code>.
     */
    public final TableField<ProcessorFilterRecord, String> DATA = createField(DSL.name("data"), org.jooq.impl.SQLDataType.CLOB.nullable(false), this, "");

    /**
     * The column <code>stroom.processor_filter.priority</code>.
     */
    public final TableField<ProcessorFilterRecord, Integer> PRIORITY = createField(DSL.name("priority"), org.jooq.impl.SQLDataType.INTEGER.nullable(false), this, "");

    /**
     * The column <code>stroom.processor_filter.enabled</code>.
     */
    public final TableField<ProcessorFilterRecord, Boolean> ENABLED = createField(DSL.name("enabled"), org.jooq.impl.SQLDataType.BIT.nullable(false), this, "");

    /**
     * Create a <code>stroom.processor_filter</code> table reference
     */
    public ProcessorFilter() {
        this(DSL.name("processor_filter"), null);
    }

    /**
     * Create an aliased <code>stroom.processor_filter</code> table reference
     */
    public ProcessorFilter(String alias) {
        this(DSL.name(alias), PROCESSOR_FILTER);
    }

    /**
     * Create an aliased <code>stroom.processor_filter</code> table reference
     */
    public ProcessorFilter(Name alias) {
        this(alias, PROCESSOR_FILTER);
    }

    private ProcessorFilter(Name alias, Table<ProcessorFilterRecord> aliased) {
        this(alias, aliased, null);
    }

    private ProcessorFilter(Name alias, Table<ProcessorFilterRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""));
    }

    public <O extends Record> ProcessorFilter(Table<O> child, ForeignKey<O, ProcessorFilterRecord> key) {
        super(child, key, PROCESSOR_FILTER);
    }

    @Override
    public Schema getSchema() {
        return Stroom.STROOM;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.<Index>asList(Indexes.PROCESSOR_FILTER_PRIMARY, Indexes.PROCESSOR_FILTER_PROCESSOR_FILTER_FK_PROCESSOR_FILTER_TRACKER_ID, Indexes.PROCESSOR_FILTER_PROCESSOR_FILTER_FK_PROCESSOR_ID, Indexes.PROCESSOR_FILTER_UUID);
    }

    @Override
    public Identity<ProcessorFilterRecord, Integer> getIdentity() {
        return Keys.IDENTITY_PROCESSOR_FILTER;
    }

    @Override
    public UniqueKey<ProcessorFilterRecord> getPrimaryKey() {
        return Keys.KEY_PROCESSOR_FILTER_PRIMARY;
    }

    @Override
    public List<UniqueKey<ProcessorFilterRecord>> getKeys() {
        return Arrays.<UniqueKey<ProcessorFilterRecord>>asList(Keys.KEY_PROCESSOR_FILTER_PRIMARY, Keys.KEY_PROCESSOR_FILTER_UUID);
    }

    @Override
    public List<ForeignKey<ProcessorFilterRecord, ?>> getReferences() {
        return Arrays.<ForeignKey<ProcessorFilterRecord, ?>>asList(Keys.PROCESSOR_FILTER_FK_PROCESSOR_ID, Keys.PROCESSOR_FILTER_FK_PROCESSOR_FILTER_TRACKER_ID);
    }

    public Processor processor() {
        return new Processor(this, Keys.PROCESSOR_FILTER_FK_PROCESSOR_ID);
    }

    public ProcessorFilterTracker processorFilterTracker() {
        return new ProcessorFilterTracker(this, Keys.PROCESSOR_FILTER_FK_PROCESSOR_FILTER_TRACKER_ID);
    }

    @Override
    public TableField<ProcessorFilterRecord, Integer> getRecordVersion() {
        return VERSION;
    }

    @Override
    public ProcessorFilter as(String alias) {
        return new ProcessorFilter(DSL.name(alias), this);
    }

    @Override
    public ProcessorFilter as(Name alias) {
        return new ProcessorFilter(alias, this);
    }

    /**
     * Rename this table
     */
    @Override
    public ProcessorFilter rename(String name) {
        return new ProcessorFilter(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public ProcessorFilter rename(Name name) {
        return new ProcessorFilter(name, null);
    }

    // -------------------------------------------------------------------------
    // Row12 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row12<Integer, Integer, Long, String, Long, String, String, Integer, Integer, String, Integer, Boolean> fieldsRow() {
        return (Row12) super.fieldsRow();
    }
}
