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

package stroom.shapeshifter.ai.stage;

import stroom.meta.shared.MetaFields;
import stroom.shapeshifter.shared.RoutingFields;

import java.util.HashMap;
import java.util.Map;

/**
 * One stream as the stage receives it: the metadata a selector may match on (A22) and the data.
 *
 * @param id         What names the stream in the ledger and a reprocess request: its meta id in a node.
 * @param feed       The feed name.
 * @param type       The stream type, e.g. {@code Raw Events}.
 * @param attributes The stream's attribute map — the receipt headers.
 * @param data       The stream's content.
 */
public record Input(long id, String feed, String type, Map<String, String> attributes, String data) {

    public Input {
        attributes = Map.copyOf(attributes);
    }

    /**
     * The map a routing selector is evaluated against: the meta fields, whichever routing headers the
     * stream carries, and the record shape signature the stage computed.
     */
    public Map<String, Object> routingAttributes(final String shapeSignature) {
        final Map<String, Object> map = new HashMap<>();
        map.put(MetaFields.FIELD_FEED, feed);
        map.put(MetaFields.FIELD_TYPE, type);
        for (final String header : RoutingFields.HEADERS) {
            final String value = attributes.get(header);
            if (value != null) {
                map.put(header, value);
            }
        }
        map.put(RoutingFields.SHAPE_SIGNATURE.getFldName(), shapeSignature);
        return map;
    }
}
