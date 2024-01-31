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

package stroom.explorer.shared;

import stroom.docref.DocRef;
import stroom.svg.shared.SvgImage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

@JsonInclude(Include.NON_NULL)
public class FindResult {

    @JsonProperty
    private final DocRef docRef;
    @JsonProperty
    private final String path;
    @JsonProperty
    private final SvgImage icon;
    @JsonProperty
    private final boolean isFavourite;

    @JsonCreator
    public FindResult(@JsonProperty("docRef") final DocRef docRef,
                      @JsonProperty("path") final String path,
                      @JsonProperty("icon") final SvgImage icon,
                      @JsonProperty("isFavourite") final boolean isFavourite) {
        this.docRef = docRef;
        this.path = path;
        this.icon = icon;
        this.isFavourite = isFavourite;
    }

    public DocRef getDocRef() {
        return docRef;
    }

    public String getPath() {
        return path;
    }

    public SvgImage getIcon() {
        return icon;
    }

    public boolean getIsFavourite() {
        return isFavourite;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final FindResult that = (FindResult) o;
        return isFavourite == that.isFavourite &&
                Objects.equals(docRef, that.docRef) &&
                Objects.equals(path, that.path) &&
                Objects.equals(icon, that.icon);
    }

    @Override
    public int hashCode() {
        return Objects.hash(docRef, path, icon, isFavourite);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder copy() {
        return new Builder(this);
    }


    // --------------------------------------------------------------------------------


    public static final class Builder {

        private DocRef docRef;
        private String path;
        private SvgImage icon;
        private boolean isFavourite;

        private Builder() {
        }

        private Builder(final FindResult findResult) {
            this.docRef = findResult.docRef;
            this.path = findResult.path;
            this.icon = findResult.icon;
            this.isFavourite = findResult.isFavourite;
        }

        public Builder docRef(final DocRef docRef) {
            this.docRef = docRef;
            return this;
        }

        public Builder path(final String path) {
            this.path = path;
            return this;
        }

        public Builder icon(final SvgImage icon) {
            this.icon = icon;
            return this;
        }

        public Builder isFavourite(final boolean isFavourite) {
            this.isFavourite = isFavourite;
            return this;
        }

        public FindResult build() {
            return new FindResult(
                    docRef,
                    path,
                    icon,
                    isFavourite);
        }
    }
}
