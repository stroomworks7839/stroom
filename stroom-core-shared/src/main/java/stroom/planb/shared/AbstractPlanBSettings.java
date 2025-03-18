/*
 * Copyright 2017 Crown Copyright
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

package stroom.planb.shared;

import stroom.docs.shared.Description;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Objects;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = StateSettings.class, name = "state"),
        @JsonSubTypes.Type(value = TemporalStateSettings.class, name = "temporalState"),
        @JsonSubTypes.Type(value = RangedStateSettings.class, name = "rangedState"),
        @JsonSubTypes.Type(value = TemporalRangedStateSettings.class, name = "temporalRangedState"),
        @JsonSubTypes.Type(value = SessionSettings.class, name = "session")
})
@Description("Defines settings for Plan B")
@JsonPropertyOrder({
        "maxStoreSize"
})
@JsonInclude(Include.NON_NULL)
public abstract class AbstractPlanBSettings {

    @JsonProperty
    private final Long maxStoreSize;

    public AbstractPlanBSettings(final Long maxStoreSize) {
        this.maxStoreSize = maxStoreSize;
    }

    public Long getMaxStoreSize() {
        return maxStoreSize;
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
        final AbstractPlanBSettings doc = (AbstractPlanBSettings) o;
        return Objects.equals(maxStoreSize, doc.maxStoreSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), maxStoreSize);
    }

    @Override
    public String toString() {
        return "AbstractPlanBSettings{" +
               ", maxStoreSize=" + maxStoreSize +
               '}';
    }

    public abstract static class AbstractBuilder<T extends AbstractPlanBSettings, B extends AbstractBuilder<T, ?>> {

        protected Long maxStoreSize;

        public AbstractBuilder() {
        }

        public AbstractBuilder(final AbstractPlanBSettings settings) {
            this.maxStoreSize = settings.maxStoreSize;
        }

        public B maxStoreSize(final Long maxStoreSize) {
            this.maxStoreSize = maxStoreSize;
            return self();
        }

        protected abstract B self();

        public abstract T build();
    }
}
