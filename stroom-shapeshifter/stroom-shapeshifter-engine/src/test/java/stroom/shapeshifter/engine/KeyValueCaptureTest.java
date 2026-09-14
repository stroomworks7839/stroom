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

package stroom.shapeshifter.engine;

import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.output.XmlByteSink;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Key-value captures, which had no unit test at all (design 35 §12, phase 0).
 *
 * <p>Two of these pin behaviour that design 35 phase 3 changes on purpose — a name a record did
 * not write reads the previous record's value, and a later token of an earlier record beats this
 * record's own binding. They are here so that the change is a deliberate flip of a failing test,
 * not a silent one. E49 records the defect; design 35 §5 records why a map var removes it.
 */
class KeyValueCaptureTest {

    /** The plain case: a record's pairs are readable by name in the template that dispatched them. */
    @Test
    void pairsAreReadableByNameInTheParent() {
        assertThat(run(CONFIG, "a=1 b=2\n")).isEqualTo("[a=1,b=2,k=]");
    }

    /**
     * Pins E49's second demonstration. The middle record has no {@code k=}, and reads the
     * previous record's. Design 35 phase 3 flips this: a map declared on the record template
     * dies with the record.
     */
    @Test
    void nameNotWrittenThisRecordReadsThePreviousRecords() {
        assertThat(run(CONFIG, "k=one\nother=two\nk=three\n"))
                .isEqualTo("[a=,b=,k=one][a=,b=,k=one][a=,b=,k=three]");
    }

    /**
     * Pins E49's third demonstration, the one that is not a lifetime question. The second record
     * binds {@code key} and still reads {@code old}: the store is indexed by token position, so
     * {@code old} at token 3 outranks {@code new} at token 1 for {@code latest()}. Design 35 phase
     * 3 flips this: a map has keys, not positions.
     */
    @Test
    void laterTokenOfAnEarlierRecordBeatsThisRecordsBinding() {
        assertThat(run(KEY_CONFIG, "a=1 b=2 key=old\nkey=new\n"))
                .isEqualTo("[key=old][key=old]");
    }

    private static final String CONFIG = config("""
            {"text": "[a="}, {"capture": {"var_id": "a", "group": 0}},
            {"text": ",b="}, {"capture": {"var_id": "b", "group": 0}},
            {"text": ",k="}, {"capture": {"var_id": "k", "group": 0}}, {"text": "]"}""");

    private static final String KEY_CONFIG = config("""
            {"text": "[key="}, {"capture": {"var_id": "key", "group": 0}}, {"text": "]"}""");

    private static String config(final String reads) {
        return """
                {
                  "name": "kv", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                     "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "rec"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "rec", "mode": "rec",
                     "match": {"regex": {"pattern": "[^\\n]*\\n"}},
                     "body": [
                       {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                            "mode": "fld"}},
                       {"value-of": {"parts": [READS]}}]},
                    {"id": "00000000-0000-0000-0000-000000000003", "name": "fld", "mode": "fld",
                     "match": {"regex": {"pattern": "([a-z]+)=([a-z0-9]+)"}},
                     "captures": [{"name": "kv", "select": {"key-value": {
                         "key_ref": {"parts": [{"capture": {"group": 1}}]},
                         "value_ref": {"parts": [{"capture": {"group": 2}}]}}}}],
                     "body": []}
                  ]
                }
                """.replace("READS", reads);
    }

    private static String run(final String json, final String input) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(out));
        return out.toString(StandardCharsets.UTF_8).replace("\n", "");
    }
}
