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
 * <p>The pairs go into the map the capture names (design 35 §5), read back by key. Two of these
 * were pinned before phase 3 with the opposite expectation — a name a record did not write read
 * the previous record's value, and a later token of an earlier record beat this record's own
 * binding — and were flipped by it, deliberately: a map declared on the record template dies
 * with the record, and a map has keys, not positions. E49 records the defect.
 */
class KeyValueCaptureTest {

    /** The plain case: a record's pairs are readable by name in the template that dispatched them. */
    @Test
    void pairsAreReadableByNameInTheParent() {
        assertThat(run(CONFIG, "a=1 b=2\n")).isEqualTo("[a=1,b=2,k=]");
    }

    /** E49's second demonstration, flipped: the middle record has no {@code k=}, and reads nothing. */
    @Test
    void nameNotWrittenThisRecordReadsNothing() {
        assertThat(run(CONFIG, "k=one\nother=two\nk=three\n"))
                .isEqualTo("[a=,b=,k=one][a=,b=,k=][a=,b=,k=three]");
    }

    /**
     * E49's third demonstration, flipped: the second record binds {@code key} and reads what it
     * bound. The store used to be indexed by token position, so {@code old} at token 3 outranked
     * {@code new} at token 1; a map has keys, not positions.
     */
    @Test
    void thisRecordsBindingIsWhatThisRecordReads() {
        assertThat(run(KEY_CONFIG, "a=1 b=2 key=old\nkey=new\n"))
                .isEqualTo("[key=old][key=new]");
    }

    private static final String CONFIG = config("""
            {"text": "[a="}, {"get": {"var_id": "kv", "key": "a"}},
            {"text": ",b="}, {"get": {"var_id": "kv", "key": "b"}},
            {"text": ",k="}, {"get": {"var_id": "kv", "key": "k"}}, {"text": "]"}""");

    private static final String KEY_CONFIG = config("""
            {"text": "[key="}, {"get": {"var_id": "kv", "key": "key"}}, {"text": "]"}""");

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
                     "declarations": [{"name": "kv", "type": "map"}],
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
