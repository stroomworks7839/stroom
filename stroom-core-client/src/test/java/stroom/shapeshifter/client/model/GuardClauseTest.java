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

package stroom.shapeshifter.client.model;

import stroom.shapeshifter.client.model.GuardClause.Join;
import stroom.shapeshifter.client.model.GuardClause.Op;
import stroom.shapeshifter.config.Cast;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.Condition.Compare;
import stroom.shapeshifter.config.Condition.Literal;
import stroom.shapeshifter.config.Condition.Operand;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.RefExpression.RefPart;
import stroom.shapeshifter.config.json.ProjectJson;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The guard editor's rows: what they read, what they write, and what they refuse. */
class GuardClauseTest {

    @Test
    void clausesAreValuesSoRowsAndModelCanBeCompared() {
        // The guard form rebuilds its rows only when the model disagrees with them.
        assertThat(new GuardClause("x", Op.EQ, "1", null)).isEqualTo(new GuardClause("x", Op.EQ, "1", null))
                .hasSameHashCodeAs(new GuardClause("x", Op.EQ, "1", null));
        assertThat(new GuardClause("x", Op.EQ, "1", null)).isNotEqualTo(new GuardClause("x", Op.NE, "1", null));
        assertThat(GuardClause.read(GuardClause.write(
                List.of(new GuardClause("x", Op.EQ, "1", null)), Join.ALL)).rows())
                .containsExactly(new GuardClause("x", Op.EQ, "1", null));
    }

    @Test
    void rowsReadAndWriteTheCommonGuard() {
        final Condition guard = new Condition.And(List.of(
                new Compare(Compare.Op.GE,
                        new Operand(ProjectJson.readRefOrName("status"), null, Cast.NUMBER),
                        new Operand(null, new Literal.Whole(400), null)),
                new Condition.Exists(ProjectJson.readRefOrName("user")),
                new Condition.Matches(ProjectJson.readRefOrName("index()"), "^[0-9]+$"),
                new Condition.IsFirst()));
        final List<GuardClause> rows = GuardClause.read(guard).rows();
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).getVariable()).isEqualTo("status");
        assertThat(rows.get(0).getOp()).isEqualTo(Op.GE);
        assertThat(rows.get(0).getValue()).isEqualTo("400");
        assertThat(rows.get(0).getAs()).isEqualTo(Cast.NUMBER);
        assertThat(rows.get(2).getVariable()).isEqualTo("index()");
        assertThat(rows.get(3).getOp()).isEqualTo(Op.IS_FIRST);
        assertThat(GuardClause.write(rows, Join.ALL)).isEqualTo(guard);
        assertThat(GuardClause.describe(guard))
                .isEqualTo("status ge 400 and user exists and index() matches ^[0-9]+$ and is-first");
    }

    @Test
    void oneRowIsTheConditionItselfAndNoRowsIsNoGuard() {
        final Condition one = new Condition.Contains(ProjectJson.readRefOrName("path"), "/admin");
        assertThat(GuardClause.write(GuardClause.read(one).rows(), Join.ALL)).isEqualTo(one);
        assertThat(GuardClause.read(null).rows()).isEmpty();
        assertThat(GuardClause.write(List.of(), Join.ALL)).isNull();
        assertThat(GuardClause.describe(null)).isEqualTo("no guard");
    }

    @Test
    void theValueSpellingDeclaresTheLiteralType() {
        assertThat(GuardClause.literal("42")).isEqualTo(new Literal.Whole(42));
        assertThat(GuardClause.literal("-4.5")).isEqualTo(new Literal.Fractional(-4.5));
        assertThat(GuardClause.literal("true")).isEqualTo(new Literal.Truth(true));
        assertThat(GuardClause.literal("\"42\"")).isEqualTo(new Literal.Text("42"));
        assertThat(GuardClause.literal("GET")).isEqualTo(new Literal.Text("GET"));
        // And it round-trips: a text "42" comes back quoted, so it stays text.
        final Condition c = new Compare(Compare.Op.EQ,
                new Operand(ProjectJson.readRefOrName("code"), null, null),
                new Operand(null, new Literal.Text("42"), null));
        assertThat(GuardClause.read(c).rows().get(0).getValue()).isEqualTo("\"42\"");
        assertThat(GuardClause.write(GuardClause.read(c).rows(), Join.ALL)).isEqualTo(c);
    }

    @Test
    void anOrReadsAsRowsJoinedByAny() {
        // 51 of the corpus's 476 conditions are an or, and before design 46 §7.2 every one of
        // them was typed as JSON.
        final Condition or = new Condition.Or(List.of(new Condition.IsFirst(), new Condition.IsLast()));
        final GuardClause.Rows read = GuardClause.read(or);
        assertThat(read.join()).isEqualTo(Join.ANY);
        assertThat(read.rows()).hasSize(2);
        assertThat(GuardClause.write(read.rows(), read.join())).isEqualTo(or);
        assertThat(GuardClause.describe(or)).isEqualTo("is-first or is-last");
    }

    @Test
    void aNegatedClauseReadsAsANegatedRow() {
        // 115 of them, the single commonest shape after the bare clause.
        final RefExpression a = ProjectJson.readRefOrName("a");
        final Condition not = new Condition.Not(new Condition.Exists(a));
        final GuardClause.Rows read = GuardClause.read(not);
        assertThat(read.rows()).hasSize(1);
        assertThat(read.rows().get(0).isNegated()).isTrue();
        assertThat(read.rows().get(0).getOp()).isEqualTo(Op.EXISTS);
        assertThat(GuardClause.write(read.rows(), read.join())).isEqualTo(not);
        assertThat(GuardClause.describe(not)).isEqualTo("a not exists");
    }

    @Test
    void anOrOfPlainAndNegatedRowsRoundTrips() {
        // The shape apache_httpd uses 30 times: an or whose parts are clauses and negations.
        final RefExpression a = ProjectJson.readRefOrName("a");
        final Condition or = new Condition.Or(List.of(
                new Condition.Contains(a, "x"),
                new Condition.Not(new Condition.Contains(a, "y"))));
        final GuardClause.Rows read = GuardClause.read(or);
        assertThat(read.join()).isEqualTo(Join.ANY);
        assertThat(read.rows().get(0).isNegated()).isFalse();
        assertThat(read.rows().get(1).isNegated()).isTrue();
        assertThat(GuardClause.write(read.rows(), read.join())).isEqualTo(or);
    }

    @Test
    void whatTheRowsStillCannotExpressReadsAsNull() {
        final RefExpression a = ProjectJson.readRefOrName("a");
        // A negation of anything but a clause: the rows have one level, and rewriting a double
        // negative into the clause it equals would change the configuration behind the author.
        final Condition twice = new Condition.Not(new Condition.Not(new Condition.Exists(a)));
        assertThat(GuardClause.read(twice)).isNull();
        final Condition nested = new Condition.Not(new Condition.And(List.of(
                new Condition.IsFirst(), new Condition.IsLast())));
        assertThat(GuardClause.read(nested)).isNull();
        final Condition twoRefs = new Compare(Compare.Op.EQ, new Operand(a, null, null), new Operand(a, null, null));
        assertThat(GuardClause.read(twoRefs)).isNull();
        final RefExpression path = new RefExpression(List.of(new RefPart.Capture("a", 0, null),
                new RefPart.Accessor(RefPart.Accessor.Kind.SIZE, new RefExpression(List.of(
                        new RefPart.Capture("a", 0, null))), null, null, null)));
        assertThat(GuardClause.read(new Condition.Exists(path))).isNull();
        assertThat(GuardClause.describe(twice)).isEqualTo("guarded (see workbench)");
    }

    @Test
    void unfinishedRowIsRefused() {
        assertThatThrownBy(() -> new GuardClause("", Op.EQ, "1", null).toCondition())
                .isInstanceOf(ConfigException.class).hasMessageContaining("variable");
        assertThatThrownBy(() -> new GuardClause("x", Op.MATCHES, "", null).toCondition())
                .isInstanceOf(ConfigException.class).hasMessageContaining("pattern");
        assertThat(new GuardClause("", Op.IS_LAST, "", null).toCondition()).isEqualTo(new Condition.IsLast());
        assertThat(new GuardClause("", Op.EQ, "", null).isBlank()).isTrue();
        assertThat(new GuardClause("x", Op.EQ, "", null).isBlank()).isFalse();
        assertThat(new GuardClause("", Op.IS_LAST, "", null).isBlank()).isFalse();
    }
}
