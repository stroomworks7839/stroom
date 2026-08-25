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

package stroom.shapeshifter.engine;

import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.exec.Comparisons;
import stroom.shapeshifter.engine.exec.TypedValue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The comparison spine (design/17 §8), tested directly: the strict rule over every kind
 * pair, each cast succeeding and failing, the legacy aliases constructing the casts their
 * semantics always implied, and the lint firing on the one statically visible foot-gun.
 */
class CompareSpineTest {

    private static final TypedValue BYTES = TypedValue.of("42");
    private static final TypedValue INT = new TypedValue.Int(42);
    private static final TypedValue REAL = new TypedValue.Real(42.0);
    private static final TypedValue BOOL = new TypedValue.Bool(true);

    // -----------------------------------------------------------------------------------
    // The strict rule
    // -----------------------------------------------------------------------------------

    @Test
    void crossKindPairsDoNotCompare() {
        // Bytes "42" against Int 42: same rendering, different kinds, no answer.
        assertThat(Comparisons.compare(BYTES, INT)).isNull();
        assertThat(Comparisons.compare(BYTES, BOOL)).isNull();
        assertThat(Comparisons.compare(INT, BOOL)).isNull();
        assertThat(Comparisons.compare(null, INT)).isNull();
        assertThat(Comparisons.compare(BYTES, null)).isNull();
    }

    @Test
    void intAgainstRealIsPromotionNotCoercion() {
        assertThat(Comparisons.compare(INT, REAL)).isZero();
        assertThat(Comparisons.compare(new TypedValue.Int(3), new TypedValue.Real(2.5)))
                .isPositive();
    }

    @Test
    void longRangeIntegersCompareExactly() {
        // 2^53 and 2^53 + 1 collapse to one double; the Int/Int path must not go there.
        assertThat(Comparisons.compare(
                new TypedValue.Int(9007199254740992L), new TypedValue.Int(9007199254740993L)))
                .isNegative();
    }

    @Test
    void boolsOrderFalseBeforeTrue() {
        assertThat(Comparisons.compare(new TypedValue.Bool(false), new TypedValue.Bool(true)))
                .isNegative();
    }

    @Test
    void byteWiseStringComparisonAgreesWithDecodedComparisonOnMultiByteInput() {
        // UTF-8's unsigned byte order is code-point order; signed bytes would say é < m.
        final String[] words = {"m", "é", "😀", "a", "zz", "z", "é1", "é́"};
        for (final String a : words) {
            for (final String b : words) {
                final Integer byteWise = Comparisons.compare(TypedValue.of(a), TypedValue.of(b));
                final int codePoints = java.util.Arrays.compare(
                        a.codePoints().toArray(), b.codePoints().toArray());
                assertThat(Integer.signum(byteWise))
                        .as("'%s' vs '%s'", a, b)
                        .isEqualTo(Integer.signum(codePoints));
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // The casts
    // -----------------------------------------------------------------------------------

    @Test
    void castsSucceedAndFailPerTheTable() {
        assertThat(Comparisons.cast(BYTES, Cast.NUMBER)).isEqualTo(INT);
        assertThat(Comparisons.cast(TypedValue.of("2.5"), Cast.NUMBER))
                .isEqualTo(new TypedValue.Real(2.5));
        assertThat(Comparisons.cast(TypedValue.of("n/a"), Cast.NUMBER)).isNull();
        assertThat(Comparisons.cast(TypedValue.of("true"), Cast.BOOLEAN))
                .isEqualTo(new TypedValue.Bool(true));
        assertThat(Comparisons.cast(TypedValue.of("yes"), Cast.BOOLEAN)).isNull();
        // String is the total cast: every value has a string form.
        assertThat(Comparisons.cast(INT, Cast.STRING)).isEqualTo(BYTES);
        assertThat(Comparisons.cast(null, Cast.NUMBER)).isNull();
        assertThat(Comparisons.cast(BYTES, null)).isSameAs(BYTES);
    }

    @Test
    void numberCastKeepsLongRangeIntegersWhole() {
        assertThat(Comparisons.cast(TypedValue.of("9007199254740993"), Cast.NUMBER))
                .isEqualTo(new TypedValue.Int(9007199254740993L));
    }

    // -----------------------------------------------------------------------------------
    // The aliases, asserted at the reader
    // -----------------------------------------------------------------------------------

    private static Condition readGuard(final String conditionJson) {
        final Project project = ProjectReader.read("""
                {"name": "t", "version": 4,
                 "templates": [{"id": "00000000-0000-0000-0000-000000000001", "name": "t",
                   "guard": %s,
                   "match": {"regex": {"pattern": "x"}}, "body": []}]}
                """.formatted(conditionJson));
        return project.templates().getFirst().guard();
    }

    @Test
    void legacyGreaterThanConstructsGtWithANumberCastOnTheLeft() {
        final Condition condition = readGuard(
                "{\"greater-than\": {\"select\": {\"parts\": []}, \"value\": 5}}");
        assertThat(condition).isEqualTo(new Condition.Compare(Condition.Compare.Op.GT,
                new Condition.Operand(readGuardRef(), null, Cast.NUMBER),
                new Condition.Operand(null, new Condition.Literal.Fractional(5.0), null)));
    }

    @Test
    void legacyEqualsConstructsEqWithStringCastsOnBothSides() {
        // The phase 1 audit's correction: the engine's counters are already Int, and legacy
        // equality compares string forms — so the alias casts both sides to string.
        final Condition condition = readGuard(
                "{\"equals\": {\"select\": {\"parts\": []}, \"value\": \"3\"}}");
        assertThat(condition).isEqualTo(new Condition.Compare(Condition.Compare.Op.EQ,
                new Condition.Operand(readGuardRef(), null, Cast.STRING),
                new Condition.Operand(null, new Condition.Literal.Text("3"), Cast.STRING)));
    }

    private static stroom.shapeshifter.engine.config.RefExpression readGuardRef() {
        return new stroom.shapeshifter.engine.config.RefExpression(java.util.List.of());
    }

    // -----------------------------------------------------------------------------------
    // The lint, and the date refusal
    // -----------------------------------------------------------------------------------

    private static CompiledProject compileGuard(final String conditionJson) {
        return Shapeshifter.compile(ProjectReader.read("""
                {"name": "t", "version": 4,
                 "templates": [{"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source",
                   "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                             "mode": "doc"}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "guarded", "mode": "doc",
                   "guard": %s,
                   "match": {"regex": {"pattern": "x"}}, "body": []}]}
                """.formatted(conditionJson)));
    }

    @Test
    void typedLiteralAgainstAnUncastRefDrawsTheLint() {
        final CompiledProject compiled = compileGuard(
                "{\"gt\": {\"left\": {\"ref\": {\"parts\": [{\"capture\": {\"group\": 1}}]}},"
                + " \"right\": {\"value\": 100}}}");
        assertThat(compiled.warnings())
                .anyMatch(m -> m.text().contains("typed literal against an uncast reference"));
    }

    @Test
    void theCastSilencesTheLint() {
        final CompiledProject compiled = compileGuard(
                "{\"gt\": {\"left\": {\"ref\": {\"parts\": [{\"capture\": {\"group\": 1}}]},"
                + " \"as\": \"number\"}, \"right\": {\"value\": 100}}}");
        assertThat(compiled.warnings())
                .noneMatch(m -> m.text().contains("typed literal against an uncast reference"));
    }

    @Test
    void stringLiteralDrawsNoLint() {
        // Two untyped sides are a string comparison — the corpus's whole shape.
        final CompiledProject compiled = compileGuard(
                "{\"eq\": {\"left\": {\"ref\": {\"parts\": [{\"capture\": {\"group\": 1}}]}},"
                + " \"right\": {\"value\": \"x\"}}}");
        assertThat(compiled.warnings())
                .noneMatch(m -> m.text().contains("typed literal against an uncast reference"));
    }

    @Test
    void dateCastIsRefusedByName() {
        assertThatThrownBy(() -> compileGuard(
                "{\"eq\": {\"left\": {\"ref\": {\"parts\": [{\"capture\": {\"group\": 1}}]},"
                + " \"as\": \"date\"}, \"right\": {\"value\": \"2026-01-01\"}}}"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("date");
    }
}
