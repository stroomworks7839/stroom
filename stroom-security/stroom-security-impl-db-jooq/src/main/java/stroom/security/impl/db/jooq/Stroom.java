/*
 * This file is generated by jOOQ.
 */
package stroom.security.impl.db.jooq;


import java.util.Arrays;
import java.util.List;

import org.jooq.Catalog;
import org.jooq.Table;
import org.jooq.impl.SchemaImpl;

import stroom.security.impl.db.jooq.tables.ApiKey;
import stroom.security.impl.db.jooq.tables.PermissionApp;
import stroom.security.impl.db.jooq.tables.PermissionAppId;
import stroom.security.impl.db.jooq.tables.PermissionDoc;
import stroom.security.impl.db.jooq.tables.PermissionDocCreate;
import stroom.security.impl.db.jooq.tables.PermissionDocId;
import stroom.security.impl.db.jooq.tables.PermissionDocTypeId;
import stroom.security.impl.db.jooq.tables.StroomUser;
import stroom.security.impl.db.jooq.tables.StroomUserArchive;
import stroom.security.impl.db.jooq.tables.StroomUserGroup;


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
     * The table <code>stroom.api_key</code>.
     */
    public final ApiKey API_KEY = ApiKey.API_KEY;

    /**
     * The table <code>stroom.permission_app</code>.
     */
    public final PermissionApp PERMISSION_APP = PermissionApp.PERMISSION_APP;

    /**
     * The table <code>stroom.permission_app_id</code>.
     */
    public final PermissionAppId PERMISSION_APP_ID = PermissionAppId.PERMISSION_APP_ID;

    /**
     * The table <code>stroom.permission_doc</code>.
     */
    public final PermissionDoc PERMISSION_DOC = PermissionDoc.PERMISSION_DOC;

    /**
     * The table <code>stroom.permission_doc_create</code>.
     */
    public final PermissionDocCreate PERMISSION_DOC_CREATE = PermissionDocCreate.PERMISSION_DOC_CREATE;

    /**
     * The table <code>stroom.permission_doc_id</code>.
     */
    public final PermissionDocId PERMISSION_DOC_ID = PermissionDocId.PERMISSION_DOC_ID;

    /**
     * The table <code>stroom.permission_doc_type_id</code>.
     */
    public final PermissionDocTypeId PERMISSION_DOC_TYPE_ID = PermissionDocTypeId.PERMISSION_DOC_TYPE_ID;

    /**
     * The table <code>stroom.stroom_user</code>.
     */
    public final StroomUser STROOM_USER = StroomUser.STROOM_USER;

    /**
     * The table <code>stroom.stroom_user_archive</code>.
     */
    public final StroomUserArchive STROOM_USER_ARCHIVE = StroomUserArchive.STROOM_USER_ARCHIVE;

    /**
     * The table <code>stroom.stroom_user_group</code>.
     */
    public final StroomUserGroup STROOM_USER_GROUP = StroomUserGroup.STROOM_USER_GROUP;

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
            ApiKey.API_KEY,
            PermissionApp.PERMISSION_APP,
            PermissionAppId.PERMISSION_APP_ID,
            PermissionDoc.PERMISSION_DOC,
            PermissionDocCreate.PERMISSION_DOC_CREATE,
            PermissionDocId.PERMISSION_DOC_ID,
            PermissionDocTypeId.PERMISSION_DOC_TYPE_ID,
            StroomUser.STROOM_USER,
            StroomUserArchive.STROOM_USER_ARCHIVE,
            StroomUserGroup.STROOM_USER_GROUP
        );
    }
}
