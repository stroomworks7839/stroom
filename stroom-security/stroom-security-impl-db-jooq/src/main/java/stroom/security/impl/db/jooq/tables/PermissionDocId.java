/*
 * This file is generated by jOOQ.
 */
package stroom.security.impl.db.jooq.tables;


import stroom.security.impl.db.jooq.Keys;
import stroom.security.impl.db.jooq.Stroom;
import stroom.security.impl.db.jooq.tables.records.PermissionDocIdRecord;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Function2;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row2;
import org.jooq.Schema;
import org.jooq.SelectField;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;
import org.jooq.types.UByte;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class PermissionDocId extends TableImpl<PermissionDocIdRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>stroom.permission_doc_id</code>
     */
    public static final PermissionDocId PERMISSION_DOC_ID = new PermissionDocId();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<PermissionDocIdRecord> getRecordType() {
        return PermissionDocIdRecord.class;
    }

    /**
     * The column <code>stroom.permission_doc_id.id</code>.
     */
    public final TableField<PermissionDocIdRecord, UByte> ID = createField(DSL.name("id"), SQLDataType.TINYINTUNSIGNED.nullable(false), this, "");

    /**
     * The column <code>stroom.permission_doc_id.permission</code>.
     */
    public final TableField<PermissionDocIdRecord, String> PERMISSION = createField(DSL.name("permission"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    private PermissionDocId(Name alias, Table<PermissionDocIdRecord> aliased) {
        this(alias, aliased, null);
    }

    private PermissionDocId(Name alias, Table<PermissionDocIdRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>stroom.permission_doc_id</code> table reference
     */
    public PermissionDocId(String alias) {
        this(DSL.name(alias), PERMISSION_DOC_ID);
    }

    /**
     * Create an aliased <code>stroom.permission_doc_id</code> table reference
     */
    public PermissionDocId(Name alias) {
        this(alias, PERMISSION_DOC_ID);
    }

    /**
     * Create a <code>stroom.permission_doc_id</code> table reference
     */
    public PermissionDocId() {
        this(DSL.name("permission_doc_id"), null);
    }

    public <O extends Record> PermissionDocId(Table<O> child, ForeignKey<O, PermissionDocIdRecord> key) {
        super(child, key, PERMISSION_DOC_ID);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Stroom.STROOM;
    }

    @Override
    public UniqueKey<PermissionDocIdRecord> getPrimaryKey() {
        return Keys.KEY_PERMISSION_DOC_ID_PRIMARY;
    }

    @Override
    public List<UniqueKey<PermissionDocIdRecord>> getUniqueKeys() {
        return Arrays.asList(Keys.KEY_PERMISSION_DOC_ID_PERMISSION_DOC_ID_PERMISSION_IDX);
    }

    @Override
    public PermissionDocId as(String alias) {
        return new PermissionDocId(DSL.name(alias), this);
    }

    @Override
    public PermissionDocId as(Name alias) {
        return new PermissionDocId(alias, this);
    }

    @Override
    public PermissionDocId as(Table<?> alias) {
        return new PermissionDocId(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public PermissionDocId rename(String name) {
        return new PermissionDocId(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public PermissionDocId rename(Name name) {
        return new PermissionDocId(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public PermissionDocId rename(Table<?> name) {
        return new PermissionDocId(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row2 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row2<UByte, String> fieldsRow() {
        return (Row2) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function2<? super UByte, ? super String, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function2<? super UByte, ? super String, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
