/*
 * This file is generated by jOOQ.
 */
package stroom.index.impl.db.jooq.tables;


import java.util.Arrays;
import java.util.List;

import javax.annotation.processing.Generated;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Identity;
import org.jooq.Index;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Row15;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.TableImpl;

import stroom.index.impl.db.jooq.Indexes;
import stroom.index.impl.db.jooq.Keys;
import stroom.index.impl.db.jooq.Stroom;
import stroom.index.impl.db.jooq.tables.records.IndexVolumeRecord;


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
public class IndexVolume extends TableImpl<IndexVolumeRecord> {

    private static final long serialVersionUID = -424494784;

    /**
     * The reference instance of <code>stroom.index_volume</code>
     */
    public static final IndexVolume INDEX_VOLUME = new IndexVolume();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<IndexVolumeRecord> getRecordType() {
        return IndexVolumeRecord.class;
    }

    /**
     * The column <code>stroom.index_volume.id</code>.
     */
    public final TableField<IndexVolumeRecord, Integer> ID = createField(DSL.name("id"), org.jooq.impl.SQLDataType.INTEGER.nullable(false).identity(true), this, "");

    /**
     * The column <code>stroom.index_volume.version</code>.
     */
    public final TableField<IndexVolumeRecord, Integer> VERSION = createField(DSL.name("version"), org.jooq.impl.SQLDataType.INTEGER.nullable(false), this, "");

    /**
     * The column <code>stroom.index_volume.create_time_ms</code>.
     */
    public final TableField<IndexVolumeRecord, Long> CREATE_TIME_MS = createField(DSL.name("create_time_ms"), org.jooq.impl.SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * The column <code>stroom.index_volume.create_user</code>.
     */
    public final TableField<IndexVolumeRecord, String> CREATE_USER = createField(DSL.name("create_user"), org.jooq.impl.SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.index_volume.update_time_ms</code>.
     */
    public final TableField<IndexVolumeRecord, Long> UPDATE_TIME_MS = createField(DSL.name("update_time_ms"), org.jooq.impl.SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * The column <code>stroom.index_volume.update_user</code>.
     */
    public final TableField<IndexVolumeRecord, String> UPDATE_USER = createField(DSL.name("update_user"), org.jooq.impl.SQLDataType.VARCHAR(255).nullable(false), this, "");

    /**
     * The column <code>stroom.index_volume.node_name</code>.
     */
    public final TableField<IndexVolumeRecord, String> NODE_NAME = createField(DSL.name("node_name"), org.jooq.impl.SQLDataType.VARCHAR(255), this, "");

    /**
     * The column <code>stroom.index_volume.path</code>.
     */
    public final TableField<IndexVolumeRecord, String> PATH = createField(DSL.name("path"), org.jooq.impl.SQLDataType.VARCHAR(255), this, "");

    /**
     * The column <code>stroom.index_volume.fk_index_volume_group_id</code>.
     */
    public final TableField<IndexVolumeRecord, Integer> FK_INDEX_VOLUME_GROUP_ID = createField(DSL.name("fk_index_volume_group_id"), org.jooq.impl.SQLDataType.INTEGER.nullable(false), this, "");

    /**
     * The column <code>stroom.index_volume.state</code>.
     */
    public final TableField<IndexVolumeRecord, Byte> STATE = createField(DSL.name("state"), org.jooq.impl.SQLDataType.TINYINT, this, "");

    /**
     * The column <code>stroom.index_volume.bytes_limit</code>.
     */
    public final TableField<IndexVolumeRecord, Long> BYTES_LIMIT = createField(DSL.name("bytes_limit"), org.jooq.impl.SQLDataType.BIGINT, this, "");

    /**
     * The column <code>stroom.index_volume.bytes_used</code>.
     */
    public final TableField<IndexVolumeRecord, Long> BYTES_USED = createField(DSL.name("bytes_used"), org.jooq.impl.SQLDataType.BIGINT, this, "");

    /**
     * The column <code>stroom.index_volume.bytes_free</code>.
     */
    public final TableField<IndexVolumeRecord, Long> BYTES_FREE = createField(DSL.name("bytes_free"), org.jooq.impl.SQLDataType.BIGINT, this, "");

    /**
     * The column <code>stroom.index_volume.bytes_total</code>.
     */
    public final TableField<IndexVolumeRecord, Long> BYTES_TOTAL = createField(DSL.name("bytes_total"), org.jooq.impl.SQLDataType.BIGINT, this, "");

    /**
     * The column <code>stroom.index_volume.status_ms</code>.
     */
    public final TableField<IndexVolumeRecord, Long> STATUS_MS = createField(DSL.name("status_ms"), org.jooq.impl.SQLDataType.BIGINT, this, "");

    /**
     * Create a <code>stroom.index_volume</code> table reference
     */
    public IndexVolume() {
        this(DSL.name("index_volume"), null);
    }

    /**
     * Create an aliased <code>stroom.index_volume</code> table reference
     */
    public IndexVolume(String alias) {
        this(DSL.name(alias), INDEX_VOLUME);
    }

    /**
     * Create an aliased <code>stroom.index_volume</code> table reference
     */
    public IndexVolume(Name alias) {
        this(alias, INDEX_VOLUME);
    }

    private IndexVolume(Name alias, Table<IndexVolumeRecord> aliased) {
        this(alias, aliased, null);
    }

    private IndexVolume(Name alias, Table<IndexVolumeRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""));
    }

    public <O extends Record> IndexVolume(Table<O> child, ForeignKey<O, IndexVolumeRecord> key) {
        super(child, key, INDEX_VOLUME);
    }

    @Override
    public Schema getSchema() {
        return Stroom.STROOM;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.<Index>asList(Indexes.INDEX_VOLUME_NODE_NAME_PATH, Indexes.INDEX_VOLUME_PRIMARY);
    }

    @Override
    public Identity<IndexVolumeRecord, Integer> getIdentity() {
        return Keys.IDENTITY_INDEX_VOLUME;
    }

    @Override
    public UniqueKey<IndexVolumeRecord> getPrimaryKey() {
        return Keys.KEY_INDEX_VOLUME_PRIMARY;
    }

    @Override
    public List<UniqueKey<IndexVolumeRecord>> getKeys() {
        return Arrays.<UniqueKey<IndexVolumeRecord>>asList(Keys.KEY_INDEX_VOLUME_PRIMARY, Keys.KEY_INDEX_VOLUME_NODE_NAME_PATH);
    }

    @Override
    public List<ForeignKey<IndexVolumeRecord, ?>> getReferences() {
        return Arrays.<ForeignKey<IndexVolumeRecord, ?>>asList(Keys.INDEX_VOLUME_GROUP_LINK_FK_GROUP_NAME);
    }

    public IndexVolumeGroup indexVolumeGroup() {
        return new IndexVolumeGroup(this, Keys.INDEX_VOLUME_GROUP_LINK_FK_GROUP_NAME);
    }

    @Override
    public TableField<IndexVolumeRecord, Integer> getRecordVersion() {
        return VERSION;
    }

    @Override
    public IndexVolume as(String alias) {
        return new IndexVolume(DSL.name(alias), this);
    }

    @Override
    public IndexVolume as(Name alias) {
        return new IndexVolume(alias, this);
    }

    /**
     * Rename this table
     */
    @Override
    public IndexVolume rename(String name) {
        return new IndexVolume(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public IndexVolume rename(Name name) {
        return new IndexVolume(name, null);
    }

    // -------------------------------------------------------------------------
    // Row15 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row15<Integer, Integer, Long, String, Long, String, String, String, Integer, Byte, Long, Long, Long, Long, Long> fieldsRow() {
        return (Row15) super.fieldsRow();
    }
}
