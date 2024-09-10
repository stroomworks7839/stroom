/*
 * This file is generated by jOOQ.
 */
package stroom.security.impl.db.jooq;


import org.jooq.ForeignKey;
import org.jooq.TableField;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.Internal;

import stroom.security.impl.db.jooq.tables.ApiKey;
import stroom.security.impl.db.jooq.tables.PermissionApp;
import stroom.security.impl.db.jooq.tables.PermissionAppId;
import stroom.security.impl.db.jooq.tables.PermissionDoc;
import stroom.security.impl.db.jooq.tables.PermissionDocCreate;
import stroom.security.impl.db.jooq.tables.PermissionDocId;
import stroom.security.impl.db.jooq.tables.PermissionDocTypeId;
import stroom.security.impl.db.jooq.tables.StroomUser;
import stroom.security.impl.db.jooq.tables.StroomUserGroup;
import stroom.security.impl.db.jooq.tables.records.ApiKeyRecord;
import stroom.security.impl.db.jooq.tables.records.PermissionAppIdRecord;
import stroom.security.impl.db.jooq.tables.records.PermissionAppRecord;
import stroom.security.impl.db.jooq.tables.records.PermissionDocCreateRecord;
import stroom.security.impl.db.jooq.tables.records.PermissionDocIdRecord;
import stroom.security.impl.db.jooq.tables.records.PermissionDocRecord;
import stroom.security.impl.db.jooq.tables.records.PermissionDocTypeIdRecord;
import stroom.security.impl.db.jooq.tables.records.StroomUserGroupRecord;
import stroom.security.impl.db.jooq.tables.records.StroomUserRecord;


/**
 * A class modelling foreign key relationships and constraints of tables in
 * stroom.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class Keys {

    // -------------------------------------------------------------------------
    // UNIQUE and PRIMARY KEY definitions
    // -------------------------------------------------------------------------

    public static final UniqueKey<ApiKeyRecord> KEY_API_KEY_API_KEY_API_KEY_HASH_IDX = Internal.createUniqueKey(ApiKey.API_KEY, DSL.name("KEY_api_key_api_key_api_key_hash_idx"), new TableField[] { ApiKey.API_KEY.API_KEY_HASH }, true);
    public static final UniqueKey<ApiKeyRecord> KEY_API_KEY_API_KEY_OWNER_NAME_IDX = Internal.createUniqueKey(ApiKey.API_KEY, DSL.name("KEY_api_key_api_key_owner_name_idx"), new TableField[] { ApiKey.API_KEY.FK_OWNER_UUID, ApiKey.API_KEY.NAME }, true);
    public static final UniqueKey<ApiKeyRecord> KEY_API_KEY_PRIMARY = Internal.createUniqueKey(ApiKey.API_KEY, DSL.name("KEY_api_key_PRIMARY"), new TableField[] { ApiKey.API_KEY.ID }, true);
    public static final UniqueKey<PermissionAppRecord> KEY_PERMISSION_APP_PERMISSION_APP_USER_UUID_PERMISSION_ID_IDX = Internal.createUniqueKey(PermissionApp.PERMISSION_APP, DSL.name("KEY_permission_app_permission_app_user_uuid_permission_id_idx"), new TableField[] { PermissionApp.PERMISSION_APP.USER_UUID, PermissionApp.PERMISSION_APP.PERMISSION_ID }, true);
    public static final UniqueKey<PermissionAppRecord> KEY_PERMISSION_APP_PRIMARY = Internal.createUniqueKey(PermissionApp.PERMISSION_APP, DSL.name("KEY_permission_app_PRIMARY"), new TableField[] { PermissionApp.PERMISSION_APP.ID }, true);
    public static final UniqueKey<PermissionAppIdRecord> KEY_PERMISSION_APP_ID_PERMISSION_APP_ID_PERMISSION_IDX = Internal.createUniqueKey(PermissionAppId.PERMISSION_APP_ID, DSL.name("KEY_permission_app_id_permission_app_id_permission_idx"), new TableField[] { PermissionAppId.PERMISSION_APP_ID.PERMISSION }, true);
    public static final UniqueKey<PermissionAppIdRecord> KEY_PERMISSION_APP_ID_PRIMARY = Internal.createUniqueKey(PermissionAppId.PERMISSION_APP_ID, DSL.name("KEY_permission_app_id_PRIMARY"), new TableField[] { PermissionAppId.PERMISSION_APP_ID.ID }, true);
    public static final UniqueKey<PermissionDocRecord> KEY_PERMISSION_DOC_PERMISSION_DOC_USER_UUID_DOC_UUID_IDX = Internal.createUniqueKey(PermissionDoc.PERMISSION_DOC, DSL.name("KEY_permission_doc_permission_doc_user_uuid_doc_uuid_idx"), new TableField[] { PermissionDoc.PERMISSION_DOC.USER_UUID, PermissionDoc.PERMISSION_DOC.DOC_UUID }, true);
    public static final UniqueKey<PermissionDocRecord> KEY_PERMISSION_DOC_PRIMARY = Internal.createUniqueKey(PermissionDoc.PERMISSION_DOC, DSL.name("KEY_permission_doc_PRIMARY"), new TableField[] { PermissionDoc.PERMISSION_DOC.ID }, true);
    public static final UniqueKey<PermissionDocCreateRecord> KEY_PERMISSION_DOC_CREATE_PERMISSION_DOC_CREATE_USER_UUID_DOC_UUID_DOC_TYPE_ID_IDX = Internal.createUniqueKey(PermissionDocCreate.PERMISSION_DOC_CREATE, DSL.name("KEY_permission_doc_create_permission_doc_create_user_uuid_doc_uuid_doc_type_id_idx"), new TableField[] { PermissionDocCreate.PERMISSION_DOC_CREATE.USER_UUID, PermissionDocCreate.PERMISSION_DOC_CREATE.DOC_UUID, PermissionDocCreate.PERMISSION_DOC_CREATE.DOC_TYPE_ID }, true);
    public static final UniqueKey<PermissionDocCreateRecord> KEY_PERMISSION_DOC_CREATE_PRIMARY = Internal.createUniqueKey(PermissionDocCreate.PERMISSION_DOC_CREATE, DSL.name("KEY_permission_doc_create_PRIMARY"), new TableField[] { PermissionDocCreate.PERMISSION_DOC_CREATE.ID }, true);
    public static final UniqueKey<PermissionDocIdRecord> KEY_PERMISSION_DOC_ID_PERMISSION_DOC_ID_PERMISSION_IDX = Internal.createUniqueKey(PermissionDocId.PERMISSION_DOC_ID, DSL.name("KEY_permission_doc_id_permission_doc_id_permission_idx"), new TableField[] { PermissionDocId.PERMISSION_DOC_ID.PERMISSION }, true);
    public static final UniqueKey<PermissionDocIdRecord> KEY_PERMISSION_DOC_ID_PRIMARY = Internal.createUniqueKey(PermissionDocId.PERMISSION_DOC_ID, DSL.name("KEY_permission_doc_id_PRIMARY"), new TableField[] { PermissionDocId.PERMISSION_DOC_ID.ID }, true);
    public static final UniqueKey<PermissionDocTypeIdRecord> KEY_PERMISSION_DOC_TYPE_ID_PERMISSION_DOC_TYPE_ID_TYPE_IDX = Internal.createUniqueKey(PermissionDocTypeId.PERMISSION_DOC_TYPE_ID, DSL.name("KEY_permission_doc_type_id_permission_doc_type_id_type_idx"), new TableField[] { PermissionDocTypeId.PERMISSION_DOC_TYPE_ID.TYPE }, true);
    public static final UniqueKey<PermissionDocTypeIdRecord> KEY_PERMISSION_DOC_TYPE_ID_PRIMARY = Internal.createUniqueKey(PermissionDocTypeId.PERMISSION_DOC_TYPE_ID, DSL.name("KEY_permission_doc_type_id_PRIMARY"), new TableField[] { PermissionDocTypeId.PERMISSION_DOC_TYPE_ID.ID }, true);
    public static final UniqueKey<StroomUserRecord> KEY_STROOM_USER_PRIMARY = Internal.createUniqueKey(StroomUser.STROOM_USER, DSL.name("KEY_stroom_user_PRIMARY"), new TableField[] { StroomUser.STROOM_USER.ID }, true);
    public static final UniqueKey<StroomUserRecord> KEY_STROOM_USER_STROOM_USER_NAME_IS_GROUP_IDX = Internal.createUniqueKey(StroomUser.STROOM_USER, DSL.name("KEY_stroom_user_stroom_user_name_is_group_idx"), new TableField[] { StroomUser.STROOM_USER.NAME, StroomUser.STROOM_USER.IS_GROUP }, true);
    public static final UniqueKey<StroomUserRecord> KEY_STROOM_USER_STROOM_USER_UUID_IDX = Internal.createUniqueKey(StroomUser.STROOM_USER, DSL.name("KEY_stroom_user_stroom_user_uuid_idx"), new TableField[] { StroomUser.STROOM_USER.UUID }, true);
    public static final UniqueKey<StroomUserGroupRecord> KEY_STROOM_USER_GROUP_PRIMARY = Internal.createUniqueKey(StroomUserGroup.STROOM_USER_GROUP, DSL.name("KEY_stroom_user_group_PRIMARY"), new TableField[] { StroomUserGroup.STROOM_USER_GROUP.ID }, true);
    public static final UniqueKey<StroomUserGroupRecord> KEY_STROOM_USER_GROUP_STROOM_USER_GROUP_GROUP_UUID_USER_UUID_IDX = Internal.createUniqueKey(StroomUserGroup.STROOM_USER_GROUP, DSL.name("KEY_stroom_user_group_stroom_user_group_group_uuid_user_uuid_IDX"), new TableField[] { StroomUserGroup.STROOM_USER_GROUP.GROUP_UUID, StroomUserGroup.STROOM_USER_GROUP.USER_UUID }, true);
    public static final UniqueKey<StroomUserGroupRecord> KEY_STROOM_USER_GROUP_STROOM_USER_GROUP_USER_UUID_GROUP_UUID_IDX = Internal.createUniqueKey(StroomUserGroup.STROOM_USER_GROUP, DSL.name("KEY_stroom_user_group_stroom_user_group_user_uuid_group_uuid_idx"), new TableField[] { StroomUserGroup.STROOM_USER_GROUP.USER_UUID, StroomUserGroup.STROOM_USER_GROUP.GROUP_UUID }, true);

    // -------------------------------------------------------------------------
    // FOREIGN KEY definitions
    // -------------------------------------------------------------------------

    public static final ForeignKey<ApiKeyRecord, StroomUserRecord> API_KEY_FK_OWNER_UUID = Internal.createForeignKey(ApiKey.API_KEY, DSL.name("api_key_fk_owner_uuid"), new TableField[] { ApiKey.API_KEY.FK_OWNER_UUID }, Keys.KEY_STROOM_USER_STROOM_USER_UUID_IDX, new TableField[] { StroomUser.STROOM_USER.UUID }, true);
    public static final ForeignKey<PermissionAppRecord, PermissionAppIdRecord> PERMISSION_APP_PERMISSION_ID = Internal.createForeignKey(PermissionApp.PERMISSION_APP, DSL.name("permission_app_permission_id"), new TableField[] { PermissionApp.PERMISSION_APP.PERMISSION_ID }, Keys.KEY_PERMISSION_APP_ID_PRIMARY, new TableField[] { PermissionAppId.PERMISSION_APP_ID.ID }, true);
    public static final ForeignKey<PermissionAppRecord, StroomUserRecord> PERMISSION_APP_USER_UUID = Internal.createForeignKey(PermissionApp.PERMISSION_APP, DSL.name("permission_app_user_uuid"), new TableField[] { PermissionApp.PERMISSION_APP.USER_UUID }, Keys.KEY_STROOM_USER_STROOM_USER_UUID_IDX, new TableField[] { StroomUser.STROOM_USER.UUID }, true);
    public static final ForeignKey<PermissionDocRecord, PermissionDocIdRecord> PERMISSION_DOC_PERMISSION_ID = Internal.createForeignKey(PermissionDoc.PERMISSION_DOC, DSL.name("permission_doc_permission_id"), new TableField[] { PermissionDoc.PERMISSION_DOC.PERMISSION_ID }, Keys.KEY_PERMISSION_DOC_ID_PRIMARY, new TableField[] { PermissionDocId.PERMISSION_DOC_ID.ID }, true);
    public static final ForeignKey<PermissionDocRecord, StroomUserRecord> PERMISSION_DOC_USER_UUID = Internal.createForeignKey(PermissionDoc.PERMISSION_DOC, DSL.name("permission_doc_user_uuid"), new TableField[] { PermissionDoc.PERMISSION_DOC.USER_UUID }, Keys.KEY_STROOM_USER_STROOM_USER_UUID_IDX, new TableField[] { StroomUser.STROOM_USER.UUID }, true);
    public static final ForeignKey<PermissionDocCreateRecord, PermissionDocTypeIdRecord> PERMISSION_DOC_CREATE_DOC_TYPE_ID = Internal.createForeignKey(PermissionDocCreate.PERMISSION_DOC_CREATE, DSL.name("permission_doc_create_doc_type_id"), new TableField[] { PermissionDocCreate.PERMISSION_DOC_CREATE.DOC_TYPE_ID }, Keys.KEY_PERMISSION_DOC_TYPE_ID_PRIMARY, new TableField[] { PermissionDocTypeId.PERMISSION_DOC_TYPE_ID.ID }, true);
    public static final ForeignKey<PermissionDocCreateRecord, StroomUserRecord> PERMISSION_DOC_CREATE_USER_UUID = Internal.createForeignKey(PermissionDocCreate.PERMISSION_DOC_CREATE, DSL.name("permission_doc_create_user_uuid"), new TableField[] { PermissionDocCreate.PERMISSION_DOC_CREATE.USER_UUID }, Keys.KEY_STROOM_USER_STROOM_USER_UUID_IDX, new TableField[] { StroomUser.STROOM_USER.UUID }, true);
    public static final ForeignKey<StroomUserGroupRecord, StroomUserRecord> STROOM_USER_GROUP_FK_GROUP_UUID = Internal.createForeignKey(StroomUserGroup.STROOM_USER_GROUP, DSL.name("stroom_user_group_fk_group_uuid"), new TableField[] { StroomUserGroup.STROOM_USER_GROUP.GROUP_UUID }, Keys.KEY_STROOM_USER_STROOM_USER_UUID_IDX, new TableField[] { StroomUser.STROOM_USER.UUID }, true);
    public static final ForeignKey<StroomUserGroupRecord, StroomUserRecord> STROOM_USER_GROUP_FK_USER_UUID = Internal.createForeignKey(StroomUserGroup.STROOM_USER_GROUP, DSL.name("stroom_user_group_fk_user_uuid"), new TableField[] { StroomUserGroup.STROOM_USER_GROUP.USER_UUID }, Keys.KEY_STROOM_USER_STROOM_USER_UUID_IDX, new TableField[] { StroomUser.STROOM_USER.UUID }, true);
}
