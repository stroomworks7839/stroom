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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// Where a step goes on one outcome (A37; design 01 §10.2), written `on <outcome> goto <step>`,
/// `on <outcome> abandon`, `on spent goto <step>` or `on spent abandon`. A transition on an outcome
/// fires at once, on the first candidate that has it; a transition on `spent` fires when the step's
/// candidates are gone, where the default is to abandon. Self re-ask on a shortfall needs no transition:
/// it is what a step does until it is spent, and a pass goes to the next line unless `on passed goto`
/// says otherwise. The step named [#END] is the end of the plan.
@JsonPropertyOrder({"on", "goTo"})
@JsonInclude(Include.NON_NULL)
public class Transition {

    public static final String SPENT = "spent";
    public static final String ON = "on";
    public static final String GOTO = "goto";
    public static final String ABANDON = "abandon";
    /// The reserved step name a `goto` reaches when the plan is complete.
    public static final String END = "end";

    /// The outcome this fires on, or null for `spent`.
    @JsonProperty
    private final StepOutcome on;
    /// The id of the step to go to, or null to abandon the attempt.
    @JsonProperty
    private final String goTo;

    @JsonCreator
    public Transition(@JsonProperty("on") final StepOutcome on,
                      @JsonProperty("goTo") final String goTo) {
        this.on = on;
        this.goTo = goTo == null || goTo.trim().isEmpty()
                ? null
                : goTo.trim();
    }

    public StepOutcome getOn() {
        return on;
    }

    public String getGoTo() {
        return goTo;
    }

    /// Whether this fires when the candidates are spent rather than on an outcome.
    public boolean onSpent() {
        return on == null;
    }

    /// Whether this abandons the attempt rather than going to a step.
    public boolean abandons() {
        return goTo == null;
    }

    /// The transition as it is written: `on coverage-short goto parser`, `on spent abandon`.
    public String format() {
        return ON + ' ' + (on == null
                ? SPENT
                : on.getDisplayValue()) + ' ' + (goTo == null
                ? ABANDON
                : GOTO + ' ' + goTo);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Transition that = (Transition) o;
        return on == that.on && Objects.equals(goTo, that.goTo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(on, goTo);
    }

    @Override
    public String toString() {
        return format();
    }
}
