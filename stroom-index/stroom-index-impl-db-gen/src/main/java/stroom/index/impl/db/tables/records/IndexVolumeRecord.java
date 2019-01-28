/*
 * This file is generated by jOOQ.
 */
package stroom.index.impl.db.tables.records;


import javax.annotation.Generated;

import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record15;
import org.jooq.Row15;
import org.jooq.impl.UpdatableRecordImpl;

import stroom.index.impl.db.tables.IndexVolume;


/**
 * This class is generated by jOOQ.
 */
@Generated(
    value = {
        "http://www.jooq.org",
        "jOOQ version:3.11.9"
    },
    comments = "This class is generated by jOOQ"
)
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class IndexVolumeRecord extends UpdatableRecordImpl<IndexVolumeRecord> implements Record15<Long, Byte, Long, String, Long, String, String, String, Byte, Byte, Long, Long, Long, Long, Long> {

    private static final long serialVersionUID = -790952868;

    /**
     * Setter for <code>stroom.index_volume.id</code>.
     */
    public void setId(Long value) {
        set(0, value);
    }

    /**
     * Getter for <code>stroom.index_volume.id</code>.
     */
    public Long getId() {
        return (Long) get(0);
    }

    /**
     * Setter for <code>stroom.index_volume.version</code>.
     */
    public void setVersion(Byte value) {
        set(1, value);
    }

    /**
     * Getter for <code>stroom.index_volume.version</code>.
     */
    public Byte getVersion() {
        return (Byte) get(1);
    }

    /**
     * Setter for <code>stroom.index_volume.create_time_ms</code>.
     */
    public void setCreateTimeMs(Long value) {
        set(2, value);
    }

    /**
     * Getter for <code>stroom.index_volume.create_time_ms</code>.
     */
    public Long getCreateTimeMs() {
        return (Long) get(2);
    }

    /**
     * Setter for <code>stroom.index_volume.create_user</code>.
     */
    public void setCreateUser(String value) {
        set(3, value);
    }

    /**
     * Getter for <code>stroom.index_volume.create_user</code>.
     */
    public String getCreateUser() {
        return (String) get(3);
    }

    /**
     * Setter for <code>stroom.index_volume.update_time_ms</code>.
     */
    public void setUpdateTimeMs(Long value) {
        set(4, value);
    }

    /**
     * Getter for <code>stroom.index_volume.update_time_ms</code>.
     */
    public Long getUpdateTimeMs() {
        return (Long) get(4);
    }

    /**
     * Setter for <code>stroom.index_volume.update_user</code>.
     */
    public void setUpdateUser(String value) {
        set(5, value);
    }

    /**
     * Getter for <code>stroom.index_volume.update_user</code>.
     */
    public String getUpdateUser() {
        return (String) get(5);
    }

    /**
     * Setter for <code>stroom.index_volume.node_name</code>.
     */
    public void setNodeName(String value) {
        set(6, value);
    }

    /**
     * Getter for <code>stroom.index_volume.node_name</code>.
     */
    public String getNodeName() {
        return (String) get(6);
    }

    /**
     * Setter for <code>stroom.index_volume.path</code>.
     */
    public void setPath(String value) {
        set(7, value);
    }

    /**
     * Getter for <code>stroom.index_volume.path</code>.
     */
    public String getPath() {
        return (String) get(7);
    }

    /**
     * Setter for <code>stroom.index_volume.index_status</code>.
     */
    public void setIndexStatus(Byte value) {
        set(8, value);
    }

    /**
     * Getter for <code>stroom.index_volume.index_status</code>.
     */
    public Byte getIndexStatus() {
        return (Byte) get(8);
    }

    /**
     * Setter for <code>stroom.index_volume.volume_type</code>.
     */
    public void setVolumeType(Byte value) {
        set(9, value);
    }

    /**
     * Getter for <code>stroom.index_volume.volume_type</code>.
     */
    public Byte getVolumeType() {
        return (Byte) get(9);
    }

    /**
     * Setter for <code>stroom.index_volume.bytes_limit</code>.
     */
    public void setBytesLimit(Long value) {
        set(10, value);
    }

    /**
     * Getter for <code>stroom.index_volume.bytes_limit</code>.
     */
    public Long getBytesLimit() {
        return (Long) get(10);
    }

    /**
     * Setter for <code>stroom.index_volume.bytes_used</code>.
     */
    public void setBytesUsed(Long value) {
        set(11, value);
    }

    /**
     * Getter for <code>stroom.index_volume.bytes_used</code>.
     */
    public Long getBytesUsed() {
        return (Long) get(11);
    }

    /**
     * Setter for <code>stroom.index_volume.bytes_free</code>.
     */
    public void setBytesFree(Long value) {
        set(12, value);
    }

    /**
     * Getter for <code>stroom.index_volume.bytes_free</code>.
     */
    public Long getBytesFree() {
        return (Long) get(12);
    }

    /**
     * Setter for <code>stroom.index_volume.bytes_total</code>.
     */
    public void setBytesTotal(Long value) {
        set(13, value);
    }

    /**
     * Getter for <code>stroom.index_volume.bytes_total</code>.
     */
    public Long getBytesTotal() {
        return (Long) get(13);
    }

    /**
     * Setter for <code>stroom.index_volume.status_ms</code>.
     */
    public void setStatusMs(Long value) {
        set(14, value);
    }

    /**
     * Getter for <code>stroom.index_volume.status_ms</code>.
     */
    public Long getStatusMs() {
        return (Long) get(14);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public Record1<Long> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record15 type implementation
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public Row15<Long, Byte, Long, String, Long, String, String, String, Byte, Byte, Long, Long, Long, Long, Long> fieldsRow() {
        return (Row15) super.fieldsRow();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Row15<Long, Byte, Long, String, Long, String, String, String, Byte, Byte, Long, Long, Long, Long, Long> valuesRow() {
        return (Row15) super.valuesRow();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<Long> field1() {
        return IndexVolume.INDEX_VOLUME.ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<Byte> field2() {
        return IndexVolume.INDEX_VOLUME.VERSION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<Long> field3() {
        return IndexVolume.INDEX_VOLUME.CREATE_TIME_MS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<String> field4() {
        return IndexVolume.INDEX_VOLUME.CREATE_USER;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<Long> field5() {
        return IndexVolume.INDEX_VOLUME.UPDATE_TIME_MS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<String> field6() {
        return IndexVolume.INDEX_VOLUME.UPDATE_USER;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<String> field7() {
        return IndexVolume.INDEX_VOLUME.NODE_NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<String> field8() {
        return IndexVolume.INDEX_VOLUME.PATH;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<Byte> field9() {
        return IndexVolume.INDEX_VOLUME.INDEX_STATUS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<Byte> field10() {
        return IndexVolume.INDEX_VOLUME.VOLUME_TYPE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<Long> field11() {
        return IndexVolume.INDEX_VOLUME.BYTES_LIMIT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<Long> field12() {
        return IndexVolume.INDEX_VOLUME.BYTES_USED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<Long> field13() {
        return IndexVolume.INDEX_VOLUME.BYTES_FREE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<Long> field14() {
        return IndexVolume.INDEX_VOLUME.BYTES_TOTAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Field<Long> field15() {
        return IndexVolume.INDEX_VOLUME.STATUS_MS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long component1() {
        return getId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Byte component2() {
        return getVersion();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long component3() {
        return getCreateTimeMs();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String component4() {
        return getCreateUser();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long component5() {
        return getUpdateTimeMs();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String component6() {
        return getUpdateUser();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String component7() {
        return getNodeName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String component8() {
        return getPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Byte component9() {
        return getIndexStatus();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Byte component10() {
        return getVolumeType();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long component11() {
        return getBytesLimit();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long component12() {
        return getBytesUsed();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long component13() {
        return getBytesFree();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long component14() {
        return getBytesTotal();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long component15() {
        return getStatusMs();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long value1() {
        return getId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Byte value2() {
        return getVersion();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long value3() {
        return getCreateTimeMs();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String value4() {
        return getCreateUser();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long value5() {
        return getUpdateTimeMs();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String value6() {
        return getUpdateUser();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String value7() {
        return getNodeName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String value8() {
        return getPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Byte value9() {
        return getIndexStatus();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Byte value10() {
        return getVolumeType();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long value11() {
        return getBytesLimit();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long value12() {
        return getBytesUsed();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long value13() {
        return getBytesFree();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long value14() {
        return getBytesTotal();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long value15() {
        return getStatusMs();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value1(Long value) {
        setId(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value2(Byte value) {
        setVersion(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value3(Long value) {
        setCreateTimeMs(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value4(String value) {
        setCreateUser(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value5(Long value) {
        setUpdateTimeMs(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value6(String value) {
        setUpdateUser(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value7(String value) {
        setNodeName(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value8(String value) {
        setPath(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value9(Byte value) {
        setIndexStatus(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value10(Byte value) {
        setVolumeType(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value11(Long value) {
        setBytesLimit(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value12(Long value) {
        setBytesUsed(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value13(Long value) {
        setBytesFree(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value14(Long value) {
        setBytesTotal(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord value15(Long value) {
        setStatusMs(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndexVolumeRecord values(Long value1, Byte value2, Long value3, String value4, Long value5, String value6, String value7, String value8, Byte value9, Byte value10, Long value11, Long value12, Long value13, Long value14, Long value15) {
        value1(value1);
        value2(value2);
        value3(value3);
        value4(value4);
        value5(value5);
        value6(value6);
        value7(value7);
        value8(value8);
        value9(value9);
        value10(value10);
        value11(value11);
        value12(value12);
        value13(value13);
        value14(value14);
        value15(value15);
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached IndexVolumeRecord
     */
    public IndexVolumeRecord() {
        super(IndexVolume.INDEX_VOLUME);
    }

    /**
     * Create a detached, initialised IndexVolumeRecord
     */
    public IndexVolumeRecord(Long id, Byte version, Long createTimeMs, String createUser, Long updateTimeMs, String updateUser, String nodeName, String path, Byte indexStatus, Byte volumeType, Long bytesLimit, Long bytesUsed, Long bytesFree, Long bytesTotal, Long statusMs) {
        super(IndexVolume.INDEX_VOLUME);

        set(0, id);
        set(1, version);
        set(2, createTimeMs);
        set(3, createUser);
        set(4, updateTimeMs);
        set(5, updateUser);
        set(6, nodeName);
        set(7, path);
        set(8, indexStatus);
        set(9, volumeType);
        set(10, bytesLimit);
        set(11, bytesUsed);
        set(12, bytesFree);
        set(13, bytesTotal);
        set(14, statusMs);
    }
}
