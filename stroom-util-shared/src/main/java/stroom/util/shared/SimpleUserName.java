package stroom.util.shared;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Comparator;
import java.util.Objects;

/**
 * Pojo for representing enough info to identify a user
 */
@JsonInclude(Include.NON_NULL)
public class SimpleUserName implements Comparable<UserName>, HasAuditableUserIdentity, UserName {
    private static final Comparator<UserName> COMPARATOR = Comparator.comparing(UserName::getSubjectId);

    @JsonProperty
    private final String subjectId;
    @JsonProperty
    private final String displayName;
    @JsonProperty
    private final String fullName;

    @JsonCreator
    public SimpleUserName(@JsonProperty("subjectId") final String subjectId,
                          @JsonProperty("displayName") final String displayName,
                          @JsonProperty("fullName") final String fullName) {
        if (subjectId == null || subjectId.trim().length() == 0) {
            throw new RuntimeException("subjectId must have a value");
        }
        this.subjectId = Objects.requireNonNull(subjectId);
        this.displayName = displayName;
        this.fullName = fullName;
    }

    public SimpleUserName(final String subjectId) {
        this(subjectId, null, null);
    }

    @Override
    public String getSubjectId() {
        return subjectId;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getFullName() {
        return fullName;
    }

    @Override
    @JsonIgnore
    public String getUserIdentityForAudit() {
        return HasAuditableUserIdentity.fromUserNames(subjectId, displayName);
    }

    @Override
    public String toString() {
        return UserName.buildCombinedName(this);
    }

    /**
     * displayName and fullName are optional and for decoration, so equals/hash/compare
     * is done on name only
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SimpleUserName userName = (SimpleUserName) o;
        return subjectId.equals(userName.subjectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subjectId);
    }

    public Builder copy() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public int compareTo(final UserName o) {
        return COMPARATOR.compare(this, o);
    }


    // --------------------------------------------------------------------------------


    public static final class Builder {

        private String name;
        private String displayName;
        private String fullName;

        private Builder() {
        }

        private Builder(final SimpleUserName username) {
            this.name = username.subjectId;
            this.displayName = username.getDisplayName();
            this.fullName = username.getFullName();
        }

        public Builder name(final String value) {
            name = value;
            return this;
        }

        public Builder displayName(final String value) {
            displayName = value;
            return this;
        }

        public Builder fullName(final String value) {
            fullName = value;
            return this;
        }

        public UserName build() {
            return new SimpleUserName(name, displayName, fullName);
        }
    }
}
