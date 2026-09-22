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

import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.util.shared.StoredError;

import java.util.ArrayList;
import java.util.List;

/// Running a step the way the fragment will run it (§12 item 25): the `SplitFilter` the fragment carries
/// gives the transform one record at a time, so what is scored must be what a transform run one record at
/// a time produces. A stylesheet that reads the whole document — counting its siblings, asking for its
/// position, pulling a value from the record before it — behaves differently when it is given one record,
/// and the promotion gate has to see that difference before it promotes rather than after.
public final class PerRecord {

    private PerRecord() {
    }

    /// @param depth How deep the records sit, as the rule's boundary carries it.
    /// @return What the step wrote for each record, joined; or the step run over the whole document where
    /// there is nothing to split — one record, or none at that depth — since running the whole of a
    /// single record is running that record.
    public static StepResult run(final StepRunner runner,
                                 final String configuration,
                                 final String input,
                                 final int depth) {
        final List<String> records = RecordSplit.split(input, depth);
        if (records.size() < 2) {
            return runner.run(configuration, input);
        }
        final List<String> written = new ArrayList<>();
        final List<StoredError> diagnostics = new ArrayList<>();
        for (final String record : records) {
            final StepResult result = runner.run(configuration, record);
            diagnostics.addAll(result.diagnostics());
            if (result.output() != null) {
                written.add(result.output());
            }
        }
        return new StepResult(RecordJoin.join(written), List.copyOf(diagnostics));
    }
}
