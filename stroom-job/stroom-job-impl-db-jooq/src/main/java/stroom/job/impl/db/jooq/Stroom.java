/*
 * This file is generated by jOOQ.
 */
package stroom.job.impl.db.jooq;


import stroom.job.impl.db.jooq.tables.Job;
import stroom.job.impl.db.jooq.tables.JobNode;

import org.jooq.Catalog;
import org.jooq.Table;
import org.jooq.impl.SchemaImpl;

import java.util.Arrays;
import java.util.List;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Stroom extends SchemaImpl {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>stroom</code>
     */
    public static final Stroom STROOM = new Stroom();

    /**
     * The table <code>stroom.job</code>.
     */
    public final Job JOB = Job.JOB;

    /**
     * The table <code>stroom.job_node</code>.
     */
    public final JobNode JOB_NODE = JobNode.JOB_NODE;

    /**
     * No further instances allowed
     */
    private Stroom() {
        super("stroom", null);
    }


    @Override
    public Catalog getCatalog() {
        return DefaultCatalog.DEFAULT_CATALOG;
    }

    @Override
    public final List<Table<?>> getTables() {
        return Arrays.asList(
            Job.JOB,
            JobNode.JOB_NODE
        );
    }
}
