/*
 * This file is generated by jOOQ.
 */
package stroom.data.store.impl.fs.db.jooq.tables;


import stroom.data.store.impl.fs.db.jooq.Keys;
import stroom.data.store.impl.fs.db.jooq.Stroom;
import stroom.data.store.impl.fs.db.jooq.tables.records.FsVolumeRecord;

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

import java.util.Arrays;
import java.util.List;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class FsVolume extends TableImpl<FsVolumeRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>stroom.fs_volume</code>
     */
    public static final FsVolume FS_VOLUME = new FsVolume();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<FsVolumeRecord> getRecordType() {
        return FsVolumeRecord.class;
    }

    /**
     * The column <code>stroom.fs_volume.id</code>.
     */
    public final TableField<FsVolumeRecord, Integer> ID = createField(DSL.name("id"), SQLDataType.INTEGER.nullable(false).identity(true), this, "");

    /**
     * The column <code>stroom.fs_volume.version</code>.
     */
    public final TableField<FsVolumeRecord, Integer> VERSION = createField(DSL.name("version"), SQLDataType.INTEGER.nullable(false), this, "");

    /**
     * The column <code>stroom.fs_volume.create_time_ms</code>.
     */
    public final TableField<FsVolumeRecord, Long> CREATE_TIME_MS = createField(DSL.name("create_time_ms"), SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * The column <code>stroom.fs_volume.create_user</code>.
     */
    public final TableField<FsVolumeRecord, String> CREATE_USER = createField(DSL.name("create_user"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.fs_volume.update_time_ms</code>.
     */
    public final TableField<FsVolumeRecord, Long> UPDATE_TIME_MS = createField(DSL.name("update_time_ms"), SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * The column <code>stroom.fs_volume.update_user</code>.
     */
    public final TableField<FsVolumeRecord, String> UPDATE_USER = createField(DSL.name("update_user"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.fs_volume.path</code>.
     */
    public final TableField<FsVolumeRecord, String> PATH = createField(DSL.name("path"), SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.fs_volume.status</code>.
     */
    public final TableField<FsVolumeRecord, Byte> STATUS = createField(DSL.name("status"), SQLDataType.TINYINT.nullable(false), this, "");

    /**
     * The column <code>stroom.fs_volume.byte_limit</code>.
     */
    public final TableField<FsVolumeRecord, Long> BYTE_LIMIT = createField(DSL.name("byte_limit"), SQLDataType.BIGINT, this, "");

    /**
     * The column <code>stroom.fs_volume.fk_fs_volume_state_id</code>.
     */
    public final TableField<FsVolumeRecord, Integer> FK_FS_VOLUME_STATE_ID = createField(DSL.name("fk_fs_volume_state_id"), SQLDataType.INTEGER.nullable(false), this, "");

    /**
     * The column <code>stroom.fs_volume.volume_type</code>.
     */
    public final TableField<FsVolumeRecord, Integer> VOLUME_TYPE = createField(DSL.name("volume_type"), SQLDataType.INTEGER.nullable(false), this, "");

    /**
     * The column <code>stroom.fs_volume.data</code>.
     */
    public final TableField<FsVolumeRecord, byte[]> DATA = createField(DSL.name("data"), SQLDataType.BLOB, this, "");

    /**
     * The column <code>stroom.fs_volume.fk_fs_volume_group_id</code>.
     */
    public final TableField<FsVolumeRecord, Integer> FK_FS_VOLUME_GROUP_ID = createField(DSL.name("fk_fs_volume_group_id"), SQLDataType.INTEGER.nullable(false), this, "");

    private FsVolume(Name alias, Table<FsVolumeRecord> aliased) {
        this(alias, aliased, null);
    }

    private FsVolume(Name alias, Table<FsVolumeRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>stroom.fs_volume</code> table reference
     */
    public FsVolume(String alias) {
        this(DSL.name(alias), FS_VOLUME);
    }

    /**
     * Create an aliased <code>stroom.fs_volume</code> table reference
     */
    public FsVolume(Name alias) {
        this(alias, FS_VOLUME);
    }

    /**
     * Create a <code>stroom.fs_volume</code> table reference
     */
    public FsVolume() {
        this(DSL.name("fs_volume"), null);
    }

    public <O extends Record> FsVolume(Table<O> child, ForeignKey<O, FsVolumeRecord> key) {
        super(child, key, FS_VOLUME);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Stroom.STROOM;
    }

    @Override
    public Identity<FsVolumeRecord, Integer> getIdentity() {
        return (Identity<FsVolumeRecord, Integer>) super.getIdentity();
    }

    @Override
    public UniqueKey<FsVolumeRecord> getPrimaryKey() {
        return Keys.KEY_FS_VOLUME_PRIMARY;
    }

    @Override
    public List<UniqueKey<FsVolumeRecord>> getUniqueKeys() {
        return Arrays.asList(Keys.KEY_FS_VOLUME_PATH);
    }

    @Override
    public List<ForeignKey<FsVolumeRecord, ?>> getReferences() {
        return Arrays.asList(Keys.FS_VOLUME_FK_FS_VOLUME_STATE_ID, Keys.FS_VOLUME_GROUP_FK_FS_VOLUME_GROUP_ID);
    }

    private transient FsVolumeState _fsVolumeState;
    private transient FsVolumeGroup _fsVolumeGroup;

    /**
     * Get the implicit join path to the <code>stroom.fs_volume_state</code>
     * table.
     */
    public FsVolumeState fsVolumeState() {
        if (_fsVolumeState == null)
            _fsVolumeState = new FsVolumeState(this, Keys.FS_VOLUME_FK_FS_VOLUME_STATE_ID);

        return _fsVolumeState;
    }

    /**
     * Get the implicit join path to the <code>stroom.fs_volume_group</code>
     * table.
     */
    public FsVolumeGroup fsVolumeGroup() {
        if (_fsVolumeGroup == null)
            _fsVolumeGroup = new FsVolumeGroup(this, Keys.FS_VOLUME_GROUP_FK_FS_VOLUME_GROUP_ID);

        return _fsVolumeGroup;
    }

    @Override
    public TableField<FsVolumeRecord, Integer> getRecordVersion() {
        return VERSION;
    }

    @Override
    public FsVolume as(String alias) {
        return new FsVolume(DSL.name(alias), this);
    }

    @Override
    public FsVolume as(Name alias) {
        return new FsVolume(alias, this);
    }

    /**
     * Rename this table
     */
    @Override
    public FsVolume rename(String name) {
        return new FsVolume(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public FsVolume rename(Name name) {
        return new FsVolume(name, null);
    }

    // -------------------------------------------------------------------------
    // Row13 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row13<Integer, Integer, Long, String, Long, String, String, Byte, Long, Integer, Integer, byte[], Integer> fieldsRow() {
        return (Row13) super.fieldsRow();
    }
}
