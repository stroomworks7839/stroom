/*
 * Copyright 2024 Crown Copyright
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

package stroom.docref;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlType;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@value #CLASS_DESC}
 */
@XmlType(name = "DocRef", propOrder = {"type", "uuid", "name"})
@XmlRootElement(name = "doc")
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(description = DocRef.CLASS_DESC)
@JsonPropertyOrder({"type", "uuid", "name"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DocRef implements Comparable<DocRef>, HasDisplayValue, HasType, HasUuid, HasNameMutable {

    public static final String CLASS_DESC = "A class for describing a unique reference to a 'document' in stroom.  " +
                                            "A 'document' is an entity in stroom such as a data source dictionary " +
                                            "or pipeline.";

    @Schema(description = "The type of the 'document' that this DocRef refers to",
            example = "StroomStatsStore")
    @JsonProperty
    private String type;

    @XmlElement
    @Schema(description = "The unique identifier for this 'document'",
            example = "9f6184b4-bd78-48bc-b0cd-6e51a357f6a6")
    @JsonProperty(required = true)
    private String uuid;

    @XmlElement
    @Schema(description = "The name for the data source",
            example = "MyStatistic")
    @JsonProperty
    private String name;

    /**
     * JAXB requires a no-arg constructor.
     */
    public DocRef() {
    }

    /**
     * @param type The type of the 'document' that this docRef points to an instance of. Supported types are defined
     *             outside of this documentation.
     * @param uuid A UUID as generated by {@link java.util.UUID#randomUUID()}
     */
    public DocRef(final String type, String uuid) {
        this(type, uuid, null);
    }

    /**
     * @param type The type of the 'document' that this docRef points to an instance of. Supported types are defined
     *             outside of this documentation.
     * @param uuid A UUID as generated by {@link java.util.UUID#randomUUID()}
     * @param name The name of the 'document' being referenced
     */
    @JsonCreator
    public DocRef(@JsonProperty("type") final String type,
                  @JsonProperty("uuid") final String uuid,
                  @JsonProperty("name") final String name) {
        this.type = type;
        this.uuid = uuid;
        this.name = name;
    }

    /**
     * @return The type of the 'document' that this docRef points to an instance of. Supported types are defined
     * outside of this documentation.
     */
    @Override
    public String getType() {
        return type;
    }

    public void setType(final String type) {
        this.type = type;
    }

    /**
     * @return A UUID as generated by {@link java.util.UUID#randomUUID()}
     */
    @Override
    public String getUuid() {
        return uuid;
    }

    public void setUuid(final String uuid) {
        this.uuid = uuid;
    }

    /**
     * @return The name of the 'document' being referenced
     */
    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(final String name) {
        this.name = name;
    }

    @XmlTransient
    @JsonIgnore
    @Override
    public String getDisplayValue() {
        return name;
    }

    public String getDisplayValue(final DisplayType displayType) {
        final DisplayType dispType = displayType != null
                ? displayType
                : DisplayType.AUTO;
        final Function<DocRef, String> displayTextFunc = dispType.getDisplayTextFunc();
        return displayTextFunc.apply(this);
    }

    private String getNameOrUuid() {
        return name != null
                ? name
                : uuid;
    }

    @Override
    public int compareTo(final DocRef o) {
        int diff = 0;

        if (type != null && o.type != null) {
            diff = type.compareTo(o.type);
        }
        if (diff == 0) {
            if (name != null && o.name != null) {
                diff = name.compareTo(o.name);
            }
        }
        if (diff == 0) {
            diff = uuid.compareTo(o.uuid);
        }
        return diff;
    }

    public String toInfoString() {
        final StringBuilder sb = new StringBuilder();
        if (name != null) {
            sb.append(name);
        }
        if (uuid != null) {
            //noinspection SizeReplaceableByIsEmpty // Not in GWT
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append("{");
            sb.append(uuid);
            sb.append("}");
        }

        //noinspection SizeReplaceableByIsEmpty // Not in GWT
        if (sb.length() > 0) {
            return sb.toString();
        }

        return toString();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DocRef)) {
            return false;
        }
        //noinspection PatternVariableCanBeUsed // Not in GWT
        final DocRef docRef = (DocRef) o;
        return Objects.equals(uuid, docRef.uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    /**
     * @return The {@link DocRef} in the form '{@code {type:uuid:name}}'
     */
    public String toShortString() {
        return "{" + type + ":" + uuid + ":" + name + "}";
    }

    @Override
    public String toString() {
        // TODO: 15/12/2022 I Think we ought to change the output to this shorter form, but not sure
        //  if we have any code that relies on the format.
//        return "{" + name + ":" + type + ":" + uuid + "}";
        return "DocRef{" +
               "type='" + type + '\'' +
               ", uuid='" + uuid + '\'' +
               ", name='" + name + '\'' +
               '}';
    }

    /**
     * Compare this to other on type, uuid and name, unlike the {@link DocRef#equals(Object)}
     * which only compares on type and uuid. This method is useful for determining if the name has changed
     * as well as the key parts.
     */
    public boolean equalsIncludingName(final DocRef other) {
        return Objects.equals(this, other);
    }

    /**
     * Compare two {@link DocRef} objects on type, uuid and name, unlike the {@link DocRef#equals(Object)}
     * which only compares on type and uuid. This method is useful for determining if the name has changed
     * as well as the key parts.
     */
    public static boolean equalsIncludingName(final DocRef docRef1, final DocRef docRef2) {
        if (docRef1 == docRef2) {
            return true;
        } else if (docRef1 == null) {
            return false;
        } else if (docRef2 == null) {
            return false;
        } else {
            return Objects.equals(docRef1.type, docRef2.type) &&
                   Objects.equals(docRef1.uuid, docRef2.uuid) &&
                   Objects.equals(docRef1.name, docRef2.name);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a {@link DocRef} builder for a given type
     *
     * @param type The document type
     */
    public static TypedBuilder builder(final String type) {
        return new TypedBuilder(type);
    }

    public Builder copy() {
        return new Builder(this);
    }

    /**
     * @return A copy of this {@link DocRef} with a null name.
     */
    public DocRef withoutName() {
        return new DocRef(type, uuid);
    }


    // --------------------------------------------------------------------------------


    public enum DisplayType {
        /**
         * Displays the first non-null value in this order:
         * <p>name</p>
         * <p>UUID</p>
         */
        AUTO(DocRef::getNameOrUuid, "value"),
        /**
         * The name of the {@link DocRef}
         */
        NAME(DocRef::getName, "name"),
        /**
         * The type of the {@link DocRef}
         */
        TYPE(DocRef::getType, "type"),
        /**
         * The UUID of the {@link DocRef}
         */
        UUID(DocRef::getUuid, "UUID");

        private final Function<DocRef, String> displayTextFunc;
        private final String typeName;

        DisplayType(final Function<DocRef, String> displayTextFunc,
                    final String typeName) {
            this.displayTextFunc = displayTextFunc;
            this.typeName = typeName;
        }

        public Function<DocRef, String> getDisplayTextFunc() {
            return displayTextFunc;
        }

        /**
         * @return The name of the display type for use in UI text.
         */
        public String getTypeName() {
            return typeName;
        }
    }


    // --------------------------------------------------------------------------------


    /**
     * Builder for constructing a {@link DocRef docRef}
     */
    public static final class Builder {

        private String type;
        private String uuid;
        private String name;

        private Builder() {
        }

        private Builder(final DocRef docRef) {
            this.type = docRef.type;
            this.uuid = docRef.uuid;
            this.name = docRef.name;
        }

        /**
         * @param value The type of the 'document' that this docRef points to an instance of. Supported types
         *              are defined outside of this documentation.
         * @return The {@link Builder}, enabling method chaining
         */
        public Builder type(final String value) {
            this.type = value;
            return this;
        }

        /**
         * @param value A UUID as generated by {@link java.util.UUID#randomUUID()}
         * @return The {@link Builder}, enabling method chaining
         */
        public Builder uuid(final String value) {
            this.uuid = value;
            return this;
        }

        /**
         * Sets the uuid to a new random UUID.
         *
         * @return The {@link Builder}, enabling method chaining
         */
        public Builder randomUuid() {
            this.uuid = UUID.randomUUID().toString();
            return this;
        }

        /**
         * @param value The name of the 'document' being referenced
         * @return The {@link Builder}, enabling method chaining
         */
        public Builder name(final String value) {
            this.name = value;
            return this;
        }

        public DocRef build() {
            return new DocRef(type, uuid, name);
        }
    }


    // --------------------------------------------------------------------------------


    /**
     * Builder for constructing a {@link DocRef docRef}
     */
    public static final class TypedBuilder {

        private final String type;
        private String uuid;
        private String name;

        private TypedBuilder(final String type) {
            this.type = Objects.requireNonNull(type);
        }

        /**
         * @param value A UUID as generated by {@link java.util.UUID#randomUUID()}
         * @return The {@link Builder}, enabling method chaining
         */
        public TypedBuilder uuid(final String value) {
            this.uuid = value;
            return this;
        }

        /**
         * Generate a new random UUID and set it as the uuid.
         *
         * @return The {@link Builder}, enabling method chaining
         */
        public TypedBuilder randomUuid() {
            this.uuid = UUID.randomUUID().toString();
            return this;
        }

        /**
         * @param value The name of the 'document' being referenced
         * @return The {@link Builder}, enabling method chaining
         */
        public TypedBuilder name(final String value) {
            this.name = value;
            return this;
        }

        public DocRef build() {
            Objects.requireNonNull(uuid, "UUID not set");
            // Name is not strictly needed by the backend
            return new DocRef(type, uuid, name);
        }
    }
}
