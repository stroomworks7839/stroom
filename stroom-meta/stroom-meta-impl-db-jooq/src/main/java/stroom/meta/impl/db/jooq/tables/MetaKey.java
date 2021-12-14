/*
 * This file is generated by jOOQ.
 */
package stroom.meta.impl.db.jooq.tables;


import java.util.Arrays;
import java.util.List;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Identity;
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
import stroom.meta.impl.db.jooq.tables.records.MetaKeyRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class MetaKey extends TableImpl<MetaKeyRecord> {

    private static final long serialVersionUID = 289219362;

    /**
     * The reference instance of <code>stroom.meta_key</code>
     */
    public static final MetaKey META_KEY = new MetaKey();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<MetaKeyRecord> getRecordType() {
        return MetaKeyRecord.class;
    }

    /**
     * The column <code>stroom.meta_key.id</code>.
     */
    public final TableField<MetaKeyRecord, Integer> ID = createField(DSL.name("id"), org.jooq.impl.SQLDataType.INTEGER.nullable(false).identity(true), this, "");

    /**
     * The column <code>stroom.meta_key.name</code>.
     */
    public final TableField<MetaKeyRecord, String> NAME = createField(DSL.name("name"), org.jooq.impl.SQLDataType.VARCHAR(100).nullable(false), this, "");

    /**
     * The column <code>stroom.meta_key.field_type</code>.
     */
    public final TableField<MetaKeyRecord, Byte> FIELD_TYPE = createField(DSL.name("field_type"), org.jooq.impl.SQLDataType.TINYINT.nullable(false), this, "");

    /**
     * Create a <code>stroom.meta_key</code> table reference
     */
    public MetaKey() {
        this(DSL.name("meta_key"), null);
    }

    /**
     * Create an aliased <code>stroom.meta_key</code> table reference
     */
    public MetaKey(String alias) {
        this(DSL.name(alias), META_KEY);
    }

    /**
     * Create an aliased <code>stroom.meta_key</code> table reference
     */
    public MetaKey(Name alias) {
        this(alias, META_KEY);
    }

    private MetaKey(Name alias, Table<MetaKeyRecord> aliased) {
        this(alias, aliased, null);
    }

    private MetaKey(Name alias, Table<MetaKeyRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""));
    }

    public <O extends Record> MetaKey(Table<O> child, ForeignKey<O, MetaKeyRecord> key) {
        super(child, key, META_KEY);
    }

    @Override
    public Schema getSchema() {
        return Stroom.STROOM;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.<Index>asList(Indexes.META_KEY_META_KEY_NAME, Indexes.META_KEY_PRIMARY);
    }

    @Override
    public Identity<MetaKeyRecord, Integer> getIdentity() {
        return Keys.IDENTITY_META_KEY;
    }

    @Override
    public UniqueKey<MetaKeyRecord> getPrimaryKey() {
        return Keys.KEY_META_KEY_PRIMARY;
    }

    @Override
    public List<UniqueKey<MetaKeyRecord>> getKeys() {
        return Arrays.<UniqueKey<MetaKeyRecord>>asList(Keys.KEY_META_KEY_PRIMARY, Keys.KEY_META_KEY_META_KEY_NAME);
    }

    @Override
    public MetaKey as(String alias) {
        return new MetaKey(DSL.name(alias), this);
    }

    @Override
    public MetaKey as(Name alias) {
        return new MetaKey(alias, this);
    }

    /**
     * Rename this table
     */
    @Override
    public MetaKey rename(String name) {
        return new MetaKey(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public MetaKey rename(Name name) {
        return new MetaKey(name, null);
    }

    // -------------------------------------------------------------------------
    // Row3 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row3<Integer, String, Byte> fieldsRow() {
        return (Row3) super.fieldsRow();
    }
}
