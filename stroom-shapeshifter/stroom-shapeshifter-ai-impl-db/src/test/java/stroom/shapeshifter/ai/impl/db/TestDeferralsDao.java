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

package stroom.shapeshifter.ai.impl.db;

import stroom.shapeshifter.ai.stage.Deferrals.Deferral;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The deferral rows of design 04 §6: one per stream and document, brought up to date rather than
/// duplicated when the stream is put back again, and gone when it is released.
class TestDeferralsDao {

    private static final String DOC = "doc-" + System.nanoTime();
    private static final String OTHER_DOC = "other-" + System.nanoTime();

    @Inject
    private DeferralsDao deferrals;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        for (final String doc : List.of(DOC, OTHER_DOC)) {
            deferrals.forDocument(doc).forEach(row -> deferrals.released(doc, row.inputId()));
        }
    }

    @Test
    void aStreamPutBackTwiceIsOneRowThatKeepsWhenItFirstWaited() {
        deferrals.deferred(DOC, 7L, "DOOR-ACCESS", "Raw Events", "pipe", 100L, 5, "frag:1",
                "2 of 6 record(s) fell short of the bar", 2);
        final Deferral first = deferrals.forDocument(DOC).getFirst();
        deferrals.deferred(DOC, 7L, "DOOR-ACCESS", "Raw Events", "pipe", 101L, 5, "frag:2",
                "1 of 6 record(s) fell short of the bar", 1);

        final List<Deferral> rows = deferrals.forDocument(DOC);

        assertThat(rows).hasSize(1);
        final Deferral row = rows.getFirst();
        assertThat(row.attempts()).isEqualTo(2);
        assertThat(row.taskId()).isEqualTo(101L);
        assertThat(row.filterId()).isEqualTo(5);
        assertThat(row.failedAgainst()).isEqualTo("frag:2");
        assertThat(row.failingRecords()).isEqualTo(1);
        assertThat(row.reason()).isEqualTo("1 of 6 record(s) fell short of the bar");
        assertThat(row.firstDeferredMs()).isEqualTo(first.firstDeferredMs());
    }

    @Test
    void aStreamWithNoRuleAndNoTaskIsARowOfNulls() {
        deferrals.deferred(DOC, 8L, "LIFT", "Raw Events", null, null, null, null, "no rule for this shape", 0);

        final Deferral row = deferrals.forDocument(DOC).getFirst();

        assertThat(row.pipeline()).isNull();
        assertThat(row.taskId()).isNull();
        assertThat(row.filterId()).isNull();
        assertThat(row.failedAgainst()).isNull();
        assertThat(row.failingRecords()).isZero();
        assertThat(row.attempts()).isEqualTo(1);
    }

    @Test
    void releasedRowsAreGoneAndOtherDocumentsAreUntouched() {
        deferrals.deferred(DOC, 7L, "DOOR-ACCESS", "Raw Events", "pipe", 100L, 5, "frag:1", "short", 2);
        deferrals.deferred(DOC, 9L, "DOOR-ACCESS", "Raw Events", "pipe", 102L, 5, "frag:1", "short", 3);
        deferrals.deferred(OTHER_DOC, 7L, "DOOR-ACCESS", "Raw Events", "pipe", 103L, 6, "frag:9", "short", 1);

        deferrals.released(DOC, 7L);

        assertThat(deferrals.forDocument(DOC)).extracting(Deferral::inputId).containsExactly(9L);
        assertThat(deferrals.forDocument(OTHER_DOC)).extracting(Deferral::inputId).containsExactly(7L);
    }
}
