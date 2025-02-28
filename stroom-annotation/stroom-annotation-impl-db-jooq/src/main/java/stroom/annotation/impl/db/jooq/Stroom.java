/*
 * This file is generated by jOOQ.
 */
package stroom.annotation.impl.db.jooq;


import java.util.Arrays;
import java.util.List;

import org.jooq.Catalog;
import org.jooq.Table;
import org.jooq.impl.SchemaImpl;

import stroom.annotation.impl.db.jooq.tables.Annotation;
import stroom.annotation.impl.db.jooq.tables.AnnotationDataLink;
import stroom.annotation.impl.db.jooq.tables.AnnotationEntry;
import stroom.annotation.impl.db.jooq.tables.AnnotationGroup;
import stroom.annotation.impl.db.jooq.tables.AnnotationTag;
import stroom.annotation.impl.db.jooq.tables.AnnotationTagLink;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class Stroom extends SchemaImpl {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>stroom</code>
     */
    public static final Stroom STROOM = new Stroom();

    /**
     * The table <code>stroom.annotation</code>.
     */
    public final Annotation ANNOTATION = Annotation.ANNOTATION;

    /**
     * The table <code>stroom.annotation_data_link</code>.
     */
    public final AnnotationDataLink ANNOTATION_DATA_LINK = AnnotationDataLink.ANNOTATION_DATA_LINK;

    /**
     * The table <code>stroom.annotation_entry</code>.
     */
    public final AnnotationEntry ANNOTATION_ENTRY = AnnotationEntry.ANNOTATION_ENTRY;

    /**
     * The table <code>stroom.annotation_group</code>.
     */
    public final AnnotationGroup ANNOTATION_GROUP = AnnotationGroup.ANNOTATION_GROUP;

    /**
     * The table <code>stroom.annotation_tag</code>.
     */
    public final AnnotationTag ANNOTATION_TAG = AnnotationTag.ANNOTATION_TAG;

    /**
     * The table <code>stroom.annotation_tag_link</code>.
     */
    public final AnnotationTagLink ANNOTATION_TAG_LINK = AnnotationTagLink.ANNOTATION_TAG_LINK;

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
            Annotation.ANNOTATION,
            AnnotationDataLink.ANNOTATION_DATA_LINK,
            AnnotationEntry.ANNOTATION_ENTRY,
            AnnotationGroup.ANNOTATION_GROUP,
            AnnotationTag.ANNOTATION_TAG,
            AnnotationTagLink.ANNOTATION_TAG_LINK
        );
    }
}
