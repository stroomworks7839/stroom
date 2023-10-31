/*
 * This file is generated by jOOQ.
 */
package stroom.security.impl.db.jooq.tables;


import stroom.security.impl.db.jooq.Keys;
import stroom.security.impl.db.jooq.Stroom;
import stroom.security.impl.db.jooq.tables.records.AppPermissionRecord;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Identity;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Row3;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

import java.util.Arrays;
import java.util.List;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class AppPermission extends TableImpl<AppPermissionRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>stroom.app_permission</code>
     */
    public static final AppPermission APP_PERMISSION = new AppPermission();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<AppPermissionRecord> getRecordType() {
        return AppPermissionRecord.class;
    }

    /**
     * The column <code>stroom.app_permission.id</code>.
     */
    public final TableField<AppPermissionRecord, Long> ID = createField(DSL.name("id"), SQLDataType.BIGINT.nullable(false).identity(true), this, "");

    /**
     * The column <code>stroom.app_permission.user_uuid</code>.
     */
    public final TableField<AppPermissionRecord, String> USER_UUID = createField(DSL.name("user_uuid"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.app_permission.permission</code>.
     */
    public final TableField<AppPermissionRecord, String> PERMISSION = createField(DSL.name("permission"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    private AppPermission(Name alias, Table<AppPermissionRecord> aliased) {
        this(alias, aliased, null);
    }

    private AppPermission(Name alias, Table<AppPermissionRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>stroom.app_permission</code> table reference
     */
    public AppPermission(String alias) {
        this(DSL.name(alias), APP_PERMISSION);
    }

    /**
     * Create an aliased <code>stroom.app_permission</code> table reference
     */
    public AppPermission(Name alias) {
        this(alias, APP_PERMISSION);
    }

    /**
     * Create a <code>stroom.app_permission</code> table reference
     */
    public AppPermission() {
        this(DSL.name("app_permission"), null);
    }

    public <O extends Record> AppPermission(Table<O> child, ForeignKey<O, AppPermissionRecord> key) {
        super(child, key, APP_PERMISSION);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Stroom.STROOM;
    }

    @Override
    public Identity<AppPermissionRecord, Long> getIdentity() {
        return (Identity<AppPermissionRecord, Long>) super.getIdentity();
    }

    @Override
    public UniqueKey<AppPermissionRecord> getPrimaryKey() {
        return Keys.KEY_APP_PERMISSION_PRIMARY;
    }

    @Override
    public List<UniqueKey<AppPermissionRecord>> getUniqueKeys() {
        return Arrays.asList(Keys.KEY_APP_PERMISSION_APP_PERMISSION_USER_UUID_PERMISSION_IDX);
    }

    @Override
    public List<ForeignKey<AppPermissionRecord, ?>> getReferences() {
        return Arrays.asList(Keys.APP_PERMISSION_USER_UUID);
    }

    private transient StroomUser _stroomUser;

    /**
     * Get the implicit join path to the <code>stroom.stroom_user</code> table.
     */
    public StroomUser stroomUser() {
        if (_stroomUser == null)
            _stroomUser = new StroomUser(this, Keys.APP_PERMISSION_USER_UUID);

        return _stroomUser;
    }

    @Override
    public AppPermission as(String alias) {
        return new AppPermission(DSL.name(alias), this);
    }

    @Override
    public AppPermission as(Name alias) {
        return new AppPermission(alias, this);
    }

    /**
     * Rename this table
     */
    @Override
    public AppPermission rename(String name) {
        return new AppPermission(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public AppPermission rename(Name name) {
        return new AppPermission(name, null);
    }

    // -------------------------------------------------------------------------
    // Row3 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row3<Long, String, String> fieldsRow() {
        return (Row3) super.fieldsRow();
    }
}
