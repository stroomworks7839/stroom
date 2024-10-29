/*
 * This file is generated by jOOQ.
 */
package stroom.processor.impl.db.jooq.tables;


import stroom.processor.impl.db.jooq.Keys;
import stroom.processor.impl.db.jooq.Stroom;
import stroom.processor.impl.db.jooq.tables.records.ProcessorRecord;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Function11;
import org.jooq.Identity;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row11;
import org.jooq.Schema;
import org.jooq.SelectField;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class Processor extends TableImpl<ProcessorRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>stroom.processor</code>
     */
    public static final Processor PROCESSOR = new Processor();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<ProcessorRecord> getRecordType() {
        return ProcessorRecord.class;
    }

    /**
     * The column <code>stroom.processor.id</code>.
     */
    public final TableField<ProcessorRecord, Integer> ID = createField(DSL.name("id"), SQLDataType.INTEGER.nullable(false).identity(true), this, "");

    /**
     * The column <code>stroom.processor.version</code>.
     */
    public final TableField<ProcessorRecord, Integer> VERSION = createField(DSL.name("version"), SQLDataType.INTEGER.nullable(false), this, "");

    /**
     * The column <code>stroom.processor.create_time_ms</code>.
     */
    public final TableField<ProcessorRecord, Long> CREATE_TIME_MS = createField(DSL.name("create_time_ms"), SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * The column <code>stroom.processor.create_user</code>.
     */
    public final TableField<ProcessorRecord, String> CREATE_USER = createField(DSL.name("create_user"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.processor.update_time_ms</code>.
     */
    public final TableField<ProcessorRecord, Long> UPDATE_TIME_MS = createField(DSL.name("update_time_ms"), SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * The column <code>stroom.processor.update_user</code>.
     */
    public final TableField<ProcessorRecord, String> UPDATE_USER = createField(DSL.name("update_user"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.processor.uuid</code>.
     */
    public final TableField<ProcessorRecord, String> UUID = createField(DSL.name("uuid"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.processor.task_type</code>.
     */
    public final TableField<ProcessorRecord, String> TASK_TYPE = createField(DSL.name("task_type"), SQLDataType.VARCHAR(255), this, "");

    /**
     * The column <code>stroom.processor.pipeline_uuid</code>.
     */
    public final TableField<ProcessorRecord, String> PIPELINE_UUID = createField(DSL.name("pipeline_uuid"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.processor.enabled</code>.
     */
    public final TableField<ProcessorRecord, Boolean> ENABLED = createField(DSL.name("enabled"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline("0", SQLDataType.BOOLEAN)), this, "");

    /**
     * The column <code>stroom.processor.deleted</code>.
     */
    public final TableField<ProcessorRecord, Boolean> DELETED = createField(DSL.name("deleted"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline("0", SQLDataType.BOOLEAN)), this, "");

    private Processor(Name alias, Table<ProcessorRecord> aliased) {
        this(alias, aliased, null);
    }

    private Processor(Name alias, Table<ProcessorRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>stroom.processor</code> table reference
     */
    public Processor(String alias) {
        this(DSL.name(alias), PROCESSOR);
    }

    /**
     * Create an aliased <code>stroom.processor</code> table reference
     */
    public Processor(Name alias) {
        this(alias, PROCESSOR);
    }

    /**
     * Create a <code>stroom.processor</code> table reference
     */
    public Processor() {
        this(DSL.name("processor"), null);
    }

    public <O extends Record> Processor(Table<O> child, ForeignKey<O, ProcessorRecord> key) {
        super(child, key, PROCESSOR);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Stroom.STROOM;
    }

    @Override
    public Identity<ProcessorRecord, Integer> getIdentity() {
        return (Identity<ProcessorRecord, Integer>) super.getIdentity();
    }

    @Override
    public UniqueKey<ProcessorRecord> getPrimaryKey() {
        return Keys.KEY_PROCESSOR_PRIMARY;
    }

    @Override
    public List<UniqueKey<ProcessorRecord>> getUniqueKeys() {
        return Arrays.asList(Keys.KEY_PROCESSOR_PROCESSOR_UUID, Keys.KEY_PROCESSOR_PROCESSOR_TASK_TYPE_PIPELINE_UUID);
    }

    @Override
    public TableField<ProcessorRecord, Integer> getRecordVersion() {
        return VERSION;
    }

    @Override
    public Processor as(String alias) {
        return new Processor(DSL.name(alias), this);
    }

    @Override
    public Processor as(Name alias) {
        return new Processor(alias, this);
    }

    @Override
    public Processor as(Table<?> alias) {
        return new Processor(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public Processor rename(String name) {
        return new Processor(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public Processor rename(Name name) {
        return new Processor(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public Processor rename(Table<?> name) {
        return new Processor(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row11 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row11<Integer, Integer, Long, String, Long, String, String, String, String, Boolean, Boolean> fieldsRow() {
        return (Row11) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function11<? super Integer, ? super Integer, ? super Long, ? super String, ? super Long, ? super String, ? super String, ? super String, ? super String, ? super Boolean, ? super Boolean, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function11<? super Integer, ? super Integer, ? super Long, ? super String, ? super Long, ? super String, ? super String, ? super String, ? super String, ? super Boolean, ? super Boolean, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
