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

package stroom.shapeshifter.ai.fragment;

import stroom.shapeshifter.ai.scoring.Verdict;

/// The bar, record by record (design 04 §3, A55): what a serving run asks of each record of the chain's
/// output as it is produced. The stage supplies it — the gates that judge a record, over the record as
/// one document of its own — and the run stops at the first record that falls short.
public interface RecordJudge {

    /// Judge one record of the output.
    ///
    /// @param index  Which record, from zero, in output order.
    /// @param record The record as a document of its own: the output's root with this record alone in
    ///               it, so that the scorers read it as they read any output.
    /// @return The verdict. A record clears the bar when every gate passed on it.
    Verdict judge(int index, String record);

    /// Whether a verdict clears the bar.
    static boolean clears(final Verdict verdict) {
        return verdict.gatesPassed() && verdict.failingRecords().isEmpty();
    }
}
