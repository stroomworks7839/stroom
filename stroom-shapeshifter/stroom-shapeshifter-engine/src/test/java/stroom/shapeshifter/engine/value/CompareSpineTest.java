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

package stroom.shapeshifter.engine.value;

import stroom.shapeshifter.config.Cast;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.engine.ProjectReader;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.graph.CompiledProject;
import stroom.shapeshifter.engine.match.MatchResult;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comparison spine (design/17 §8), tested directly: the strict rule over every kind
 * pair, each cast succeeding and failing, the legacy aliases constructing the casts their
 * semantics always implied, and the lint firing on the one statically visible foot-gun.
 */
class CompareSpineTest {

    private static final TypedValue BYTES = TypedValue.of("42");
    private static final TypedValue INT = new TypedValue.Integer(42);
    private static final TypedValue REAL = new TypedValue.Double(42.0);
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
        assertThat(Comparisons.compare(new TypedValue.Integer(3), new TypedValue.Double(2.5)))
                .isPositive();
    }

    @Test
    void longRangeIntegersCompareExactly() {
        // 2^53 and 2^53 + 1 collapse to one double; the Int/Int path must not go there.
        assertThat(Comparisons.compare(
                new TypedValue.Integer(9007199254740992L), new TypedValue.Integer(9007199254740993L)))
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
                .isEqualTo(new TypedValue.Double(2.5));
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
                .isEqualTo(new TypedValue.Integer(9007199254740993L));
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


    private static stroom.shapeshifter.config.RefExpression readGuardRef() {
        return new stroom.shapeshifter.config.RefExpression(java.util.List.of());
    }

    // -----------------------------------------------------------------------------------
    // The legacy absent rule, preserved through the aliases (the phase 3 audit's finding).
    // The old evaluator read an absent side as the empty string; the strict rule says
    // absent never compares. The aliases carry the difference so the corpus cannot notice.
    // -----------------------------------------------------------------------------------

    /**
     * Evaluate a condition as a template's guard, with no match and no variables so that every
     * reference is absent.
     *
     * <p>It compiles a whole configuration to get there, rather than calling a compiler pass,
     * because the passes are the compiler's business and only {@code Compiler} is its face
     * (2026-09-10, when the compiled vocabulary moved to {@code graph} and the passes stopped
     * being reachable from outside).
     *
     * <p>The template <b>declares</b> the name these conditions read, because the compiler
     * rightly refuses a configuration that reads a name nothing writes. Declaring it does not
     * bind it: no match has been made and the registry is empty, so it still resolves to nothing,
     * which is the absence every case here is about.
     */
    private static boolean evaluate(final Condition condition) {
        final stroom.shapeshifter.config.Template guarded =
                new stroom.shapeshifter.config.Template(
                        java.util.UUID.randomUUID().toString(), "guarded", "doc", false, condition,
                        java.util.List.of(),
                        java.util.List.of(new stroom.shapeshifter.config.Declaration("missing",
                                stroom.shapeshifter.config.Declaration.Type.SCALAR)),
                        new stroom.shapeshifter.config.MatchExpression.Regex(".*", null, 0),
                        new stroom.shapeshifter.config.Template.MatchLimits(0, -1, null),
                        java.util.List.of(new stroom.shapeshifter.config.CaptureBinding(
                                "missing",
                                new stroom.shapeshifter.config.CaptureBinding
                                        .CaptureSource.Group(1), null)),
                        java.util.List.of(), null, false);
        final stroom.shapeshifter.config.Project project =
                new stroom.shapeshifter.config.Project("t", 5,
                        stroom.shapeshifter.config.Project.SourceConfig.defaults(),
                        java.util.List.of(guarded));
        final stroom.shapeshifter.engine.graph.CompiledProject compiled =
                stroom.shapeshifter.engine.Shapeshifter.compile(project);
        return stroom.shapeshifter.engine.exec.Conditions.evaluate(
                compiled.templates()[0].guard(),
                MatchResult.empty(), 1,
                new stroom.shapeshifter.engine.exec.VarRegistry(compiled.names()));
    }

    private static final String MISSING_REF =
            "{\"parts\": [{\"capture\": {\"var_id\": \"missing\", \"group\": 0}}]}";




    @Test
    void theNewSpellingsKeepTheStrictRuleOnAbsence() {
        // The aliases preserve the old world; the new vocabulary does not inherit it.
        final Condition condition = readGuard(
                "{\"ne\": {\"left\": {\"ref\": " + MISSING_REF + "}, \"right\": {\"value\": \"x\"}}}");
        assertThat(evaluate(condition)).isFalse();
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
    void dateCastsCompareOnTheTimelineAcrossOffsets() {
        // Phase 4 delivered the Instant: as:date now reads ISO, and two offsets of one
        // moment are equal — the offset is inert in comparison (design/17 §3).
        final TypedValue paris = Comparisons.cast(TypedValue.of("2026-08-25T10:00:00+01:00"),
                Cast.DATE);
        final TypedValue utc = Comparisons.cast(TypedValue.of("2026-08-25T09:00:00Z"), Cast.DATE);
        assertThat(Comparisons.compare(paris, utc)).isZero();
        assertThat(Comparisons.cast(TypedValue.of("not a date"), Cast.DATE)).isNull();
        // No offset, no reading: a local date-time goes through parse-date, which has a zone.
        assertThat(Comparisons.cast(TypedValue.of("2026-08-25T09:00:00"), Cast.DATE)).isNull();
    }
}
