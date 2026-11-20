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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.config.Template;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestGroupHues {

    // A template binding $1 to key and the label `val` to value, beside panes that coloured its
    // key and some other template's value, and allocated three colours in all
    private static final Template PAIR = Templates.withCaptures(Templates.create("pair", null, false), List.of(
            new CaptureBinding("key", new CaptureSource.Group(1), null),
            new CaptureBinding("value", new CaptureSource.Label("val"), null)));
    private static final Map<String, String> PANES = Map.of(
            "t1/key", "hsl(1, 62%, 30%)",
            "other/value", "hsl(2, 62%, 30%)");

    private static GroupHues hues(final String templateId, final Template template) {
        return new GroupHues(templateId, template, (id, name) -> PANES.get(id + "/" + name), 3);
    }

    @Test
    void aGroupBoundToAVariableTakesTheVariablesColour() {
        assertThat(hues("t1", PAIR).hue(1, null)).isEqualTo("hsl(1, 62%, 30%)");
    }

    @Test
    void aGroupBoundToNothingIsNumberedPastThePanes() {
        // Never one of the three the panes hold, so it is never taken for one of their variables
        assertThat(hues("t1", PAIR).hue(2, null)).isEqualTo(Colours.hue(5));
    }

    @Test
    void aVariableNothingInScopeBoundIsNumberedPastThePanesToo() {
        // `value` is bound by label; the panes coloured another template's `value`, not this one's
        assertThat(hues("t1", PAIR).hue(4, "val")).isEqualTo(Colours.hue(7));
    }

    @Test
    void aTreesLabelFindsItsVariable() {
        final GroupHues hues = new GroupHues("t1", PAIR, (id, name) -> "value".equals(name)
                ? "#123456"
                : null, 3);
        assertThat(hues.hue(4, "val")).isEqualTo("#123456");
    }

    @Test
    void beforeAnyRunEveryGroupIsNumberedFromOne() {
        assertThat(GroupHues.NONE.hue(1, null)).isEqualTo(Colours.hue(1));
        assertThat(new GroupHues("t1", PAIR, null).hue(1, null)).isEqualTo(Colours.hue(1));
    }

    @Test
    void coloursAreTheSameOnlyOverTheSameSubjectAndAllocation() {
        // The workbench draws again only when one of these moved: a run or a step makes a new
        // allocation, and an edit a new template
        final GroupHues before = new GroupHues("t1", PAIR, null);
        assertThat(before.sameAs(new GroupHues("t1", PAIR, null))).isTrue();
        assertThat(before.sameAs(new GroupHues("t2", PAIR, null))).isFalse();
        assertThat(before.sameAs(new GroupHues("t1", Templates.create("pair", null, false), null))).isFalse();
        assertThat(before.sameAs(null)).isFalse();
    }

    @Test
    void aPartOfTheLibraryHasNoVariables() {
        assertThat(hues(null, null).hue(1, null)).isEqualTo(Colours.hue(4));
    }
}
