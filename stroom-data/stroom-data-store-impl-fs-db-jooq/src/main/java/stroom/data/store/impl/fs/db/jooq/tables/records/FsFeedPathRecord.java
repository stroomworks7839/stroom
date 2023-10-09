/*
 * This file is generated by jOOQ.
 */
package stroom.data.store.impl.fs.db.jooq.tables.records;


import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record3;
import org.jooq.Row3;
import org.jooq.impl.UpdatableRecordImpl;

import stroom.data.store.impl.fs.db.jooq.tables.FsFeedPath;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class FsFeedPathRecord extends UpdatableRecordImpl<FsFeedPathRecord> implements Record3<Integer, String, String> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>stroom.fs_feed_path.id</code>.
     */
    public void setId(Integer value) {
        set(0, value);
    }

    /**
     * Getter for <code>stroom.fs_feed_path.id</code>.
     */
    public Integer getId() {
        return (Integer) get(0);
    }

    /**
     * Setter for <code>stroom.fs_feed_path.name</code>.
     */
    public void setName(String value) {
        set(1, value);
    }

    /**
     * Getter for <code>stroom.fs_feed_path.name</code>.
     */
    public String getName() {
        return (String) get(1);
    }

    /**
     * Setter for <code>stroom.fs_feed_path.path</code>.
     */
    public void setPath(String value) {
        set(2, value);
    }

    /**
     * Getter for <code>stroom.fs_feed_path.path</code>.
     */
    public String getPath() {
        return (String) get(2);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<Integer> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record3 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row3<Integer, String, String> fieldsRow() {
        return (Row3) super.fieldsRow();
    }

    @Override
    public Row3<Integer, String, String> valuesRow() {
        return (Row3) super.valuesRow();
    }

    @Override
    public Field<Integer> field1() {
        return FsFeedPath.FS_FEED_PATH.ID;
    }

    @Override
    public Field<String> field2() {
        return FsFeedPath.FS_FEED_PATH.NAME;
    }

    @Override
    public Field<String> field3() {
        return FsFeedPath.FS_FEED_PATH.PATH;
    }

    @Override
    public Integer component1() {
        return getId();
    }

    @Override
    public String component2() {
        return getName();
    }

    @Override
    public String component3() {
        return getPath();
    }

    @Override
    public Integer value1() {
        return getId();
    }

    @Override
    public String value2() {
        return getName();
    }

    @Override
    public String value3() {
        return getPath();
    }

    @Override
    public FsFeedPathRecord value1(Integer value) {
        setId(value);
        return this;
    }

    @Override
    public FsFeedPathRecord value2(String value) {
        setName(value);
        return this;
    }

    @Override
    public FsFeedPathRecord value3(String value) {
        setPath(value);
        return this;
    }

    @Override
    public FsFeedPathRecord values(Integer value1, String value2, String value3) {
        value1(value1);
        value2(value2);
        value3(value3);
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached FsFeedPathRecord
     */
    public FsFeedPathRecord() {
        super(FsFeedPath.FS_FEED_PATH);
    }

    /**
     * Create a detached, initialised FsFeedPathRecord
     */
    public FsFeedPathRecord(Integer id, String name, String path) {
        super(FsFeedPath.FS_FEED_PATH);

        setId(id);
        setName(name);
        setPath(path);
    }
}
