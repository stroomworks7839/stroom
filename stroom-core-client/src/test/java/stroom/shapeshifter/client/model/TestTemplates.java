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

package stroom.shapeshifter.client.model;

import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.Declaration.Type;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.ParamDecl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestTemplates {

    private static Template template(final String name, final List<Declaration> declarations) {
        return new Template(ProjectText.newId(), name, null, false, null, null, declarations,
                new MatchExpression.Regex("a", null, 0), null, null, List.of(), null, false);
    }

    @Test
    void aCaptureIsOfferedTheNamesDeclaredOnOtherTemplates() {
        // Rung 5 (design 46 §6.4a): `heading` is declared on the document and bound in
        // header_column, which declares nothing — the engine accepts it, so the editor offers it.
        final Template document = template("document", List.of(new Declaration("heading", Type.LIST)));
        final Template column = template("header_column", List.of());
        final Project project = new Project("p", 5, null, List.of(document, column), null);

        assertThat(Templates.declaredNames(project, column)).containsExactly("heading");
    }

    @Test
    void theTemplatesOwnNamesComeFirstAndEachNameOnce() {
        final Template document = template("document", List.of(
                new Declaration("heading", Type.LIST), new Declaration("shared", Type.SCALAR)));
        final Template record = template("data_record", List.of(
                new Declaration("record_body", Type.SCALAR), new Declaration("shared", Type.SCALAR)));
        final Project project = new Project("p", 5, null, List.of(document, record), null);

        assertThat(Templates.declaredNames(project, record))
                .containsExactly("record_body", "shared", "heading");
    }

    @Test
    void nothingDeclaredAndNothingToAskAreBothEmpty() {
        final Template bare = template("bare", List.of());

        assertThat(Templates.declaredNames(new Project("p", 5, null, List.of(bare), null), bare)).isEmpty();
        assertThat(Templates.declaredNames(null, null)).isEmpty();
    }

    @Test
    void aTitleTakenLateKeepsWhatWasDoneToTheBodyMeanwhile() {
        // Design 47 §7.8, finding 1: the title is written onto the template as it is by then
        final Template was = template("pair", List.of());
        final Template retitled = Templates.withIdentity(was, "pairs", "kv", false,
                List.of(new ParamDecl("depth", "1")), "utf-8", true);
        final Template bodyChanged = Templates.withBody(was, List.of(new OutputNode.Text("<pair/>")));

        final Template taken = Templates.withIdentityOf(bodyChanged, retitled);

        assertThat(taken.name()).isEqualTo("pairs");
        assertThat(taken.mode()).isEqualTo("kv");
        assertThat(taken.param()).containsExactly(new ParamDecl("depth", "1"));
        assertThat(taken.encoding()).isEqualTo("utf-8");
        assertThat(taken.ignoreErrors()).isTrue();
        assertThat(taken.body()).isEqualTo(bodyChanged.body());
        assertThat(taken.id()).isEqualTo(was.id());
    }

    @Test
    void theRoleIsNotPartOfAnIdentity() {
        final Template was = template("pair", List.of());
        final Template advanceOnly = Templates.withRole(was, true);

        assertThat(Templates.withIdentityOf(advanceOnly, was).consume()).isTrue();
        assertThat(Templates.sameIdentity(advanceOnly, was)).isTrue();
    }

    @Test
    void anIdentityIsTheSameUntilATitleFieldChanges() {
        final Template was = template("pair", List.of());

        assertThat(Templates.sameIdentity(Templates.withBody(was, List.of(new OutputNode.Text("x"))), was))
                .isTrue();
        assertThat(Templates.sameIdentity(Templates.withIdentity(was, "other", null, false, null, null,
                false), was)).isFalse();
        assertThat(Templates.sameIdentity(Templates.withIdentity(was, "pair", "kv", false, null, null,
                false), was)).isFalse();
        assertThat(Templates.sameIdentity(Templates.withIdentity(was, "pair", null, false, null, "ascii",
                false), was)).isFalse();
    }

    @Test
    void aNameIsTakenOnlyByAnotherTemplate() {
        // Design 47 §7.8, finding 4: a call names a template, so two of a name cannot be told apart
        final Template pair = template("pair", List.of());
        final Template junk = template("junk_line", List.of());
        final Project project = new Project("p", 5, null, List.of(pair, junk), null);

        assertThat(Templates.nameTaken(project, "junk_line", pair.id())).isTrue();
        assertThat(Templates.nameTaken(project, "pair", pair.id())).isFalse();
        assertThat(Templates.nameTaken(project, "pairs", pair.id())).isFalse();
        assertThat(Templates.nameTaken(project, "pair", null)).isTrue();
        assertThat(Templates.nameTaken(null, "pair", null)).isFalse();
    }
}
