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

import stroom.meta.shared.MetaFields;
import stroom.query.api.datasource.QueryField;

import java.util.List;

/**
 * The fields a routing selector may be written against (proposed ruling A22) and a learning key may be
 * drawn from (ruling A29), defined once so that what the editor offers, what the matcher evaluates and
 * what the chain question shows the model cannot drift: the stream's meta fields, the receipt headers
 * that say what the data is and where it came from, and the record shape signature the supervisor
 * computes (§5).
 * <p>
 * The header names are Stroom's standard header arguments, restated here because that class is not
 * shared with the client; the server side checks the two agree.
 */
public final class RoutingFields {

    public static final String FORMAT = "Format";
    public static final String SCHEMA = "Schema";
    public static final String COMPRESSION = "Compression";
    public static final String SYSTEM = "System";
    public static final String ENVIRONMENT = "Environment";
    public static final String REMOTE_FILE = "RemoteFile";

    /**
     * The receipt headers a selector may match and the shape question may see, strongest signal first:
     * {@code Format} names the parser, {@code Schema} the transform's target.
     */
    public static final List<String> HEADERS = List.of(
            FORMAT, SCHEMA, COMPRESSION, SYSTEM, ENVIRONMENT, REMOTE_FILE);

    public static final QueryField SHAPE_SIGNATURE = QueryField.createText(RoutingRule.SHAPE_SIGNATURE_FIELD);

    /**
     * The learning key a document starts with (A29): most bindings switch on feed and type alone, and
     * the signature is added where one feed carries several kinds of record.
     */
    public static final List<String> DEFAULT_LEARNING_KEY = List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE);

    public static final List<QueryField> FIELDS = List.of(
            MetaFields.FEED,
            MetaFields.TYPE,
            QueryField.createText(FORMAT),
            QueryField.createText(SCHEMA),
            QueryField.createText(COMPRESSION),
            QueryField.createText(SYSTEM),
            QueryField.createText(ENVIRONMENT),
            QueryField.createText(REMOTE_FILE),
            SHAPE_SIGNATURE);

    /**
     * The names of {@link #FIELDS}: what a learning key's entries are validated against.
     */
    public static final List<String> NAMES = FIELDS.stream().map(QueryField::getFldName).toList();

    private RoutingFields() {
    }
}
