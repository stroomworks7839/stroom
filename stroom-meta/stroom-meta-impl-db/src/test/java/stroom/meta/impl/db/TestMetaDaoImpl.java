/*
 * Copyright 2017 Crown Copyright
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
 *
 */

package stroom.meta.impl.db;

import stroom.cache.impl.CacheModule;
import stroom.cluster.lock.mock.MockClusterLockModule;
import stroom.collection.mock.MockCollectionModule;
import stroom.dictionary.mock.MockWordListProviderModule;
import stroom.docref.DocRef;
import stroom.docrefinfo.mock.MockDocRefInfoModule;
import stroom.feed.shared.FeedDoc;
import stroom.meta.api.AttributeMap;
import stroom.meta.api.MetaProperties;
import stroom.meta.impl.MetaValueDao;
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaExpressionUtil;
import stroom.meta.shared.MetaFields;
import stroom.meta.shared.SelectionSummary;
import stroom.meta.shared.Status;
import stroom.query.api.v2.ExpressionOperator;
import stroom.query.api.v2.ExpressionOperator.Op;
import stroom.query.api.v2.ExpressionTerm;
import stroom.query.api.v2.ExpressionTerm.Condition;
import stroom.security.mock.MockSecurityContextModule;
import stroom.task.mock.MockTaskModule;
import stroom.test.common.util.db.DbTestModule;
import stroom.util.shared.ResultPage;

import com.google.inject.Guice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import javax.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

class TestMetaDaoImpl {

    private static final String RAW_STREAM_TYPE_NAME = "RAW_TEST_STREAM_TYPE";
    private static final String PROCESSED_STREAM_TYPE_NAME = "TEST_STREAM_TYPE";
    private static final String TEST1_FEED_NAME = "TEST1";
    private static final String TEST2_FEED_NAME = "TEST2";
    private static final String TEST3_FEED_NAME = "TEST3";
    private static final DocRef TEST1_FEED =
            new DocRef(FeedDoc.DOCUMENT_TYPE, UUID.randomUUID().toString(), TEST1_FEED_NAME);
    private static final DocRef TEST2_FEED =
            new DocRef(FeedDoc.DOCUMENT_TYPE, UUID.randomUUID().toString(), TEST2_FEED_NAME);
    private static final DocRef TEST3_FEED =
            new DocRef(FeedDoc.DOCUMENT_TYPE, UUID.randomUUID().toString(), TEST3_FEED_NAME);

    @Inject
    private Cleanup cleanup;
    @Inject
    private MetaDaoImpl metaDao;
    @Inject
    private MetaValueDao metaValueDao;
    @Inject
    private MetaDbConnProvider metaDbConnProvider;

    @BeforeEach
    void setup() {
        Guice.createInjector(
                        new MetaTestModule(),
                        new MetaDbModule(),
                        new MockClusterLockModule(),
                        new MockSecurityContextModule(),
                        new MockTaskModule(),
                        new MockCollectionModule(),
                        new MockDocRefInfoModule(),
                        new MockWordListProviderModule(),
                        new CacheModule(),
                        new DbTestModule())
                .injectMembers(this);
        // Delete everything`
        cleanup.cleanup();

        // Add some test data.
        for (int i = 0; i < 10; i++) {
            final Meta parent = metaDao.create(createRawProperties(TEST1_FEED_NAME));
            Meta myMeta = metaDao.create(createProcessedProperties(parent, TEST1_FEED_NAME));

            AttributeMap attributeMap = new AttributeMap();
            attributeMap.put(MetaFields.REC_READ.getName(), "" + 100 * i);
            attributeMap.put(MetaFields.REC_WRITE.getName(), "" + 10 * i);
            metaValueDao.addAttributes(myMeta, attributeMap);
        }
        for (int i = 0; i < 10; i++) {
            final Meta parent = metaDao.create(createRawProperties(TEST2_FEED_NAME));
            Meta myMeta = metaDao.create(createProcessedProperties(parent, TEST2_FEED_NAME));
            AttributeMap attributeMap = new AttributeMap();
            attributeMap.put(MetaFields.REC_READ.getName(), "" + 1000 * i);
            attributeMap.put(MetaFields.REC_WRITE.getName(), "" + 100 * i);
            metaValueDao.addAttributes(myMeta, attributeMap);
        }

        metaValueDao.flush();
        // Unlock all streams.
        metaDao.updateStatus(new FindMetaCriteria(ExpressionOperator.builder().build()),
                Status.LOCKED,
                Status.UNLOCKED,
                System.currentTimeMillis());
    }

    @Test
    void testFind() {
        setup();

        // Find all.
        test(ExpressionOperator.builder().build(), 40);

        // Find feed 1.
        test(MetaExpressionUtil.createFeedExpression(
                TEST1_FEED_NAME), 20);

        // Find feed 2.
        test(MetaExpressionUtil.createFeedExpression(TEST2_FEED_NAME), 20);

        // Find both feeds.
        test(MetaExpressionUtil.createFeedsExpression(TEST1_FEED_NAME,
                TEST2_FEED_NAME), 40);

        // Find none.
        test(MetaExpressionUtil.createFeedsExpression(), 0);

        // Find with doc ref.
        test(ExpressionOperator.builder()
                .addTerm(createFeedTerm(TEST1_FEED, true))
                .addTerm(MetaFields.STATUS, Condition.EQUALS, Status.UNLOCKED.getDisplayValue())
                .build(), 20);

        test(ExpressionOperator.builder()
                .addTerm(createFeedTerm(TEST1_FEED, true))
                .addTerm(createFeedTerm(TEST2_FEED, true))
                .addTerm(MetaFields.STATUS, Condition.EQUALS, Status.UNLOCKED.getDisplayValue())
                .build(), 0);

        test(ExpressionOperator.builder()
                .addTerm(createFeedTerm(TEST1_FEED, true))
                .addTerm(createFeedTerm(TEST2_FEED, true))
                .build(), 0);

        test(ExpressionOperator.builder()
                .addTerm(createFeedTerm(TEST1_FEED, true))
                .addTerm(createFeedTerm(TEST2_FEED, false))
                .addTerm(MetaFields.STATUS, Condition.EQUALS, Status.UNLOCKED.getDisplayValue())
                .build(), 20);

        test(ExpressionOperator.builder()
                .addTerm(createFeedTerm(TEST1_FEED, false))
                .addTerm(createFeedTerm(TEST2_FEED, true))
                .addTerm(MetaFields.STATUS, Condition.EQUALS, Status.UNLOCKED.getDisplayValue())
                .build(), 20);

        test(ExpressionOperator.builder()
                .addTerm(createFeedTerm(TEST1_FEED, false))
                .addTerm(createFeedTerm(TEST2_FEED, false))
                .addTerm(MetaFields.STATUS, Condition.EQUALS, Status.UNLOCKED.getDisplayValue())
                .build(), 40);

        test(ExpressionOperator.builder()
                .addTerm(createFeedTerm(TEST1_FEED, false))
                .addTerm(createFeedTerm(TEST2_FEED, false))
                .build(), 40);

        test(ExpressionOperator.builder()
                .addTerm(createFeedTerm(TEST1_FEED, false))
                .addTerm(createFeedTerm(TEST2_FEED, false))
                .addTerm(createFeedTerm(TEST3_FEED, false))
                .build(), 40);

        test(ExpressionOperator.builder()
                .addTerm(createFeedTerm(TEST1_FEED, false))
                .addTerm(createFeedTerm(TEST2_FEED, false))
                .addTerm(createFeedTerm(TEST3_FEED, true))
                .build(), 0);

        test(ExpressionOperator.builder()
                .addTerm(MetaFields.FEED, Condition.EQUALS, TEST1_FEED_NAME)
                .addTerm(MetaFields.TYPE, Condition.EQUALS, RAW_STREAM_TYPE_NAME)
                .build(), 10);

        // Or tests.
        test(ExpressionOperator.builder()
                .addOperator(
                        ExpressionOperator.builder()
                                .op(Op.OR)
                                .addTerm(createFeedTerm(TEST1_FEED, true))
                                .addTerm(createFeedTerm(TEST2_FEED, true))
                                .build())
                .addTerm(MetaFields.STATUS, Condition.EQUALS, Status.UNLOCKED.getDisplayValue())
                .build(), 40);

        test(ExpressionOperator.builder()
                .addOperator(
                        ExpressionOperator.builder()
                                .op(Op.OR)
                                .addTerm(createFeedTerm(TEST1_FEED, true))
                                .addTerm(createFeedTerm(TEST2_FEED, true))
                                .build())
                .build(), 40);

        test(ExpressionOperator.builder()
                .addOperator(
                        ExpressionOperator.builder()
                                .op(Op.OR)
                                .addTerm(createFeedTerm(TEST1_FEED, true))
                                .addTerm(createFeedTerm(TEST2_FEED, false))
                                .build())
                .build(), 20);

        test(ExpressionOperator.builder()
                .addOperator(
                        ExpressionOperator.builder()
                                .op(Op.OR)
                                .addTerm(createFeedTerm(TEST1_FEED, false))
                                .addTerm(createFeedTerm(TEST2_FEED, false))
                                .build())
                .build(), 40);

        test(ExpressionOperator.builder()
                .addOperator(
                        ExpressionOperator.builder()
                                .op(Op.OR)
                                .addTerm(createFeedTerm(TEST1_FEED, false))
                                .addTerm(createFeedTerm(TEST2_FEED, false))
                                .addTerm(createFeedTerm(TEST3_FEED, false))
                                .build())
                .build(), 40);

        test(ExpressionOperator.builder()
                .addOperator(
                        ExpressionOperator.builder()
                                .op(Op.OR)
                                .addTerm(createFeedTerm(TEST1_FEED, false))
                                .addTerm(createFeedTerm(TEST2_FEED, false))
                                .addTerm(createFeedTerm(TEST3_FEED, true))
                                .build())
                .build(), 0);
    }

    private void test(final ExpressionOperator expression, final int expected) {
        final ResultPage<Meta> resultPage = metaDao.find(new FindMetaCriteria(expression));
        assertThat(resultPage.size()).isEqualTo(expected);
    }

    private ExpressionTerm createFeedTerm(final DocRef feed, boolean enabled) {
        return ExpressionTerm
                .builder()
                .field(MetaFields.FEED.getName())
                .condition(Condition.IS_DOC_REF)
                .docRef(feed)
                .enabled(enabled)
                .build();
    }

    @Test
    void testExtendedFind() {
        setup();

        ResultPage<Meta> resultPage = metaDao.find(new FindMetaCriteria(MetaExpressionUtil.createFeedExpression(
                TEST1_FEED_NAME)));
        assertThat(resultPage.size())
                .isEqualTo(20);

        resultPage = metaDao.find(new FindMetaCriteria(MetaExpressionUtil.createFeedExpression(TEST2_FEED_NAME)));
        assertThat(resultPage.size())
                .isEqualTo(20);

        resultPage = metaDao.find(new FindMetaCriteria(MetaExpressionUtil.createFeedsExpression(TEST1_FEED_NAME,
                TEST2_FEED_NAME)));
        assertThat(resultPage.size())
                .isEqualTo(40);

        resultPage = metaDao.find(new FindMetaCriteria(MetaExpressionUtil.createFeedsExpression()));
        assertThat(resultPage.size())
                .isEqualTo(0);

        final ExpressionOperator expression = ExpressionOperator.builder()
                .addOperator(ExpressionOperator.builder().op(Op.OR)
                        .addTerm(MetaFields.FEED, Condition.EQUALS, TEST1_FEED_NAME)
                        .addTerm(MetaFields.FEED, Condition.EQUALS, TEST2_FEED_NAME)
                        .build())
                .addTerm(MetaFields.TYPE, Condition.EQUALS, PROCESSED_STREAM_TYPE_NAME)
                .addTerm(MetaFields.REC_WRITE.getName(), Condition.EQUALS, "0")
                .addTerm(MetaFields.REC_READ.getName(), Condition.GREATER_THAN_OR_EQUAL_TO, "0")
                .build();

        System.err.println("About to find...");
        resultPage = metaDao.find(new FindMetaCriteria(expression));
        assertThat(resultPage.size())
                .isEqualTo(2);
    }

    @Test
    void testFindReprocess() {
        setup();

        ResultPage<Meta> resultPage = metaDao.findReprocess(
                new FindMetaCriteria(MetaExpressionUtil.createFeedExpression(TEST1_FEED_NAME)));
        assertThat(resultPage.size())
                .isEqualTo(10);

        resultPage = metaDao.findReprocess(new FindMetaCriteria(MetaExpressionUtil.createFeedExpression(
                TEST2_FEED_NAME)));
        assertThat(resultPage.size())
                .isEqualTo(10);

        resultPage = metaDao.findReprocess(new FindMetaCriteria(MetaExpressionUtil.createFeedsExpression(
                TEST1_FEED_NAME,
                TEST2_FEED_NAME)));
        assertThat(resultPage.size())
                .isEqualTo(20);

        resultPage = metaDao.findReprocess(new FindMetaCriteria(MetaExpressionUtil.createFeedsExpression()));
        assertThat(resultPage.size())
                .isEqualTo(0);

        final ExpressionOperator expression = ExpressionOperator.builder()
                .addOperator(ExpressionOperator.builder().op(Op.OR)
                        .addTerm(MetaFields.FEED, Condition.EQUALS, TEST1_FEED_NAME)
                        .addTerm(MetaFields.FEED, Condition.EQUALS, TEST2_FEED_NAME)
                        .build())
                .addTerm(MetaFields.TYPE, Condition.EQUALS, RAW_STREAM_TYPE_NAME)
                .build();
        resultPage = metaDao.findReprocess(new FindMetaCriteria(expression));
        assertThat(resultPage.size())
                .isEqualTo(0);
    }

    @Test
    void testGetSelectionSummary() {
        setup();

        SelectionSummary selectionSummary = metaDao.getSelectionSummary(new FindMetaCriteria(
                MetaExpressionUtil.createFeedExpression(TEST1_FEED_NAME)));
        assertThat(selectionSummary.getItemCount())
                .isEqualTo(20);

        selectionSummary = metaDao.getSelectionSummary(new FindMetaCriteria(MetaExpressionUtil.createFeedExpression(
                TEST2_FEED_NAME)));
        assertThat(selectionSummary.getItemCount())
                .isEqualTo(20);

        selectionSummary = metaDao.getSelectionSummary(new FindMetaCriteria(MetaExpressionUtil.createFeedsExpression(
                TEST1_FEED_NAME,
                TEST2_FEED_NAME)));
        assertThat(selectionSummary.getItemCount())
                .isEqualTo(40);

        selectionSummary = metaDao.getSelectionSummary(
                new FindMetaCriteria(MetaExpressionUtil.createFeedsExpression()));
        assertThat(selectionSummary.getItemCount())
                .isEqualTo(0);

        final ExpressionOperator expression = ExpressionOperator.builder()
                .addTerm(MetaFields.FEED, Condition.EQUALS, TEST1_FEED_NAME)
                .addTerm(MetaFields.TYPE, Condition.EQUALS, RAW_STREAM_TYPE_NAME)
                .build();
        selectionSummary = metaDao.getSelectionSummary(new FindMetaCriteria(expression));
        assertThat(selectionSummary.getItemCount())
                .isEqualTo(10);
    }

    @Test
    void testGetReprocessSelectionSummary() {
        setup();

        SelectionSummary selectionSummary = metaDao.getReprocessSelectionSummary(
                new FindMetaCriteria(MetaExpressionUtil.createFeedExpression(
                        TEST1_FEED_NAME)));
        assertThat(selectionSummary.getItemCount())
                .isEqualTo(10);

        selectionSummary = metaDao.getReprocessSelectionSummary(
                new FindMetaCriteria(MetaExpressionUtil.createFeedExpression(TEST2_FEED_NAME)));
        assertThat(selectionSummary.getItemCount())
                .isEqualTo(10);

        selectionSummary = metaDao.getReprocessSelectionSummary(
                new FindMetaCriteria(MetaExpressionUtil.createFeedsExpression(TEST1_FEED_NAME, TEST2_FEED_NAME)));
        assertThat(selectionSummary.getItemCount())
                .isEqualTo(20);

        selectionSummary = metaDao.getReprocessSelectionSummary(
                new FindMetaCriteria(MetaExpressionUtil.createFeedsExpression()));
        assertThat(selectionSummary.getItemCount())
                .isEqualTo(0);

        final ExpressionOperator expression = ExpressionOperator.builder()
                .addTerm(MetaFields.FEED, Condition.EQUALS, TEST1_FEED_NAME)
                .addTerm(MetaFields.TYPE, Condition.EQUALS, RAW_STREAM_TYPE_NAME)
                .build();
        selectionSummary = metaDao.getReprocessSelectionSummary(new FindMetaCriteria(expression));
        assertThat(selectionSummary.getItemCount())
                .isEqualTo(0);
    }

    private MetaProperties createRawProperties(final String feedName) {
        return MetaProperties.builder()
                .createMs(System.currentTimeMillis())
                .feedName(feedName)
                .typeName(RAW_STREAM_TYPE_NAME)
                .build();
    }

    private MetaProperties createProcessedProperties(final Meta parent, final String feedName) {
        return MetaProperties.builder()
                .parent(parent)
                .createMs(System.currentTimeMillis())
                .feedName(feedName)
                .processorUuid("12345")
                .pipelineUuid("PIPELINE_UUID")
                .typeName(PROCESSED_STREAM_TYPE_NAME)
                .build();
    }
}
