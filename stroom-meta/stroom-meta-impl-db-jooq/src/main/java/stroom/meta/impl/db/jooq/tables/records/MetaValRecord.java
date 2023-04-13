/*
 * This file is generated by jOOQ.
 */
package stroom.meta.impl.db.jooq.tables.records;


import stroom.meta.impl.db.jooq.tables.MetaVal;

import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record5;
import org.jooq.Row5;
import org.jooq.impl.UpdatableRecordImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class MetaValRecord extends UpdatableRecordImpl<MetaValRecord> implements Record5<Long, Long, Long, Integer, Long> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>stroom.meta_val.id</code>.
     */
    public void setId(Long value) {
        set(0, value);
    }

    /**
     * Getter for <code>stroom.meta_val.id</code>.
     */
    public Long getId() {
        return (Long) get(0);
    }

    /**
     * Setter for <code>stroom.meta_val.create_time</code>.
     */
    public void setCreateTime(Long value) {
        set(1, value);
    }

    /**
     * Getter for <code>stroom.meta_val.create_time</code>.
     */
    public Long getCreateTime() {
        return (Long) get(1);
    }

    /**
     * Setter for <code>stroom.meta_val.meta_id</code>.
     */
    public void setMetaId(Long value) {
        set(2, value);
    }

    /**
     * Getter for <code>stroom.meta_val.meta_id</code>.
     */
    public Long getMetaId() {
        return (Long) get(2);
    }

    /**
     * Setter for <code>stroom.meta_val.meta_key_id</code>.
     */
    public void setMetaKeyId(Integer value) {
        set(3, value);
    }

    /**
     * Getter for <code>stroom.meta_val.meta_key_id</code>.
     */
    public Integer getMetaKeyId() {
        return (Integer) get(3);
    }

    /**
     * Setter for <code>stroom.meta_val.val</code>.
     */
    public void setVal(Long value) {
        set(4, value);
    }

    /**
     * Getter for <code>stroom.meta_val.val</code>.
     */
    public Long getVal() {
        return (Long) get(4);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<Long> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record5 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row5<Long, Long, Long, Integer, Long> fieldsRow() {
        return (Row5) super.fieldsRow();
    }

    @Override
    public Row5<Long, Long, Long, Integer, Long> valuesRow() {
        return (Row5) super.valuesRow();
    }

    @Override
    public Field<Long> field1() {
        return MetaVal.META_VAL.ID;
    }

    @Override
    public Field<Long> field2() {
        return MetaVal.META_VAL.CREATE_TIME;
    }

    @Override
    public Field<Long> field3() {
        return MetaVal.META_VAL.META_ID;
    }

    @Override
    public Field<Integer> field4() {
        return MetaVal.META_VAL.META_KEY_ID;
    }

    @Override
    public Field<Long> field5() {
        return MetaVal.META_VAL.VAL;
    }

    @Override
    public Long component1() {
        return getId();
    }

    @Override
    public Long component2() {
        return getCreateTime();
    }

    @Override
    public Long component3() {
        return getMetaId();
    }

    @Override
    public Integer component4() {
        return getMetaKeyId();
    }

    @Override
    public Long component5() {
        return getVal();
    }

    @Override
    public Long value1() {
        return getId();
    }

    @Override
    public Long value2() {
        return getCreateTime();
    }

    @Override
    public Long value3() {
        return getMetaId();
    }

    @Override
    public Integer value4() {
        return getMetaKeyId();
    }

    @Override
    public Long value5() {
        return getVal();
    }

    @Override
    public MetaValRecord value1(Long value) {
        setId(value);
        return this;
    }

    @Override
    public MetaValRecord value2(Long value) {
        setCreateTime(value);
        return this;
    }

    @Override
    public MetaValRecord value3(Long value) {
        setMetaId(value);
        return this;
    }

    @Override
    public MetaValRecord value4(Integer value) {
        setMetaKeyId(value);
        return this;
    }

    @Override
    public MetaValRecord value5(Long value) {
        setVal(value);
        return this;
    }

    @Override
    public MetaValRecord values(Long value1, Long value2, Long value3, Integer value4, Long value5) {
        value1(value1);
        value2(value2);
        value3(value3);
        value4(value4);
        value5(value5);
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached MetaValRecord
     */
    public MetaValRecord() {
        super(MetaVal.META_VAL);
    }

    /**
     * Create a detached, initialised MetaValRecord
     */
    public MetaValRecord(Long id, Long createTime, Long metaId, Integer metaKeyId, Long val) {
        super(MetaVal.META_VAL);

        setId(id);
        setCreateTime(createTime);
        setMetaId(metaId);
        setMetaKeyId(metaKeyId);
        setVal(val);
    }
}
