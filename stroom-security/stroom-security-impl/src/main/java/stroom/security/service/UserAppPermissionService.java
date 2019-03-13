/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.security.service;

import stroom.security.shared.UserAppPermissions;
import stroom.security.shared.UserRef;

import java.util.Set;

public interface UserAppPermissionService {
    default UserAppPermissions getPermissionsForUser(UserRef userRef) {
        final Set<String> permissionNames = getPermissionNamesForUser(userRef.getUuid());
        final Set<String> allNames = getAllPermissionNames();

        return new UserAppPermissions(userRef, allNames, permissionNames);
    }

    Set<String> getPermissionNamesForUser(String userUuid);

    Set<String> getAllPermissionNames();

    void addPermission(String userUuid, String permission);

    void removePermission(String userUuid, String permission);
}
