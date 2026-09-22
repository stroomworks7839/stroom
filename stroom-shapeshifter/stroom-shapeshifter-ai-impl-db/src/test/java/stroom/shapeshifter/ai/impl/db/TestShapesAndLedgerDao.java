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

package stroom.shapeshifter.ai.impl.db;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/// The shape state of A26 and the ledger of §5.2 as rows.
class TestShapesAndLedgerDao {

    private static final String DOC = "doc-" + System.nanoTime();
    private static final String SHAPE = "Feed=DOOR-ACCESS|Type=Raw Events";

    @Inject
    private ShapesDao shapes;
    @Inject
    private LedgerDao ledger;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        shapes.reset(DOC, SHAPE);
        ledger.release(DOC, SHAPE);
    }

    @Test
    void aShapeRemembersWhyItWasGivenUpMarkedOrHeldForReview() {
        assertThat(shapes.reasonGivenUp(DOC, SHAPE)).describedAs("a shape nothing has said anything about")
                .isEmpty();

        shapes.giveUp(DOC, SHAPE, "Rejected: the wrong parser");
        shapes.markForRelearning(DOC, SHAPE, "Rolling score 0.6 fell below 0.8");
        shapes.awaitReview(DOC, SHAPE, "rule-1");

        assertThat(shapes.reasonGivenUp(DOC, SHAPE)).contains("Rejected: the wrong parser");
        assertThat(shapes.relearnReason(DOC, SHAPE)).contains("Rolling score 0.6 fell below 0.8");
        assertThat(shapes.draftAwaiting(DOC, SHAPE)).contains("rule-1");
        assertThat(shapes.shapeAwaiting(DOC, "rule-1")).contains(SHAPE);
        assertThat(shapes.shapeAwaiting(DOC, "rule-2")).isEmpty();

        shapes.reset(DOC, SHAPE);
        assertThat(shapes.reasonGivenUp(DOC, SHAPE)).isEmpty();
        assertThat(shapes.relearnReason(DOC, SHAPE)).isEmpty();
        assertThat(shapes.draftAwaiting(DOC, SHAPE)).isEmpty();
    }

    @Test
    void theRollingScoreIsOfWhatTheShapeHasDoneLately() {
        // Nothing is said until the shape has brought enough records to rest a judgement on.
        assertThat(shapes.scored(DOC, SHAPE, 1.0, 4, 10)).isEmpty();
        assertThat(shapes.scored(DOC, SHAPE, 1.0, 4, 10)).isEmpty();

        final OptionalDouble rolling = shapes.scored(DOC, SHAPE, 0.0, 4, 10);

        assertThat(rolling).isPresent();
        assertThat(rolling.getAsDouble()).describedAs("eight good records, then four bad, remembering ten")
                .isEqualTo(8.0 / 12);
        // Reset starts it afresh, as a promotion or a retraction does.
        shapes.reset(DOC, SHAPE);
        assertThat(shapes.scored(DOC, SHAPE, 1.0, 1, 1)).hasValue(1.0);
    }

    @Test
    void theLedgerNamesWhatToReplayAndReleasesItOnce() {
        ledger.sentinelled(DOC, SHAPE, 1L, "Unknown shape");
        ledger.sentinelled(DOC, SHAPE, 2L, "Unknown shape");
        ledger.sentinelled(DOC, "another-shape", 3L, "Unknown shape");

        final List<Long> released = ledger.release(DOC, SHAPE);

        assertThat(released).describedAs("oldest first: the backlog is replayed in order").containsExactly(1L, 2L);
        assertThat(ledger.release(DOC, SHAPE))
                .describedAs("a second release finds nothing: two nodes must not both replay it")
                .isEmpty();
        assertThat(ledger.release(DOC, "another-shape")).containsExactly(3L);
    }
}
