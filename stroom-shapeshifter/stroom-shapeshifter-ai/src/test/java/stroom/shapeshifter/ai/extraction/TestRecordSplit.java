/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.ai.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §12 item 25: the documents a fragment's {@code SplitFilter} hands its transform — one per record, with
 * the parent element structure replicated above it. Driven by stroom's own filter, so that what is
 * scored is what will run.
 */
class TestRecordSplit {

    private static final String RECORDS = """
            <records xmlns="records:2">
              <record><data name="a" value="1"/></record>
              <record><data name="a" value="2"/></record>
              <record><data name="a" value="3"/></record>
            </records>""";

    @Test
    void theChildrenOfARootAreOneDocumentEach() {
        final List<String> split = RecordSplit.split(RECORDS, 1);

        assertThat(split).hasSize(3);
        assertThat(split.get(0))
                .describedAs("the root is replicated above each record, so each is a document of its own")
                .contains("<records").contains("value=\"1\"").doesNotContain("value=\"2\"");
        assertThat(split.get(2)).contains("value=\"3\"");
    }

    @Test
    void aDeeperSplitTakesWhatSitsThere() {
        final String nested = """
                <records xmlns="records:2">
                  <map>
                    <array key="events">
                      <map><data name="a" value="1"/></map>
                      <map><data name="a" value="2"/></map>
                    </array>
                  </map>
                </records>""";

        final List<String> split = RecordSplit.split(nested, 3);

        assertThat(split).describedAs("an item of an array under a key sits three elements down")
                .hasSize(2);
        assertThat(split.get(0)).contains("<records").contains("<map").contains("value=\"1\"")
                .doesNotContain("value=\"2\"");
    }

    @Test
    void nothingAtThatDepthLeavesTheOuterStructureAndNoRecords() {
        // What the filter itself does, and worth knowing rather than guessing: a depth nothing sits at
        // still emits the structure above it, once, with nothing in it. A caller that took that for the
        // stream would have thrown the records away, so the rule is that fewer than two documents is not
        // a split worth having and the chain runs over the whole.
        final List<String> split = RecordSplit.split(RECORDS, 9);

        assertThat(split).hasSize(1);
        assertThat(split.get(0)).describedAs("the root, and nothing under it")
                .doesNotContain("<record>").doesNotContain("<data");

        assertThat(RecordSplit.split("this is not xml", 1))
                .describedAs("and what will not parse is not this to complain about")
                .isEmpty();
    }
}
