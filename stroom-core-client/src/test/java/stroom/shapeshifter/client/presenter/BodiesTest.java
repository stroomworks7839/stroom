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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.Choose;
import stroom.shapeshifter.config.OutputNode.Element;
import stroom.shapeshifter.config.OutputNode.If;
import stroom.shapeshifter.config.OutputNode.Switch;
import stroom.shapeshifter.config.OutputNode.SwitchCase;
import stroom.shapeshifter.config.OutputNode.Text;
import stroom.shapeshifter.config.OutputNode.ValueOf;
import stroom.shapeshifter.config.OutputNode.WhenBranch;
import stroom.shapeshifter.config.json.ProjectJson;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The body editor's rewrites: paths into branches, holders keeping their heads, every result readable. */
class BodiesTest {

    private static final Text A = new Text("a");
    private static final Text B = new Text("b");
    private static final Text C = new Text("c");
    private static final Condition FIRST = new Condition.IsFirst();
    private static final List<OutputNode> BODY = List.of(
            A,
            new If(FIRST, List.of(B)),
            new Choose(List.of(new WhenBranch(FIRST, List.of(C))), List.of(new Element("e", null, false, List.of(A)))));

    @Test
    void pathsAddressCardsAndLists() {
        assertThat(Bodies.get(BODY, Bodies.path("0"))).isEqualTo(A);
        assertThat(Bodies.get(BODY, Bodies.path("1.0.0"))).isEqualTo(B);
        assertThat(Bodies.get(BODY, Bodies.path("2.0.0"))).isEqualTo(C);
        assertThat(Bodies.get(BODY, Bodies.path("2.1.0.0.0"))).isEqualTo(A);
        assertThat(Bodies.list(BODY, Bodies.path("2.1"))).hasSize(1);
        assertThat(Bodies.list(BODY, Bodies.path("0.0"))).as("a leaf has no branches").isNull();
        assertThat(Bodies.get(BODY, Bodies.path("9"))).isNull();
        assertThat(Bodies.path(Bodies.branch(Bodies.path("2"), 1, 0))).isEqualTo("2.1.0");
    }

    @Test
    void rewritesReachBranchesAndKeepHeads() {
        List<OutputNode> next = Bodies.insert(BODY, Bodies.path("1.0"), 0, C);
        assertThat(((If) next.get(1)).then()).containsExactly(C, B);
        assertThat(((If) next.get(1)).test()).isEqualTo(FIRST);
        next = Bodies.replace(next, Bodies.path("2.1.0.0.0"), new ValueOf(ProjectJson.readRefOrName("x")));
        final Element e = (Element) ((Choose) next.get(2)).otherwise().get(0);
        assertThat(e.name()).isEqualTo("e");
        assertThat(e.body().get(0)).isInstanceOf(ValueOf.class);
        next = Bodies.remove(next, Bodies.path("0"));
        assertThat(next.get(0)).isInstanceOf(If.class);
        next = Bodies.move(next, Bodies.path("0"), 1);
        assertThat(next.get(1)).isInstanceOf(If.class);
        assertThat(Bodies.move(next, Bodies.path("1"), 1)).isSameAs(next);
        assertThat(BODY.get(1)).as("the original is untouched").isEqualTo(new If(FIRST, List.of(B)));
    }

    @Test
    void branchesAreOwnedByChooseAndSwitchOnly() {
        final Choose choose = (Choose) BODY.get(2);
        assertThat(Bodies.branchLabel(choose, 0)).isEqualTo("when is-first");
        assertThat(Bodies.branchLabel(choose, 1)).isEqualTo("otherwise");
        assertThat(Bodies.branchIsOwn(choose, 0)).isTrue();
        assertThat(Bodies.branchIsOwn(choose, 1)).isFalse();
        assertThat(Bodies.branchLabel((If) BODY.get(1), 0)).isEqualTo("then");
        final Choose more = Bodies.addWhen(choose, new Condition.IsLast());
        assertThat(more.when()).hasSize(2);
        assertThat(Bodies.removeWhen(more, 0).when().get(0).test()).isEqualTo(new Condition.IsLast());
        final Switch s = new Switch(ProjectJson.readRefOrName("k"), List.of(new SwitchCase("1", List.of(A))),
                List.of());
        assertThat(Bodies.branchLabel(s, 0)).isEqualTo("case 1");
        assertThat(Bodies.withCase(s, 0, "2").cases().get(0).body()).containsExactly(A);
        assertThat(Bodies.addCase(s, "3").cases()).hasSize(2);
    }

    @Test
    void kindsAndSummariesReadFromTheWireForm() {
        assertThat(Instructions.kind(A)).isEqualTo("text");
        assertThat(Instructions.kind(BODY.get(1))).isEqualTo("if");
        assertThat(Instructions.kind(BODY.get(2))).isEqualTo("choose");
        assertThat(Instructions.describe(A)).isEqualTo("\"a\"");
        assertThat(Instructions.describe(BODY.get(1))).isEqualTo("is-first");
        assertThat(Instructions.describe(BODY.get(2))).isEqualTo("1 branch and otherwise");
        assertThat(Instructions.category("text")).isEqualTo(Instructions.Category.OUTPUT);
        assertThat(Instructions.category("trim")).isEqualTo(Instructions.Category.TRANSFORM);
        for (final String kind : Instructions.TRANSFORM_KINDS) {
            assertThat(Instructions.category(kind)).isEqualTo(Instructions.Category.TRANSFORM);
        }
    }
}
