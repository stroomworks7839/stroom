package stroom.security.shared;

import stroom.util.shared.UserRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.Set;

@JsonInclude(Include.NON_NULL)
public class AppUserPermissions {

    @JsonProperty
    private final UserRef userRef;
    @JsonProperty
    private final Set<AppPermission> permissions;

    @JsonCreator
    public AppUserPermissions(@JsonProperty("userRef") final UserRef userRef,
                              @JsonProperty("permissions") final Set<AppPermission> permissions) {
        this.userRef = userRef;
        this.permissions = permissions;
    }

    public UserRef getUserRef() {
        return userRef;
    }

    public Set<AppPermission> getPermissions() {
        return permissions;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final AppUserPermissions that = (AppUserPermissions) o;
        return Objects.equals(userRef, that.userRef);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(userRef);
    }
}
