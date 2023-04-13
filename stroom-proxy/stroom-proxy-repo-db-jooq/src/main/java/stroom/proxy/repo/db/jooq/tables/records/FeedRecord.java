/*
 * This file is generated by jOOQ.
 */
package stroom.proxy.repo.db.jooq.tables.records;


import stroom.proxy.repo.db.jooq.tables.Feed;

import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record3;
import org.jooq.Row3;
import org.jooq.impl.UpdatableRecordImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class FeedRecord extends UpdatableRecordImpl<FeedRecord> implements Record3<Long, String, String> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>feed.id</code>.
     */
    public void setId(Long value) {
        set(0, value);
    }

    /**
     * Getter for <code>feed.id</code>.
     */
    public Long getId() {
        return (Long) get(0);
    }

    /**
     * Setter for <code>feed.feed_name</code>.
     */
    public void setFeedName(String value) {
        set(1, value);
    }

    /**
     * Getter for <code>feed.feed_name</code>.
     */
    public String getFeedName() {
        return (String) get(1);
    }

    /**
     * Setter for <code>feed.type_name</code>.
     */
    public void setTypeName(String value) {
        set(2, value);
    }

    /**
     * Getter for <code>feed.type_name</code>.
     */
    public String getTypeName() {
        return (String) get(2);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<Long> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record3 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row3<Long, String, String> fieldsRow() {
        return (Row3) super.fieldsRow();
    }

    @Override
    public Row3<Long, String, String> valuesRow() {
        return (Row3) super.valuesRow();
    }

    @Override
    public Field<Long> field1() {
        return Feed.FEED.ID;
    }

    @Override
    public Field<String> field2() {
        return Feed.FEED.FEED_NAME;
    }

    @Override
    public Field<String> field3() {
        return Feed.FEED.TYPE_NAME;
    }

    @Override
    public Long component1() {
        return getId();
    }

    @Override
    public String component2() {
        return getFeedName();
    }

    @Override
    public String component3() {
        return getTypeName();
    }

    @Override
    public Long value1() {
        return getId();
    }

    @Override
    public String value2() {
        return getFeedName();
    }

    @Override
    public String value3() {
        return getTypeName();
    }

    @Override
    public FeedRecord value1(Long value) {
        setId(value);
        return this;
    }

    @Override
    public FeedRecord value2(String value) {
        setFeedName(value);
        return this;
    }

    @Override
    public FeedRecord value3(String value) {
        setTypeName(value);
        return this;
    }

    @Override
    public FeedRecord values(Long value1, String value2, String value3) {
        value1(value1);
        value2(value2);
        value3(value3);
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached FeedRecord
     */
    public FeedRecord() {
        super(Feed.FEED);
    }

    /**
     * Create a detached, initialised FeedRecord
     */
    public FeedRecord(Long id, String feedName, String typeName) {
        super(Feed.FEED);

        setId(id);
        setFeedName(feedName);
        setTypeName(typeName);
    }
}
