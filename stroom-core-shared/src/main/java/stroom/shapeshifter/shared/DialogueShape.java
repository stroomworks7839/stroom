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

import stroom.docref.HasDisplayValue;

import java.util.List;

/**
 * The built-in dialogues a definition starts from (rulings A32, A33; design 01 §10.2), each an ordered list of
 * steps. {@code DIRECT} asks for the chain and then each element's configuration, judged on the whole sample
 * (A21). {@code TARGET_FIRST} first settles the record boundary, then asks what one record of each kind should
 * become and holds every configuration to those events (A31). Measured against each other in design 02 §6.3.
 */
public enum DialogueShape implements HasDisplayValue {
    DIRECT("Direct", List.of(
            DialogueStep.of(QuestionKind.CHAIN),
            DialogueStep.of(QuestionKind.CONFIGURE))),
    TARGET_FIRST("Target first", List.of(
            DialogueStep.of(QuestionKind.CHAIN),
            DialogueStep.of(QuestionKind.SPLIT, StepGuard.TEXT),
            new DialogueStep(QuestionKind.TARGET, StepGuard.ALWAYS, null, DialogueStep.DEFAULT_KINDS),
            DialogueStep.of(QuestionKind.CONFIGURE)));

    private final String displayValue;
    private final List<DialogueStep> steps;

    DialogueShape(final String displayValue, final List<DialogueStep> steps) {
        this.displayValue = displayValue;
        this.steps = steps;
    }

    /**
     * The steps of this built-in dialogue, in order.
     */
    public List<DialogueStep> steps() {
        return steps;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
