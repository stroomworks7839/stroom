/*
 * This file is generated by jOOQ.
 */
package stroom.index.impl.db.jooq.tables;


import stroom.index.impl.db.jooq.Keys;
import stroom.index.impl.db.jooq.Stroom;
import stroom.index.impl.db.jooq.tables.records.IndexFieldSourceRecord;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Function4;
import org.jooq.Identity;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row4;
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
public class IndexFieldSource extends TableImpl<IndexFieldSourceRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>stroom.index_field_source</code>
     */
    public static final IndexFieldSource INDEX_FIELD_SOURCE = new IndexFieldSource();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<IndexFieldSourceRecord> getRecordType() {
        return IndexFieldSourceRecord.class;
    }

    /**
     * The column <code>stroom.index_field_source.id</code>.
     */
    public final TableField<IndexFieldSourceRecord, Integer> ID = createField(DSL.name("id"), SQLDataType.INTEGER.nullable(false).identity(true), this, "");

    /**
     * The column <code>stroom.index_field_source.type</code>.
     */
    public final TableField<IndexFieldSourceRecord, String> TYPE = createField(DSL.name("type"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.index_field_source.uuid</code>.
     */
    public final TableField<IndexFieldSourceRecord, String> UUID = createField(DSL.name("uuid"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.index_field_source.name</code>.
     */
    public final TableField<IndexFieldSourceRecord, String> NAME = createField(DSL.name("name"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    private IndexFieldSource(Name alias, Table<IndexFieldSourceRecord> aliased) {
        this(alias, aliased, null);
    }

    private IndexFieldSource(Name alias, Table<IndexFieldSourceRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>stroom.index_field_source</code> table reference
     */
    public IndexFieldSource(String alias) {
        this(DSL.name(alias), INDEX_FIELD_SOURCE);
    }

    /**
     * Create an aliased <code>stroom.index_field_source</code> table reference
     */
    public IndexFieldSource(Name alias) {
        this(alias, INDEX_FIELD_SOURCE);
    }

    /**
     * Create a <code>stroom.index_field_source</code> table reference
     */
    public IndexFieldSource() {
        this(DSL.name("index_field_source"), null);
    }

    public <O extends Record> IndexFieldSource(Table<O> child, ForeignKey<O, IndexFieldSourceRecord> key) {
        super(child, key, INDEX_FIELD_SOURCE);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Stroom.STROOM;
    }

    @Override
    public Identity<IndexFieldSourceRecord, Integer> getIdentity() {
        return (Identity<IndexFieldSourceRecord, Integer>) super.getIdentity();
    }

    @Override
    public UniqueKey<IndexFieldSourceRecord> getPrimaryKey() {
        return Keys.KEY_INDEX_FIELD_SOURCE_PRIMARY;
    }

    @Override
    public List<UniqueKey<IndexFieldSourceRecord>> getUniqueKeys() {
        return Arrays.asList(Keys.KEY_INDEX_FIELD_SOURCE_INDEX_FIELD_SOURCE_TYPE_UUID);
    }

    @Override
    public IndexFieldSource as(String alias) {
        return new IndexFieldSource(DSL.name(alias), this);
    }

    @Override
    public IndexFieldSource as(Name alias) {
        return new IndexFieldSource(alias, this);
    }

    @Override
    public IndexFieldSource as(Table<?> alias) {
        return new IndexFieldSource(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public IndexFieldSource rename(String name) {
        return new IndexFieldSource(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public IndexFieldSource rename(Name name) {
        return new IndexFieldSource(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public IndexFieldSource rename(Table<?> name) {
        return new IndexFieldSource(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row4 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row4<Integer, String, String, String> fieldsRow() {
        return (Row4) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function4<? super Integer, ? super String, ? super String, ? super String, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function4<? super Integer, ? super String, ? super String, ? super String, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
