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

package stroom.query.api.v2;

public interface SpecialColumns {

    String RESERVED_ID_FIELD_NAME = "__id__";
    String RESERVED_STREAM_ID_FIELD_NAME = "__stream_id__";
    String RESERVED_EVENT_ID_FIELD_NAME = "__event_id__";

    Column ID_COLUMN = buildSpecialColumn(RESERVED_ID_FIELD_NAME,
            ParamSubstituteUtil.makeParam("Id"));
    Column STREAM_ID_COLUMN = buildSpecialColumn(RESERVED_STREAM_ID_FIELD_NAME,
            ParamSubstituteUtil.makeParam("StreamId"));
    Column EVENT_ID_COLUMN = buildSpecialColumn(RESERVED_EVENT_ID_FIELD_NAME,
            ParamSubstituteUtil.makeParam("EventId"));

    static Column buildSpecialColumn(final String reservedColumnName,
                                     final String expression) {
        return Column.builder()
                .id(reservedColumnName)
                .name(reservedColumnName)
                .expression(expression)
                .visible(false)
                .special(true)
                .build();
    }
}
