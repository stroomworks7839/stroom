/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.shared;

import stroom.docref.DocRef;
import stroom.docs.shared.Description;
import stroom.docstore.shared.AbstractEmbeddableDoc;
import stroom.docstore.shared.DocumentType;
import stroom.docstore.shared.DocumentTypeRegistry;
import stroom.util.shared.HasData;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

@Description(
        "A Shapeshifter Document holds a Shapeshifter configuration — a project of templates that " +
        "match and transform text or binary data into XML — as JSON.\n" +
        "\n" +
        "This Document is used by the following pipeline elements:\n" +
        "\n" +
        "* {{< pipe-elm \"ShapeshifterParser\" >}}"
)
@JsonPropertyOrder({
        "type",
        "uuid",
        "name",
        "version",
        "createTimeMs",
        "updateTimeMs",
        "createUser",
        "updateUser",
        "description",
        "data"})
@JsonInclude(Include.NON_NULL)
public class ShapeshifterDoc extends AbstractEmbeddableDoc implements HasData {

    public static final String TYPE = "Shapeshifter";
    public static final DocumentType DOCUMENT_TYPE = DocumentTypeRegistry.SHAPESHIFTER_DOCUMENT_TYPE;

    @JsonProperty
    private final String description;
    /** The project JSON, exactly as the engine's {@code ProjectReader} reads it. */
    @JsonProperty
    private final String data;

    @JsonCreator
    public ShapeshifterDoc(@JsonProperty("uuid") final String uuid,
                           @JsonProperty("name") final String name,
                           @JsonProperty("version") final String version,
                           @JsonProperty("createTimeMs") final Long createTimeMs,
                           @JsonProperty("updateTimeMs") final Long updateTimeMs,
                           @JsonProperty("createUser") final String createUser,
                           @JsonProperty("updateUser") final String updateUser,
                           @JsonProperty("description") final String description,
                           @JsonProperty("data") final String data,
                           @JsonProperty("embeddedIn") final DocRef embeddedIn) {
        super(TYPE, uuid, name, version, createTimeMs, updateTimeMs, createUser, updateUser, embeddedIn);
        this.description = description;
        this.data = data;
    }

    public static DocRef getDocRef(final String uuid) {
        return DocRef.builder(TYPE)
                .uuid(uuid)
                .build();
    }

    public static DocRef.TypedBuilder buildDocRef() {
        return DocRef.builder(TYPE);
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String getData() {
        return data;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final ShapeshifterDoc that = (ShapeshifterDoc) o;
        return Objects.equals(description, that.description) &&
               Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), description, data);
    }

    public Builder copy() {
        return new Builder(this);
    }

    @Override
    public HasData copyWithData(final String data) {
        return copy().data(data).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends AbstractBuilder<ShapeshifterDoc, Builder> {

        private String description;
        private String data;
        private DocRef embeddedIn;

        public Builder() {
        }

        public Builder(final ShapeshifterDoc doc) {
            super(doc);
            this.description = doc.description;
            this.data = doc.data;
            this.embeddedIn = doc.getEmbeddedIn();
        }

        public Builder description(final String description) {
            this.description = description;
            return self();
        }

        public Builder data(final String data) {
            this.data = data;
            return self();
        }

        public Builder embeddedIn(final DocRef embeddedIn) {
            this.embeddedIn = embeddedIn;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        public ShapeshifterDoc build() {
            return new ShapeshifterDoc(
                    uuid,
                    name,
                    version,
                    createTimeMs,
                    updateTimeMs,
                    createUser,
                    updateUser,
                    description,
                    data,
                    embeddedIn);
        }
    }
}
