/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client.view;

import stroom.shapeshifter.client.presenter.Mark;
import stroom.shapeshifter.client.presenter.Mark.Kind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The marks as a tree of spans over escaped text: nesting kept, straddling clipped, gaps inline. */
class MarksTest {

    private static String render(final String text, final Mark... marks) {
        final List<Mark> list = new ArrayList<>(List.of(marks));
        list.sort(Mark.OUTER_FIRST);
        return Marks.render(text, list).asString();
    }

    @Test
    void textIsEscapedAndAMatchIsASpanWithItsFrame() {
        assertThat(render("a<b>c", new Mark(Kind.MATCH, 7, 1, 4, "#abc", "t #1")))
                .isEqualTo("a<span class=\"ss-m\" data-frame=\"7\" style=\"--hue:#abc\" title=\"t #1\">"
                           + "&lt;b&gt;</span>c");
    }

    @Test
    void captureNestsInsideItsMatchAndFillsIt() {
        assertThat(render("xyz",
                new Mark(Kind.CAPTURE, 1, 0, 3, "red", null),
                new Mark(Kind.MATCH, 1, 0, 3, "blue", null)))
                .isEqualTo("<span class=\"ss-m\" data-frame=\"1\" style=\"--hue:blue\">"
                           + "<span class=\"ss-cap\" style=\"--hue:red\">xyz</span></span>");
    }

    @Test
    void markStraddlingItsOutersEndIsClippedThere() {
        assertThat(render("abcdef",
                new Mark(Kind.MATCH, 1, 0, 3, null, null),
                new Mark(Kind.CAPTURE, 1, 2, 5, null, null)))
                .isEqualTo("<span class=\"ss-m\" data-frame=\"1\">ab<span class=\"ss-cap\">c</span></span>def");
    }

    @Test
    void gapIsAnEmptySpanAtItsPlace() {
        assertThat(render("ab\ncd", new Mark(Kind.GAP, -1, 3, 3, null, "no template matched here")))
                .isEqualTo("ab\n<span class=\"ss-gap\" title=\"no template matched here\"></span>cd");
    }

    @Test
    void theCursorsExtentIsOutsideAMatchThatFillsItAndDimIsItsOwnMark() {
        assertThat(render("abcd",
                new Mark(Kind.MATCH, 2, 1, 3, null, null),
                new Mark(Kind.OWN, 1, 1, 3, null, null),
                new Mark(Kind.DIM, 1, 0, 1, null, null),
                new Mark(Kind.DIM, 1, 3, 4, null, null)))
                .isEqualTo("<span class=\"ss-o-dim\">a</span><span class=\"ss-o-own\">"
                           + "<span class=\"ss-m\" data-frame=\"2\">bc</span></span><span class=\"ss-o-dim\">d</span>");
    }

    @Test
    void marksBeyondTheTextAreClampedAndTitlesEscaped() {
        assertThat(render("ab", new Mark(Kind.OWN, 0, 1, 99, null, "a \"quoted\" <title>")))
                .isEqualTo("a<span class=\"ss-o-own\" title=\"a &quot;quoted&quot; &lt;title&gt;\">b</span>");
    }
}
