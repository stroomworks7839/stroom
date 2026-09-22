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

package stroom.shapeshifter.shared;

import stroom.docref.HasDisplayValue;

import java.util.List;

/// The plans as measured (design 02 §6.3), which the Learning tab loads into a document's steps and nothing
/// more (A34, A37) — a document owns its steps, and these are three it might start from. `DIRECT` asks for
/// the chain and then each element's configuration (A21), and is what a new document starts with.
/// `TARGET_FIRST` first settles the record boundary, then asks what one record of each kind should become,
/// and holds every configuration to those events (A31), a value the records lack sending the parser back.
/// `ESCALATING` starts direct and asks for a target only when the transform stays short.
public enum PlanExample implements HasDisplayValue {
    DIRECT("Direct", List.of(
            PlanStep.parse("CHAIN"),
            PlanStep.parse("CONFIGURE"))),
    TARGET_FIRST("Target first", List.of(
            PlanStep.parse("CHAIN"),
            PlanStep.parse("SPLIT"),
            PlanStep.parse("TARGET kinds 3"),
            PlanStep.parse("CONFIGURE parser"),
            PlanStep.parse("CONFIGURE transform on preservation-short goto parser"))),
    ESCALATING("Escalating", List.of(
            PlanStep.parse("CHAIN"),
            // JSON's records are the items of an array only the split can name: without it a document is
            // one record, to the count, the target and yield alike (design 02 §6.3, run 7).
            PlanStep.parse("SPLIT when json"),
            PlanStep.parse("CONFIGURE parser"),
            PlanStep.parse("first: CONFIGURE transform candidates 2 on passed goto end on spent goto target"),
            PlanStep.parse("TARGET kinds 3"),
            PlanStep.parse("again: CONFIGURE parser"),
            PlanStep.parse("CONFIGURE transform on preservation-short goto again")));

    private final String displayValue;
    private final List<PlanStep> steps;

    PlanExample(final String displayValue, final List<PlanStep> steps) {
        this.displayValue = displayValue;
        this.steps = steps;
    }

    /// The example's steps, in order: what a document loading it gets as its own.
    public List<PlanStep> steps() {
        return steps;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
