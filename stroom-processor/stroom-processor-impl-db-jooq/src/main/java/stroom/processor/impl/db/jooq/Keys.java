/*
 * This file is generated by jOOQ.
 */
package stroom.processor.impl.db.jooq;


import javax.annotation.Generated;

import org.jooq.ForeignKey;
import org.jooq.Identity;
import org.jooq.UniqueKey;
import org.jooq.impl.Internal;

import stroom.processor.impl.db.jooq.tables.Processor;
import stroom.processor.impl.db.jooq.tables.ProcessorFilter;
import stroom.processor.impl.db.jooq.tables.ProcessorFilterTracker;
import stroom.processor.impl.db.jooq.tables.ProcessorNode;
import stroom.processor.impl.db.jooq.tables.ProcessorTask;
import stroom.processor.impl.db.jooq.tables.records.ProcessorFilterRecord;
import stroom.processor.impl.db.jooq.tables.records.ProcessorFilterTrackerRecord;
import stroom.processor.impl.db.jooq.tables.records.ProcessorNodeRecord;
import stroom.processor.impl.db.jooq.tables.records.ProcessorRecord;
import stroom.processor.impl.db.jooq.tables.records.ProcessorTaskRecord;


/**
 * A class modelling foreign key relationships and constraints of tables of 
 * the <code>stroom</code> schema.
 */
@Generated(
    value = {
        "http://www.jooq.org",
        "jOOQ version:3.11.9"
    },
    comments = "This class is generated by jOOQ"
)
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Keys {

    // -------------------------------------------------------------------------
    // IDENTITY definitions
    // -------------------------------------------------------------------------

    public static final Identity<ProcessorRecord, Integer> IDENTITY_PROCESSOR = Identities0.IDENTITY_PROCESSOR;
    public static final Identity<ProcessorFilterRecord, Integer> IDENTITY_PROCESSOR_FILTER = Identities0.IDENTITY_PROCESSOR_FILTER;
    public static final Identity<ProcessorFilterTrackerRecord, Integer> IDENTITY_PROCESSOR_FILTER_TRACKER = Identities0.IDENTITY_PROCESSOR_FILTER_TRACKER;
    public static final Identity<ProcessorNodeRecord, Integer> IDENTITY_PROCESSOR_NODE = Identities0.IDENTITY_PROCESSOR_NODE;
    public static final Identity<ProcessorTaskRecord, Long> IDENTITY_PROCESSOR_TASK = Identities0.IDENTITY_PROCESSOR_TASK;

    // -------------------------------------------------------------------------
    // UNIQUE and PRIMARY KEY definitions
    // -------------------------------------------------------------------------

    public static final UniqueKey<ProcessorRecord> KEY_PROCESSOR_PRIMARY = UniqueKeys0.KEY_PROCESSOR_PRIMARY;
    public static final UniqueKey<ProcessorRecord> KEY_PROCESSOR_UUID = UniqueKeys0.KEY_PROCESSOR_UUID;
    public static final UniqueKey<ProcessorRecord> KEY_PROCESSOR_PIPELINE_UUID = UniqueKeys0.KEY_PROCESSOR_PIPELINE_UUID;
    public static final UniqueKey<ProcessorFilterRecord> KEY_PROCESSOR_FILTER_PRIMARY = UniqueKeys0.KEY_PROCESSOR_FILTER_PRIMARY;
    public static final UniqueKey<ProcessorFilterRecord> KEY_PROCESSOR_FILTER_UUID = UniqueKeys0.KEY_PROCESSOR_FILTER_UUID;
    public static final UniqueKey<ProcessorFilterTrackerRecord> KEY_PROCESSOR_FILTER_TRACKER_PRIMARY = UniqueKeys0.KEY_PROCESSOR_FILTER_TRACKER_PRIMARY;
    public static final UniqueKey<ProcessorNodeRecord> KEY_PROCESSOR_NODE_PRIMARY = UniqueKeys0.KEY_PROCESSOR_NODE_PRIMARY;
    public static final UniqueKey<ProcessorNodeRecord> KEY_PROCESSOR_NODE_NAME = UniqueKeys0.KEY_PROCESSOR_NODE_NAME;
    public static final UniqueKey<ProcessorTaskRecord> KEY_PROCESSOR_TASK_PRIMARY = UniqueKeys0.KEY_PROCESSOR_TASK_PRIMARY;

    // -------------------------------------------------------------------------
    // FOREIGN KEY definitions
    // -------------------------------------------------------------------------

    public static final ForeignKey<ProcessorFilterRecord, ProcessorRecord> PROCESSOR_FILTER_FK_PROCESSOR_ID = ForeignKeys0.PROCESSOR_FILTER_FK_PROCESSOR_ID;
    public static final ForeignKey<ProcessorFilterRecord, ProcessorFilterTrackerRecord> PROCESSOR_FILTER_FK_PROCESSOR_FILTER_TRACKER_ID = ForeignKeys0.PROCESSOR_FILTER_FK_PROCESSOR_FILTER_TRACKER_ID;
    public static final ForeignKey<ProcessorTaskRecord, ProcessorFilterRecord> PROCESSOR_TASK_FK_PROCESSOR_FILTER_ID = ForeignKeys0.PROCESSOR_TASK_FK_PROCESSOR_FILTER_ID;
    public static final ForeignKey<ProcessorTaskRecord, ProcessorNodeRecord> PROCESSOR_TASK_FK_PROCESSOR_NODE_ID = ForeignKeys0.PROCESSOR_TASK_FK_PROCESSOR_NODE_ID;

    // -------------------------------------------------------------------------
    // [#1459] distribute members to avoid static initialisers > 64kb
    // -------------------------------------------------------------------------

    private static class Identities0 {
        public static Identity<ProcessorRecord, Integer> IDENTITY_PROCESSOR = Internal.createIdentity(Processor.PROCESSOR, Processor.PROCESSOR.ID);
        public static Identity<ProcessorFilterRecord, Integer> IDENTITY_PROCESSOR_FILTER = Internal.createIdentity(ProcessorFilter.PROCESSOR_FILTER, ProcessorFilter.PROCESSOR_FILTER.ID);
        public static Identity<ProcessorFilterTrackerRecord, Integer> IDENTITY_PROCESSOR_FILTER_TRACKER = Internal.createIdentity(ProcessorFilterTracker.PROCESSOR_FILTER_TRACKER, ProcessorFilterTracker.PROCESSOR_FILTER_TRACKER.ID);
        public static Identity<ProcessorNodeRecord, Integer> IDENTITY_PROCESSOR_NODE = Internal.createIdentity(ProcessorNode.PROCESSOR_NODE, ProcessorNode.PROCESSOR_NODE.ID);
        public static Identity<ProcessorTaskRecord, Long> IDENTITY_PROCESSOR_TASK = Internal.createIdentity(ProcessorTask.PROCESSOR_TASK, ProcessorTask.PROCESSOR_TASK.ID);
    }

    private static class UniqueKeys0 {
        public static final UniqueKey<ProcessorRecord> KEY_PROCESSOR_PRIMARY = Internal.createUniqueKey(Processor.PROCESSOR, "KEY_processor_PRIMARY", Processor.PROCESSOR.ID);
        public static final UniqueKey<ProcessorRecord> KEY_PROCESSOR_UUID = Internal.createUniqueKey(Processor.PROCESSOR, "KEY_processor_uuid", Processor.PROCESSOR.UUID);
        public static final UniqueKey<ProcessorRecord> KEY_PROCESSOR_PIPELINE_UUID = Internal.createUniqueKey(Processor.PROCESSOR, "KEY_processor_pipeline_uuid", Processor.PROCESSOR.PIPELINE_UUID);
        public static final UniqueKey<ProcessorFilterRecord> KEY_PROCESSOR_FILTER_PRIMARY = Internal.createUniqueKey(ProcessorFilter.PROCESSOR_FILTER, "KEY_processor_filter_PRIMARY", ProcessorFilter.PROCESSOR_FILTER.ID);
        public static final UniqueKey<ProcessorFilterRecord> KEY_PROCESSOR_FILTER_UUID = Internal.createUniqueKey(ProcessorFilter.PROCESSOR_FILTER, "KEY_processor_filter_uuid", ProcessorFilter.PROCESSOR_FILTER.UUID);
        public static final UniqueKey<ProcessorFilterTrackerRecord> KEY_PROCESSOR_FILTER_TRACKER_PRIMARY = Internal.createUniqueKey(ProcessorFilterTracker.PROCESSOR_FILTER_TRACKER, "KEY_processor_filter_tracker_PRIMARY", ProcessorFilterTracker.PROCESSOR_FILTER_TRACKER.ID);
        public static final UniqueKey<ProcessorNodeRecord> KEY_PROCESSOR_NODE_PRIMARY = Internal.createUniqueKey(ProcessorNode.PROCESSOR_NODE, "KEY_processor_node_PRIMARY", ProcessorNode.PROCESSOR_NODE.ID);
        public static final UniqueKey<ProcessorNodeRecord> KEY_PROCESSOR_NODE_NAME = Internal.createUniqueKey(ProcessorNode.PROCESSOR_NODE, "KEY_processor_node_name", ProcessorNode.PROCESSOR_NODE.NAME);
        public static final UniqueKey<ProcessorTaskRecord> KEY_PROCESSOR_TASK_PRIMARY = Internal.createUniqueKey(ProcessorTask.PROCESSOR_TASK, "KEY_processor_task_PRIMARY", ProcessorTask.PROCESSOR_TASK.ID);
    }

    private static class ForeignKeys0 {
        public static final ForeignKey<ProcessorFilterRecord, ProcessorRecord> PROCESSOR_FILTER_FK_PROCESSOR_ID = Internal.createForeignKey(stroom.processor.impl.db.jooq.Keys.KEY_PROCESSOR_PRIMARY, ProcessorFilter.PROCESSOR_FILTER, "processor_filter_fk_processor_id", ProcessorFilter.PROCESSOR_FILTER.FK_PROCESSOR_ID);
        public static final ForeignKey<ProcessorFilterRecord, ProcessorFilterTrackerRecord> PROCESSOR_FILTER_FK_PROCESSOR_FILTER_TRACKER_ID = Internal.createForeignKey(stroom.processor.impl.db.jooq.Keys.KEY_PROCESSOR_FILTER_TRACKER_PRIMARY, ProcessorFilter.PROCESSOR_FILTER, "processor_filter_fk_processor_filter_tracker_id", ProcessorFilter.PROCESSOR_FILTER.FK_PROCESSOR_FILTER_TRACKER_ID);
        public static final ForeignKey<ProcessorTaskRecord, ProcessorFilterRecord> PROCESSOR_TASK_FK_PROCESSOR_FILTER_ID = Internal.createForeignKey(stroom.processor.impl.db.jooq.Keys.KEY_PROCESSOR_FILTER_PRIMARY, ProcessorTask.PROCESSOR_TASK, "processor_task_fk_processor_filter_id", ProcessorTask.PROCESSOR_TASK.FK_PROCESSOR_FILTER_ID);
        public static final ForeignKey<ProcessorTaskRecord, ProcessorNodeRecord> PROCESSOR_TASK_FK_PROCESSOR_NODE_ID = Internal.createForeignKey(stroom.processor.impl.db.jooq.Keys.KEY_PROCESSOR_NODE_PRIMARY, ProcessorTask.PROCESSOR_TASK, "processor_task_fk_processor_node_id", ProcessorTask.PROCESSOR_TASK.FK_PROCESSOR_NODE_ID);
    }
}
