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
import stroom.pipeline.shared.SourceLocation;
import stroom.util.shared.HasData;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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
        "data",
        "colours",
        "sample",
        "sampleText",
        "sampleKind"})
@JsonInclude(Include.NON_NULL)
public class ShapeshifterDoc extends AbstractEmbeddableDoc implements HasData {

    /** Where a project's sample comes from: a stream on this installation, or text in the document. */
    public enum SampleKind {
        STREAM,
        PASTED
    }

    public static final String TYPE = "Shapeshifter";
    public static final DocumentType DOCUMENT_TYPE = DocumentTypeRegistry.SHAPESHIFTER_DOCUMENT_TYPE;

    @JsonProperty
    private final String description;
    /** The project JSON, exactly as the engine's {@code ProjectReader} reads it. */
    @JsonProperty
    private final String data;
    /**
     * Editor metadata (design 18 §5.6): a template's swatch colour where the author chose one,
     * by template id. Presentation, never engine configuration: the project text does not carry
     * it, and the engine never sees it.
     */
    @JsonProperty
    private final Map<String, String> colours;
    /**
     * Editor metadata (design 44 §5j, amending design 18 Q2): where the author last took sample
     * data from, so the editor reopens on it instead of making them find it again. A
     * <b>reference</b>, never the bytes — Q2 stays true of data — and stripped on export, so an
     * exported configuration carries no pointer into an environment where the same id is a
     * different stream, or none. Written when the document is saved, never as a side effect of
     * choosing a sample.
     */
    @JsonProperty
    private final SourceLocation sample;
    /**
     * Sample data the author pasted, kept with the project and <b>carried on export</b> — unlike
     * {@link #sample}, which is stripped (design 44 §5q). The two are the same question answered
     * two ways, and they travel differently for a reason: a stream id means a different stream in
     * another installation, or none, while pasted bytes mean the same everywhere. It is what
     * makes a project portable — an exported configuration arrives able to demonstrate itself.
     *
     * <p>The cost, accepted rather than overlooked: a configuration exported from a live system
     * carries whatever its author pasted into it, so the Data page says so where the pasting
     * happens.
     */
    @JsonProperty
    private final String sampleText;
    /**
     * Which of the two the project is using (design 44 §5t). Both are kept — a look at a stream
     * should not throw away what the author pasted, and returning to the paste should not throw
     * away the stream — so which one is in force is its own fact rather than something inferred
     * from which is present. Stripped on export with the reference it names: an exported
     * configuration cannot honour a stream it no longer has, and lands on its sample text.
     */
    @JsonProperty
    private final SampleKind sampleKind;

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
                           @JsonProperty("colours") final Map<String, String> colours,
                           @JsonProperty("sample") final SourceLocation sample,
                           @JsonProperty("sampleText") final String sampleText,
                           @JsonProperty("sampleKind") final SampleKind sampleKind,
                           @JsonProperty("embeddedIn") final DocRef embeddedIn) {
        super(TYPE, uuid, name, version, createTimeMs, updateTimeMs, createUser, updateUser, embeddedIn);
        this.description = description;
        this.data = data;
        this.colours = colours == null || colours.isEmpty()
                ? null
                : Collections.unmodifiableMap(new HashMap<>(colours));
        this.sample = sample;
        this.sampleText = sampleText == null || sampleText.isEmpty()
                ? null
                : sampleText;
        this.sampleKind = sampleKind;
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

    /** Colour overrides by template id; empty when none were chosen. */
    public Map<String, String> getColours() {
        return colours == null
                ? Collections.emptyMap()
                : colours;
    }

    /** Where sample data was last taken from, or null; a reference the editor may fail to resolve. */
    public SourceLocation getSample() {
        return sample;
    }

    /** Which sample the project is using; null where it has never had one. */
    public SampleKind getSampleKind() {
        return sampleKind;
    }

    /** Sample data pasted by the author, or null; kept with the project and exported with it. */
    public String getSampleText() {
        return sampleText;
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
               Objects.equals(data, that.data) &&
               Objects.equals(colours, that.colours) &&
               Objects.equals(sample, that.sample) &&
               Objects.equals(sampleText, that.sampleText) &&
               sampleKind == that.sampleKind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), description, data, colours, sample, sampleText, sampleKind);
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
        private Map<String, String> colours;
        private SourceLocation sample;
        private String sampleText;
        private SampleKind sampleKind;
        private DocRef embeddedIn;

        public Builder() {
        }

        public Builder(final ShapeshifterDoc doc) {
            super(doc);
            this.description = doc.description;
            this.data = doc.data;
            this.colours = doc.colours;
            this.sample = doc.sample;
            this.sampleText = doc.sampleText;
            this.sampleKind = doc.sampleKind;
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

        public Builder colours(final Map<String, String> colours) {
            this.colours = colours;
            return self();
        }

        public Builder sample(final SourceLocation sample) {
            this.sample = sample;
            return self();
        }

        public Builder sampleText(final String sampleText) {
            this.sampleText = sampleText;
            return self();
        }

        public Builder sampleKind(final SampleKind sampleKind) {
            this.sampleKind = sampleKind;
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
                    colours,
                    sample,
                    sampleText,
                    sampleKind,
                    embeddedIn);
        }
    }
}
