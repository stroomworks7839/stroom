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

import stroom.docref.DocRef;
import stroom.meta.shared.MetaFields;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.pipeline.shared.PipelineDoc;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.util.json.JsonUtil;
import stroom.util.shared.Severity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class TestShapeshifterAiDoc {

    @Test
    void fullyPopulatedDocRoundTrips() {
        final ShapeshifterAiDoc doc = populated();

        final String json = JsonUtil.writeValueAsString(doc);
        final ShapeshifterAiDoc read = JsonUtil.readValue(json, ShapeshifterAiDoc.class);

        assertThat(read).isEqualTo(doc);
        assertThat(read.getScorers()).containsExactlyElementsOf(doc.getScorers());
        assertThat(read.getRoutingTable()).containsExactlyElementsOf(doc.getRoutingTable());
    }

    @Test
    void creatorAppliesDefaultsToNullArguments() {
        // The creator sees nulls for every field a stored doc predates, so the defaults must come from it
        // rather than only from the builder.
        final ShapeshifterAiDoc doc = new ShapeshifterAiDoc(
                "uuid", "name", "1", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        assertThat(doc.getExecutionMode()).isEqualTo(ExecutionMode.DEFERRED);
        assertThat(doc.getLearningMode()).isEqualTo(LearningMode.DISABLED);
        assertThat(doc.getErrorModeAfter()).isEqualTo(5);
        assertThat(doc.getLearningKey())
                .describedAs("A29: most bindings switch on feed and type alone")
                .containsExactly(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE);
        assertThat(doc.getRelearnThreshold()).isEqualTo(0.8);
        assertThat(doc.getAllowedElements())
                .describedAs("A10's initial set until a document narrows it")
                .containsExactly("DSParser", "JSONParser", "XMLParser", "XSLTFilter");
        assertThat(doc.getMaxAttempts()).describedAs("five since the first live run, design 02 §6.2").isEqualTo(5);
        assertThat(doc.getAttemptBudgetMs()).isEqualTo(60_000L);
        assertThat(doc.getTokenBudget()).isNull();
        assertThat(doc.getSampleRedaction()).isEqualTo(SampleRedaction.REDACTED);
        assertThat(doc.getSampleSizeLimit()).isEqualTo(8_192);
        assertThat(doc.getPromotionMode()).describedAs("A9").isEqualTo(PromotionMode.AUTOMATIC);
        assertThat(doc.getPromotionFloor()).isEqualTo(0.9);
        assertThat(doc.getHeldOutFraction()).isEqualTo(0.2);
        assertThat(doc.getMinRecordsPerShape()).isEqualTo(10);
        assertThat(doc.getRegressionCap()).isEqualTo(100);
        assertThat(doc.getRegressionRetentionDays()).isNull();
        assertThat(doc.getScorers()).isEmpty();
        assertThat(doc.getRoutingTable()).isEmpty();
    }

    @Test
    void builderDefaultsMatchCreatorDefaults() {
        // Client dirty-tracking compares the doc as read with the doc as written, so the two sources of
        // defaults must agree.
        final ShapeshifterAiDoc built = ShapeshifterAiDoc.builder().uuid("uuid").name("name").build();
        final ShapeshifterAiDoc created = new ShapeshifterAiDoc(
                "uuid", "name", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        assertThat(built).isEqualTo(created);
    }

    @Test
    void emptyDocOmitsNullKeys() {
        final String json = JsonUtil.writeValueAsString(ShapeshifterAiDoc.builder().uuid("uuid").build());

        assertThat(json).doesNotContain("\"model\"", "\"instructions\"", "\"tokenBudget\"", "\"description\"");
        assertThat(json).contains("\"type\" : \"ShapeshifterAi\"");
    }

    @Test
    void aScorerTakesItsTypesDefaultsWhenParametersAreAbsent() {
        // A doc written before a scorer had parameters, or a hand-written one, reads back with them.
        assertThat(new ScorerSetting(ScorerType.YIELD, null, null, null, null).getParameters())
                .isEqualTo(new YieldParameters(1.0, YieldBasis.RECORDS));
        assertThat(new ScorerSetting(ScorerType.COMPILE, null, null, null, null).getParameters()).isNull();
    }

    @Test
    void aScorerRefusesAnotherTypesParameters() {
        assertThatThrownBy(() -> new ScorerSetting(ScorerType.YIELD, null, null, null,
                new SchemaConformanceParameters("EVENTS")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SCHEMA_CONFORMANCE")
                .hasMessageContaining("YIELD");
    }

    @Test
    void aLearnedSelectorIsExactlyTheLearningKey() {
        // A22, A29: the supervisor writes the narrowest selector the variant was validated on — the
        // document's learning key, in its order — and nothing wider, whatever else the stream carries.
        final Map<String, Object> stream = Map.of(
                MetaFields.FIELD_FEED, "SYSLOG",
                MetaFields.FIELD_TYPE, "Raw Events",
                RoutingFields.SYSTEM, "Door Access",
                RoutingRule.SHAPE_SIGNATURE_FIELD, "rfc5424");
        final ExpressionOperator selector = RoutingRule.learnedSelector(
                List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE, RoutingRule.SHAPE_SIGNATURE_FIELD), stream);

        assertThat(selector.getChildren()).hasSize(3);
        assertThat(selector.getChildren())
                .extracting(child -> ((ExpressionTerm) child).getField(), child -> ((ExpressionTerm) child).getValue())
                .containsExactly(
                        tuple(MetaFields.FIELD_FEED, "SYSLOG"),
                        tuple(MetaFields.FIELD_TYPE, "Raw Events"),
                        tuple(RoutingRule.SHAPE_SIGNATURE_FIELD, "rfc5424"));
        assertThat(selector.getChildren())
                .allMatch(child -> ((ExpressionTerm) child).getCondition() == Condition.EQUALS);
        assertThat(selector.op()).isEqualTo(Op.AND);
    }

    @Test
    void aLearnedSelectorRefusesAKeyFieldTheStreamLacks() {
        // A stream without a value for a key field has no shape under that key; binding on the fields it
        // does carry would widen the rule to every value of the missing one, which §7.4 never validated.
        assertThatThrownBy(() -> RoutingRule.learnedSelector(
                List.of(MetaFields.FIELD_FEED, RoutingFields.SYSTEM),
                Map.of(MetaFields.FIELD_FEED, "SYSLOG")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(RoutingFields.SYSTEM);
    }

    @Test
    void aRuleDefaultsToActiveWithNoUuid() {
        // The store assigns the uuid on save and the supervisor on creation; the client never does, so a
        // rule built without one is not yet identified and is neither a draft nor provisional.
        final RoutingRule rule = RoutingRule.builder().build();

        assertThat(rule.getUuid()).isNull();
        assertThat(rule.isDraft()).isFalse();
        assertThat(rule.isProvisional()).isFalse();
        assertThat(rule.isReserved()).describedAs("no fragment: a reserved rule").isTrue();
    }

    static ShapeshifterAiDoc populated() {
        final DocRef model = OpenAIModelDoc.buildDocRef().uuid("model-uuid").name("local-model").build();
        final DocRef fragment = PipelineDoc.buildDocRef().uuid("fragment-uuid").name("syslog-v3").build();
        final Map<String, Object> stream = Map.of(
                MetaFields.FIELD_FEED, "SYSLOG",
                MetaFields.FIELD_TYPE, "Raw Events",
                RoutingRule.SHAPE_SIGNATURE_FIELD, "rfc5424");
        return ShapeshifterAiDoc.builder()
                .uuid("doc-uuid")
                .name("syslog-ai")
                .version("1")
                .createTimeMs(1L)
                .updateTimeMs(2L)
                .createUser("creator")
                .updateUser("updater")
                .description("A document")
                .executionMode(ExecutionMode.INLINE)
                .learningMode(LearningMode.AUTOMATIC)
                .errorModeAfter(2)
                .model(model)
                .learningKey(List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE, RoutingRule.SHAPE_SIGNATURE_FIELD))
                .relearnThreshold(0.75)
                .allowedElements(List.of("XSLTFilter"))
                .instructions("Prefer named fields.")
                .maxAttempts(5)
                .attemptBudgetMs(120_000L)
                .tokenBudget(4_096L)
                .sampleRedaction(SampleRedaction.RAW)
                .sampleSizeLimit(1_024)
                .promotionMode(PromotionMode.REVIEW)
                .promotionFloor(0.95)
                .heldOutFraction(0.25)
                .minRecordsPerShape(20)
                .regressionCap(50)
                .regressionRetentionDays(30)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 1.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 2.0, 0.8, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(0.9, YieldBasis.LINES)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 0.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 2.0, 0.7, true,
                                new ExtractionQualityParameters(true,
                                        List.of("/Event/EventTime", "/Event/EventSource/User/Id"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false,
                                new BusinessRulesParameters(List.of(
                                        new XPathAssertion("interactive events name the user",
                                                "not(EventDetail/Authenticate) or EventDetail/Authenticate/User/Id")),
                                        false)),
                        new ScorerSetting(ScorerType.ERROR_LOAD, 1.0, 0.9, false,
                                new ErrorLoadParameters(Severity.WARNING)),
                        new ScorerSetting(ScorerType.EVENT_CLASSIFICATION, 1.0, 0.8, false,
                                new EventClassificationParameters(List.of("logon", "logoff")))))
                .routingTable(List.of(
                        RoutingRule.builder()
                                .uuid("rule-1")
                                .expression(RoutingRule.learnedSelector(
                                        List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE,
                                                RoutingRule.SHAPE_SIGNATURE_FIELD),
                                        stream))
                                .pipeline(fragment)
                                .pinned(true)
                                .promotedTimeMs(3L)
                                .score(0.97)
                                .build(),
                        RoutingRule.builder()
                                .uuid("rule-2")
                                .expression(RoutingRule.learnedSelector(RoutingFields.DEFAULT_LEARNING_KEY, stream))
                                .pipeline(fragment)
                                .draft(true)
                                .provisional(true)
                                .build(),
                        RoutingRule.builder()
                                .build()))
                .build();
    }
}
