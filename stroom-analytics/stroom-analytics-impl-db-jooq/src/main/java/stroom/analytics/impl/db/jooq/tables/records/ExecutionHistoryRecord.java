/*
 * This file is generated by jOOQ.
 */
package stroom.analytics.impl.db.jooq.tables.records;


import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record6;
import org.jooq.Row6;
import org.jooq.impl.UpdatableRecordImpl;

import stroom.analytics.impl.db.jooq.tables.ExecutionHistory;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class ExecutionHistoryRecord extends UpdatableRecordImpl<ExecutionHistoryRecord> implements Record6<Long, Integer, Long, Long, String, String> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>stroom.execution_history.id</code>.
     */
    public void setId(Long value) {
        set(0, value);
    }

    /**
     * Getter for <code>stroom.execution_history.id</code>.
     */
    public Long getId() {
        return (Long) get(0);
    }

    /**
     * Setter for
     * <code>stroom.execution_history.fk_execution_schedule_id</code>.
     */
    public void setFkExecutionScheduleId(Integer value) {
        set(1, value);
    }

    /**
     * Getter for
     * <code>stroom.execution_history.fk_execution_schedule_id</code>.
     */
    public Integer getFkExecutionScheduleId() {
        return (Integer) get(1);
    }

    /**
     * Setter for <code>stroom.execution_history.execution_time_ms</code>.
     */
    public void setExecutionTimeMs(Long value) {
        set(2, value);
    }

    /**
     * Getter for <code>stroom.execution_history.execution_time_ms</code>.
     */
    public Long getExecutionTimeMs() {
        return (Long) get(2);
    }

    /**
     * Setter for
     * <code>stroom.execution_history.effective_execution_time_ms</code>.
     */
    public void setEffectiveExecutionTimeMs(Long value) {
        set(3, value);
    }

    /**
     * Getter for
     * <code>stroom.execution_history.effective_execution_time_ms</code>.
     */
    public Long getEffectiveExecutionTimeMs() {
        return (Long) get(3);
    }

    /**
     * Setter for <code>stroom.execution_history.status</code>.
     */
    public void setStatus(String value) {
        set(4, value);
    }

    /**
     * Getter for <code>stroom.execution_history.status</code>.
     */
    public String getStatus() {
        return (String) get(4);
    }

    /**
     * Setter for <code>stroom.execution_history.message</code>.
     */
    public void setMessage(String value) {
        set(5, value);
    }

    /**
     * Getter for <code>stroom.execution_history.message</code>.
     */
    public String getMessage() {
        return (String) get(5);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<Long> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record6 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row6<Long, Integer, Long, Long, String, String> fieldsRow() {
        return (Row6) super.fieldsRow();
    }

    @Override
    public Row6<Long, Integer, Long, Long, String, String> valuesRow() {
        return (Row6) super.valuesRow();
    }

    @Override
    public Field<Long> field1() {
        return ExecutionHistory.EXECUTION_HISTORY.ID;
    }

    @Override
    public Field<Integer> field2() {
        return ExecutionHistory.EXECUTION_HISTORY.FK_EXECUTION_SCHEDULE_ID;
    }

    @Override
    public Field<Long> field3() {
        return ExecutionHistory.EXECUTION_HISTORY.EXECUTION_TIME_MS;
    }

    @Override
    public Field<Long> field4() {
        return ExecutionHistory.EXECUTION_HISTORY.EFFECTIVE_EXECUTION_TIME_MS;
    }

    @Override
    public Field<String> field5() {
        return ExecutionHistory.EXECUTION_HISTORY.STATUS;
    }

    @Override
    public Field<String> field6() {
        return ExecutionHistory.EXECUTION_HISTORY.MESSAGE;
    }

    @Override
    public Long component1() {
        return getId();
    }

    @Override
    public Integer component2() {
        return getFkExecutionScheduleId();
    }

    @Override
    public Long component3() {
        return getExecutionTimeMs();
    }

    @Override
    public Long component4() {
        return getEffectiveExecutionTimeMs();
    }

    @Override
    public String component5() {
        return getStatus();
    }

    @Override
    public String component6() {
        return getMessage();
    }

    @Override
    public Long value1() {
        return getId();
    }

    @Override
    public Integer value2() {
        return getFkExecutionScheduleId();
    }

    @Override
    public Long value3() {
        return getExecutionTimeMs();
    }

    @Override
    public Long value4() {
        return getEffectiveExecutionTimeMs();
    }

    @Override
    public String value5() {
        return getStatus();
    }

    @Override
    public String value6() {
        return getMessage();
    }

    @Override
    public ExecutionHistoryRecord value1(Long value) {
        setId(value);
        return this;
    }

    @Override
    public ExecutionHistoryRecord value2(Integer value) {
        setFkExecutionScheduleId(value);
        return this;
    }

    @Override
    public ExecutionHistoryRecord value3(Long value) {
        setExecutionTimeMs(value);
        return this;
    }

    @Override
    public ExecutionHistoryRecord value4(Long value) {
        setEffectiveExecutionTimeMs(value);
        return this;
    }

    @Override
    public ExecutionHistoryRecord value5(String value) {
        setStatus(value);
        return this;
    }

    @Override
    public ExecutionHistoryRecord value6(String value) {
        setMessage(value);
        return this;
    }

    @Override
    public ExecutionHistoryRecord values(Long value1, Integer value2, Long value3, Long value4, String value5, String value6) {
        value1(value1);
        value2(value2);
        value3(value3);
        value4(value4);
        value5(value5);
        value6(value6);
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached ExecutionHistoryRecord
     */
    public ExecutionHistoryRecord() {
        super(ExecutionHistory.EXECUTION_HISTORY);
    }

    /**
     * Create a detached, initialised ExecutionHistoryRecord
     */
    public ExecutionHistoryRecord(Long id, Integer fkExecutionScheduleId, Long executionTimeMs, Long effectiveExecutionTimeMs, String status, String message) {
        super(ExecutionHistory.EXECUTION_HISTORY);

        setId(id);
        setFkExecutionScheduleId(fkExecutionScheduleId);
        setExecutionTimeMs(executionTimeMs);
        setEffectiveExecutionTimeMs(effectiveExecutionTimeMs);
        setStatus(status);
        setMessage(message);
        resetChangedOnNotNull();
    }
}
