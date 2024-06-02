/*
 * This file is generated by jOOQ.
 */
package stroom.analytics.impl.db.jooq;


import org.jooq.Index;
import org.jooq.OrderField;
import org.jooq.impl.DSL;
import org.jooq.impl.Internal;

import stroom.analytics.impl.db.jooq.tables.ExecutionSchedule;


/**
 * A class modelling indexes of tables in stroom.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class Indexes {

    // -------------------------------------------------------------------------
    // INDEX definitions
    // -------------------------------------------------------------------------

    public static final Index EXECUTION_SCHEDULE_EXECUTION_SCHEDULE_DOC_IDX = Internal.createIndex(DSL.name("execution_schedule_doc_idx"), ExecutionSchedule.EXECUTION_SCHEDULE, new OrderField[] { ExecutionSchedule.EXECUTION_SCHEDULE.DOC_TYPE, ExecutionSchedule.EXECUTION_SCHEDULE.DOC_UUID }, false);
}
