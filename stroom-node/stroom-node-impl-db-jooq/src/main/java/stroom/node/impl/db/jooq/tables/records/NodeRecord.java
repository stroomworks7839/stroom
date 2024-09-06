/*
 * This file is generated by jOOQ.
 */
package stroom.node.impl.db.jooq.tables.records;


import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record12;
import org.jooq.Row12;
import org.jooq.impl.UpdatableRecordImpl;

import stroom.node.impl.db.jooq.tables.Node;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class NodeRecord extends UpdatableRecordImpl<NodeRecord> implements Record12<Integer, Integer, Long, String, Long, String, String, String, Short, Boolean, String, Long> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>stroom.node.id</code>.
     */
    public void setId(Integer value) {
        set(0, value);
    }

    /**
     * Getter for <code>stroom.node.id</code>.
     */
    public Integer getId() {
        return (Integer) get(0);
    }

    /**
     * Setter for <code>stroom.node.version</code>.
     */
    public void setVersion(Integer value) {
        set(1, value);
    }

    /**
     * Getter for <code>stroom.node.version</code>.
     */
    public Integer getVersion() {
        return (Integer) get(1);
    }

    /**
     * Setter for <code>stroom.node.create_time_ms</code>.
     */
    public void setCreateTimeMs(Long value) {
        set(2, value);
    }

    /**
     * Getter for <code>stroom.node.create_time_ms</code>.
     */
    public Long getCreateTimeMs() {
        return (Long) get(2);
    }

    /**
     * Setter for <code>stroom.node.create_user</code>.
     */
    public void setCreateUser(String value) {
        set(3, value);
    }

    /**
     * Getter for <code>stroom.node.create_user</code>.
     */
    public String getCreateUser() {
        return (String) get(3);
    }

    /**
     * Setter for <code>stroom.node.update_time_ms</code>.
     */
    public void setUpdateTimeMs(Long value) {
        set(4, value);
    }

    /**
     * Getter for <code>stroom.node.update_time_ms</code>.
     */
    public Long getUpdateTimeMs() {
        return (Long) get(4);
    }

    /**
     * Setter for <code>stroom.node.update_user</code>.
     */
    public void setUpdateUser(String value) {
        set(5, value);
    }

    /**
     * Getter for <code>stroom.node.update_user</code>.
     */
    public String getUpdateUser() {
        return (String) get(5);
    }

    /**
     * Setter for <code>stroom.node.url</code>.
     */
    public void setUrl(String value) {
        set(6, value);
    }

    /**
     * Getter for <code>stroom.node.url</code>.
     */
    public String getUrl() {
        return (String) get(6);
    }

    /**
     * Setter for <code>stroom.node.name</code>.
     */
    public void setName(String value) {
        set(7, value);
    }

    /**
     * Getter for <code>stroom.node.name</code>.
     */
    public String getName() {
        return (String) get(7);
    }

    /**
     * Setter for <code>stroom.node.priority</code>.
     */
    public void setPriority(Short value) {
        set(8, value);
    }

    /**
     * Getter for <code>stroom.node.priority</code>.
     */
    public Short getPriority() {
        return (Short) get(8);
    }

    /**
     * Setter for <code>stroom.node.enabled</code>.
     */
    public void setEnabled(Boolean value) {
        set(9, value);
    }

    /**
     * Getter for <code>stroom.node.enabled</code>.
     */
    public Boolean getEnabled() {
        return (Boolean) get(9);
    }

    /**
     * Setter for <code>stroom.node.build_version</code>.
     */
    public void setBuildVersion(String value) {
        set(10, value);
    }

    /**
     * Getter for <code>stroom.node.build_version</code>.
     */
    public String getBuildVersion() {
        return (String) get(10);
    }

    /**
     * Setter for <code>stroom.node.last_boot_ms</code>.
     */
    public void setLastBootMs(Long value) {
        set(11, value);
    }

    /**
     * Getter for <code>stroom.node.last_boot_ms</code>.
     */
    public Long getLastBootMs() {
        return (Long) get(11);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<Integer> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record12 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row12<Integer, Integer, Long, String, Long, String, String, String, Short, Boolean, String, Long> fieldsRow() {
        return (Row12) super.fieldsRow();
    }

    @Override
    public Row12<Integer, Integer, Long, String, Long, String, String, String, Short, Boolean, String, Long> valuesRow() {
        return (Row12) super.valuesRow();
    }

    @Override
    public Field<Integer> field1() {
        return Node.NODE.ID;
    }

    @Override
    public Field<Integer> field2() {
        return Node.NODE.VERSION;
    }

    @Override
    public Field<Long> field3() {
        return Node.NODE.CREATE_TIME_MS;
    }

    @Override
    public Field<String> field4() {
        return Node.NODE.CREATE_USER;
    }

    @Override
    public Field<Long> field5() {
        return Node.NODE.UPDATE_TIME_MS;
    }

    @Override
    public Field<String> field6() {
        return Node.NODE.UPDATE_USER;
    }

    @Override
    public Field<String> field7() {
        return Node.NODE.URL;
    }

    @Override
    public Field<String> field8() {
        return Node.NODE.NAME;
    }

    @Override
    public Field<Short> field9() {
        return Node.NODE.PRIORITY;
    }

    @Override
    public Field<Boolean> field10() {
        return Node.NODE.ENABLED;
    }

    @Override
    public Field<String> field11() {
        return Node.NODE.BUILD_VERSION;
    }

    @Override
    public Field<Long> field12() {
        return Node.NODE.LAST_BOOT_MS;
    }

    @Override
    public Integer component1() {
        return getId();
    }

    @Override
    public Integer component2() {
        return getVersion();
    }

    @Override
    public Long component3() {
        return getCreateTimeMs();
    }

    @Override
    public String component4() {
        return getCreateUser();
    }

    @Override
    public Long component5() {
        return getUpdateTimeMs();
    }

    @Override
    public String component6() {
        return getUpdateUser();
    }

    @Override
    public String component7() {
        return getUrl();
    }

    @Override
    public String component8() {
        return getName();
    }

    @Override
    public Short component9() {
        return getPriority();
    }

    @Override
    public Boolean component10() {
        return getEnabled();
    }

    @Override
    public String component11() {
        return getBuildVersion();
    }

    @Override
    public Long component12() {
        return getLastBootMs();
    }

    @Override
    public Integer value1() {
        return getId();
    }

    @Override
    public Integer value2() {
        return getVersion();
    }

    @Override
    public Long value3() {
        return getCreateTimeMs();
    }

    @Override
    public String value4() {
        return getCreateUser();
    }

    @Override
    public Long value5() {
        return getUpdateTimeMs();
    }

    @Override
    public String value6() {
        return getUpdateUser();
    }

    @Override
    public String value7() {
        return getUrl();
    }

    @Override
    public String value8() {
        return getName();
    }

    @Override
    public Short value9() {
        return getPriority();
    }

    @Override
    public Boolean value10() {
        return getEnabled();
    }

    @Override
    public String value11() {
        return getBuildVersion();
    }

    @Override
    public Long value12() {
        return getLastBootMs();
    }

    @Override
    public NodeRecord value1(Integer value) {
        setId(value);
        return this;
    }

    @Override
    public NodeRecord value2(Integer value) {
        setVersion(value);
        return this;
    }

    @Override
    public NodeRecord value3(Long value) {
        setCreateTimeMs(value);
        return this;
    }

    @Override
    public NodeRecord value4(String value) {
        setCreateUser(value);
        return this;
    }

    @Override
    public NodeRecord value5(Long value) {
        setUpdateTimeMs(value);
        return this;
    }

    @Override
    public NodeRecord value6(String value) {
        setUpdateUser(value);
        return this;
    }

    @Override
    public NodeRecord value7(String value) {
        setUrl(value);
        return this;
    }

    @Override
    public NodeRecord value8(String value) {
        setName(value);
        return this;
    }

    @Override
    public NodeRecord value9(Short value) {
        setPriority(value);
        return this;
    }

    @Override
    public NodeRecord value10(Boolean value) {
        setEnabled(value);
        return this;
    }

    @Override
    public NodeRecord value11(String value) {
        setBuildVersion(value);
        return this;
    }

    @Override
    public NodeRecord value12(Long value) {
        setLastBootMs(value);
        return this;
    }

    @Override
    public NodeRecord values(Integer value1, Integer value2, Long value3, String value4, Long value5, String value6, String value7, String value8, Short value9, Boolean value10, String value11, Long value12) {
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
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached NodeRecord
     */
    public NodeRecord() {
        super(Node.NODE);
    }

    /**
     * Create a detached, initialised NodeRecord
     */
    public NodeRecord(Integer id, Integer version, Long createTimeMs, String createUser, Long updateTimeMs, String updateUser, String url, String name, Short priority, Boolean enabled, String buildVersion, Long lastBootMs) {
        super(Node.NODE);

        setId(id);
        setVersion(version);
        setCreateTimeMs(createTimeMs);
        setCreateUser(createUser);
        setUpdateTimeMs(updateTimeMs);
        setUpdateUser(updateUser);
        setUrl(url);
        setName(name);
        setPriority(priority);
        setEnabled(enabled);
        setBuildVersion(buildVersion);
        setLastBootMs(lastBootMs);
        resetChangedOnNotNull();
    }
}
