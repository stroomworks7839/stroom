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

import stroom.shapeshifter.client.model.TraceModel;
import stroom.shapeshifter.shared.ShapeshifterTrace;
import stroom.shapeshifter.shared.ShapeshifterTrace.Capture;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** One colour per capture over everything a cursor sees, and a cap on how deep the tint goes. */
class CaptureHuesTest {

    // The same shape as the workbench's pane-only fixture: a source frame that binds nothing,
    // two lines that each bind one capture, four pairs that each bind two, and one frame under
    // the first pair that binds a capture three binding generations down.
    private static final String INPUT = "host=web01 port=8080\nlevel=warn id=42\n";

    @Test
    void capturesOfDifferentFramesGetDifferentColours() {
        final CaptureHues hues = CaptureHues.inScope(trace(), TraceModel.ROOT);

        // Both are index 0 in their own frame, which is what used to make them the same colour.
        assertThat(hues.hue(2, 0)).isNotEqualTo(hues.hue(4, 0));
    }

    @Test
    void everyDistinctVariableInScopeGetsADistinctColour() {
        final TraceModel trace = trace();
        final CaptureHues hues = CaptureHues.inScope(trace, TraceModel.ROOT);

        // One entry per variable bound in view — the template that bound it and its name — so
        // that this counts variables and not the times they were bound.
        final Map<String, String> byVariable = new HashMap<>();
        for (final long frameId : CaptureHues.reached(trace, TraceModel.ROOT)) {
            final List<Capture> captures = trace.captures(frameId);
            for (int i = 0; i < captures.size(); i++) {
                byVariable.put(trace.frame(frameId).getTemplateId() + "/" + captures.get(i).getName(),
                        hues.hue(frameId, i));
            }
        }
        assertThat(byVariable).isNotEmpty();
        assertThat(new HashSet<>(byVariable.values())).hasSize(byVariable.size());
    }

    @Test
    void oneVariableKeepsOneColourHoweverOftenItIsBound() {
        final CaptureHues hues = CaptureHues.inScope(trace(), TraceModel.ROOT);

        // $line bound by both line frames, and $key by all four pairs. A colour per occurrence
        // would make a four-hundred-line sample four hundred colours, which identifies nothing —
        // the tint exists so that an address or a timestamp is recognisable as one.
        assertThat(hues.hue(2, 0)).isEqualTo(hues.hue(3, 0));
        assertThat(hues.hue(4, 0)).isEqualTo(hues.hue(6, 0));
        // and the key and the value of one pair are still told apart
        assertThat(hues.hue(4, 0)).isNotEqualTo(hues.hue(4, 1));
    }

    @Test
    void aVariableIsAskedForByItsTemplateAndName() {
        // The pattern workbench colours a group bound to a variable as the panes colour the
        // variable's captures, and numbers the rest past what is allocated
        final TraceModel trace = trace();
        final CaptureHues hues = CaptureHues.inScope(trace, TraceModel.ROOT);
        final String template = trace.frame(4).getTemplateId();
        final String name = trace.captures(4).get(1).getName();

        assertThat(hues.variableHue(template, name)).isEqualTo(hues.hue(4, 1));
        assertThat(CaptureHues.variableOf(trace, 6, 1)).isEqualTo(template + "/" + name);
        assertThat(hues.variableHue(template, "nothing-bound-this")).isNull();
        assertThat(hues.allocated()).isEqualTo(new HashSet<>(allHues(trace, hues)).size());
    }

    private static List<String> allHues(final TraceModel trace, final CaptureHues hues) {
        final List<String> all = new ArrayList<>();
        for (final long frameId : CaptureHues.reached(trace, TraceModel.ROOT)) {
            for (int i = 0; i < trace.captures(frameId).size(); i++) {
                all.add(hues.hue(frameId, i));
            }
        }
        for (final long frameId : trace.path(TraceModel.ROOT)) {
            for (int i = 0; i < trace.captures(frameId).size(); i++) {
                all.add(hues.hue(frameId, i));
            }
        }
        return all;
    }

    @Test
    void capturesWithNowhereToBeDrawnDoNotSpendAGeneration() {
        // A key-value pair's captures are reported with no offset, because the bytes are not a
        // slice of the frame's content (Instrument.NOT_A_SLICE). They are rows in the variables
        // pane and nothing in the text, so a frame of them is not a generation of ranges — were
        // it counted, the frames below it would be pruned for ranges that were never drawn.
        final List<Frame> frames = Arrays.asList(
                frame(1, 0, "source", 0, INPUT.length()),
                frame(2, 1, "kv", 0, 21),
                frame(3, 2, "field", 0, 4),
                frame(4, 3, "inner", 0, 2));
        final List<Capture> captures = Arrays.asList(
                unplaced(2, "host"),
                unplaced(2, "port"),
                capture(3, "name", 0, 4),
                capture(4, "head", 0, 2));
        final TraceModel trace = new TraceModel(new ShapeshifterTrace(true, true, INPUT, "", frames,
                captures, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), 7L,
                Collections.emptyList(), Collections.emptyList(), 412_000L));

        assertThat(CaptureHues.reached(trace, TraceModel.ROOT)).contains(3L, 4L);
        // and the unplaced captures are still coloured, for the pane that does list them
        assertThat(CaptureHues.inScope(trace, TraceModel.ROOT).hue(2, 0)).isNotNull();
    }

    @Test
    void thePathsColoursDoNotDependOnWhatLiesBelowTheCursor() {
        final TraceModel trace = trace();
        final CaptureHues all = CaptureHues.inScope(trace, 4L);
        final CaptureHues path = CaptureHues.onPath(trace, 4L);

        // The variables pane builds the cheaper one, so the two have to agree on the path.
        assertThat(path.hue(4, 0)).isEqualTo(all.hue(4, 0));
        assertThat(path.hue(2, 0)).isEqualTo(all.hue(2, 0));
        assertThat(path.inReach(4)).isFalse();
    }

    @Test
    void theTintReachesTwoBindingGenerationsAndStops() {
        final TraceModel trace = trace();

        // Frames 1 (source) and 0 (the document) bind nothing, so the lines are the first
        // generation and the pairs the second. Frame 8 is the third and is out.
        assertThat(CaptureHues.reached(trace, TraceModel.ROOT))
                .contains(2L, 3L, 4L, 5L, 6L, 7L)
                .doesNotContain(8L);
    }

    @Test
    void aFrameThatBindsNothingDoesNotSpendAGeneration() {
        final TraceModel trace = trace();

        // The source frame binds nothing and sits between the document and the lines; were
        // generations counted by frames rather than by the frames that bind, the pairs would be
        // the third generation and the pane would show one lot of ranges instead of two.
        assertThat(CaptureHues.reached(trace, TraceModel.ROOT)).contains(1L, 4L);
    }

    @Test
    void theCapRebasesOnTheCursor() {
        final TraceModel trace = trace();

        // From a line, the pair below it is the second generation and the frame below that the
        // third — so what was invisible at the document is visible here, and something else is
        // not. Two levels of look-ahead wherever the cursor stands.
        assertThat(CaptureHues.reached(trace, 2L))
                .contains(2L, 4L, 5L)
                .doesNotContain(8L);
        assertThat(CaptureHues.reached(trace, 4L))
                .contains(4L, 8L);
    }

    @Test
    void aCaptureOutOfScopeHasNoColour() {
        final CaptureHues hues = CaptureHues.inScope(trace(), TraceModel.ROOT);

        assertThat(hues.hue(8, 0)).isNull();
        assertThat(hues.inReach(8)).isFalse();
    }

    @Test
    void anAncestorsCapturesAreColouredButNotTinted() {
        // With the cursor on a pair, the line above it is on the path: the variables pane lists
        // its captures, so they need a colour, but the content pane does not tint them.
        final CaptureHues hues = CaptureHues.inScope(trace(), 4L);

        assertThat(hues.hue(2, 0)).isNotNull();
        assertThat(hues.inReach(2)).isFalse();
    }

    private static TraceModel trace() {
        final List<Frame> frames = Arrays.asList(
                frame(1, 0, "source", 0, INPUT.length()),
                frame(2, 1, "line", 0, 21),
                frame(3, 1, "line", 21, 17),
                frame(4, 2, "pair", 0, 10),
                frame(5, 2, "pair", 11, 9),
                frame(6, 3, "pair", 0, 10),
                frame(7, 3, "pair", 11, 5),
                frame(8, 4, "hostname", 5, 5));
        final List<Capture> captures = Arrays.asList(
                capture(2, "line", 0, 20),
                capture(3, "line", 0, 16),
                capture(4, "key", 0, 4),
                capture(4, "value", 5, 5),
                capture(5, "key", 0, 4),
                capture(5, "value", 5, 4),
                capture(6, "key", 0, 5),
                capture(6, "value", 6, 4),
                capture(7, "key", 0, 2),
                capture(7, "value", 3, 2),
                capture(8, "short", 0, 3));
        return new TraceModel(new ShapeshifterTrace(true, true, INPUT, "", frames, captures,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), 7L,
                Collections.emptyList(), Collections.emptyList(), 412_000L));
    }

    private static Frame frame(final long id, final long parentId, final String templateName,
                               final int contentOffset, final int contentLength) {
        return new Frame(id, parentId, "tpl-" + templateName, templateName, 0, 0,
                contentOffset, contentLength, contentOffset, contentLength, null);
    }

    private static Capture capture(final long frameId, final String name, final int contentOffset,
                                   final int contentLength) {
        return new Capture(frameId, name, name, "scalar", contentOffset, contentLength);
    }

    /// A capture whose bytes are not a slice of its frame's content, as a key-value pair's are not.
    private static Capture unplaced(final long frameId, final String name) {
        return new Capture(frameId, name, name, "scalar", -1, 0);
    }
}
