/*
 * This file is generated by jOOQ.
 */
package stroom.proxy.repo.db.jooq.tables.records;


import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record7;
import org.jooq.Row7;
import org.jooq.impl.UpdatableRecordImpl;

import stroom.proxy.repo.db.jooq.tables.Source;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class SourceRecord extends UpdatableRecordImpl<SourceRecord> implements Record7<Long, String, String, String, Long, Boolean, Boolean> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>source.id</code>.
     */
    public void setId(Long value) {
        set(0, value);
    }

    /**
     * Getter for <code>source.id</code>.
     */
    public Long getId() {
        return (Long) get(0);
    }

    /**
     * Setter for <code>source.path</code>.
     */
    public void setPath(String value) {
        set(1, value);
    }

    /**
     * Getter for <code>source.path</code>.
     */
    public String getPath() {
        return (String) get(1);
    }

    /**
     * Setter for <code>source.feed_name</code>.
     */
    public void setFeedName(String value) {
        set(2, value);
    }

    /**
     * Getter for <code>source.feed_name</code>.
     */
    public String getFeedName() {
        return (String) get(2);
    }

    /**
     * Setter for <code>source.type_name</code>.
     */
    public void setTypeName(String value) {
        set(3, value);
    }

    /**
     * Getter for <code>source.type_name</code>.
     */
    public String getTypeName() {
        return (String) get(3);
    }

    /**
     * Setter for <code>source.last_modified_time_ms</code>.
     */
    public void setLastModifiedTimeMs(Long value) {
        set(4, value);
    }

    /**
     * Getter for <code>source.last_modified_time_ms</code>.
     */
    public Long getLastModifiedTimeMs() {
        return (Long) get(4);
    }

    /**
     * Setter for <code>source.examined</code>.
     */
    public void setExamined(Boolean value) {
        set(5, value);
    }

    /**
     * Getter for <code>source.examined</code>.
     */
    public Boolean getExamined() {
        return (Boolean) get(5);
    }

    /**
     * Setter for <code>source.forward_error</code>.
     */
    public void setForwardError(Boolean value) {
        set(6, value);
    }

    /**
     * Getter for <code>source.forward_error</code>.
     */
    public Boolean getForwardError() {
        return (Boolean) get(6);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<Long> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record7 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row7<Long, String, String, String, Long, Boolean, Boolean> fieldsRow() {
        return (Row7) super.fieldsRow();
    }

    @Override
    public Row7<Long, String, String, String, Long, Boolean, Boolean> valuesRow() {
        return (Row7) super.valuesRow();
    }

    @Override
    public Field<Long> field1() {
        return Source.SOURCE.ID;
    }

    @Override
    public Field<String> field2() {
        return Source.SOURCE.PATH;
    }

    @Override
    public Field<String> field3() {
        return Source.SOURCE.FEED_NAME;
    }

    @Override
    public Field<String> field4() {
        return Source.SOURCE.TYPE_NAME;
    }

    @Override
    public Field<Long> field5() {
        return Source.SOURCE.LAST_MODIFIED_TIME_MS;
    }

    @Override
    public Field<Boolean> field6() {
        return Source.SOURCE.EXAMINED;
    }

    @Override
    public Field<Boolean> field7() {
        return Source.SOURCE.FORWARD_ERROR;
    }

    @Override
    public Long component1() {
        return getId();
    }

    @Override
    public String component2() {
        return getPath();
    }

    @Override
    public String component3() {
        return getFeedName();
    }

    @Override
    public String component4() {
        return getTypeName();
    }

    @Override
    public Long component5() {
        return getLastModifiedTimeMs();
    }

    @Override
    public Boolean component6() {
        return getExamined();
    }

    @Override
    public Boolean component7() {
        return getForwardError();
    }

    @Override
    public Long value1() {
        return getId();
    }

    @Override
    public String value2() {
        return getPath();
    }

    @Override
    public String value3() {
        return getFeedName();
    }

    @Override
    public String value4() {
        return getTypeName();
    }

    @Override
    public Long value5() {
        return getLastModifiedTimeMs();
    }

    @Override
    public Boolean value6() {
        return getExamined();
    }

    @Override
    public Boolean value7() {
        return getForwardError();
    }

    @Override
    public SourceRecord value1(Long value) {
        setId(value);
        return this;
    }

    @Override
    public SourceRecord value2(String value) {
        setPath(value);
        return this;
    }

    @Override
    public SourceRecord value3(String value) {
        setFeedName(value);
        return this;
    }

    @Override
    public SourceRecord value4(String value) {
        setTypeName(value);
        return this;
    }

    @Override
    public SourceRecord value5(Long value) {
        setLastModifiedTimeMs(value);
        return this;
    }

    @Override
    public SourceRecord value6(Boolean value) {
        setExamined(value);
        return this;
    }

    @Override
    public SourceRecord value7(Boolean value) {
        setForwardError(value);
        return this;
    }

    @Override
    public SourceRecord values(Long value1, String value2, String value3, String value4, Long value5, Boolean value6, Boolean value7) {
        value1(value1);
        value2(value2);
        value3(value3);
        value4(value4);
        value5(value5);
        value6(value6);
        value7(value7);
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached SourceRecord
     */
    public SourceRecord() {
        super(Source.SOURCE);
    }

    /**
     * Create a detached, initialised SourceRecord
     */
    public SourceRecord(Long id, String path, String feedName, String typeName, Long lastModifiedTimeMs, Boolean examined, Boolean forwardError) {
        super(Source.SOURCE);

        setId(id);
        setPath(path);
        setFeedName(feedName);
        setTypeName(typeName);
        setLastModifiedTimeMs(lastModifiedTimeMs);
        setExamined(examined);
        setForwardError(forwardError);
    }
}
