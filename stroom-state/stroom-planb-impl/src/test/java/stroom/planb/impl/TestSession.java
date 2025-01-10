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

package stroom.planb.impl;

import stroom.bytebuffer.impl6.ByteBufferFactory;
import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.entity.shared.ExpressionCriteria;
import stroom.planb.impl.io.Session;
import stroom.planb.impl.io.SessionFields;
import stroom.planb.impl.io.SessionReader;
import stroom.planb.impl.io.SessionWriter;
import stroom.query.api.v2.ExpressionOperator;
import stroom.query.api.v2.ExpressionTerm.Condition;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.language.functions.FieldIndex;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValDate;
import stroom.util.io.FileUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestSession {

    @Test
    void test(@TempDir Path tempDir) {
        final Ranges ranges = testWrite(tempDir);

        final byte[] key = "TEST".getBytes(StandardCharsets.UTF_8);
        final Instant refTime = Instant.parse("2000-01-01T00:00:00.000Z");
        final InstantRange highRange = ranges.highRange;
        final InstantRange lowRange = ranges.lowRange;
        final ByteBufferFactory byteBufferFactory = new ByteBufferFactoryImpl();
//        try (final SessionWriter writer = new SessionWriter(tempDir, byteBufferFactory)) {
//            highRange = insertData(writer, key, refTime, 100, 10);
//            lowRange = insertData(writer, key, refTime, 10, -10);
//        }

        try (final SessionReader reader = new SessionReader(tempDir, byteBufferFactory)) {
            assertThat(reader.count()).isEqualTo(109);
            testGet(reader, key, refTime, 10);


//            final SessionRequest sessionRequest = SessionRequest.builder().name("TEST").time(refTime).build();
//            final Optional<Session> optional = reader.getState(sessionRequest);
//            assertThat(optional).isNotEmpty();
//            final Session res = optional.get();
//            assertThat(res.key()).isEqualTo("TEST".getBytes(StandardCharsets.UTF_8));
//            assertThat(Instant.ofEpochMilli(res.start())).isEqualTo(lowRange.min());
//            assertThat(Instant.ofEpochMilli(res.end())).isEqualTo(highRange.max());

            final ExpressionOperator expression = ExpressionOperator.builder()
                    .addTextTerm(SessionFields.KEY_FIELD, Condition.EQUALS, "TEST")
                    .build();
            final ExpressionCriteria criteria = new ExpressionCriteria(expression);

            final FieldIndex fieldIndex = new FieldIndex();
            fieldIndex.create(SessionFields.KEY);
            fieldIndex.create(SessionFields.START);
            fieldIndex.create(SessionFields.END);

            final InstantRange outerRange = InstantRange.combine(highRange, lowRange);
            final ValDate minTime = ValDate.create(outerRange.min());
            final ValDate maxTime = ValDate.create(outerRange.max());
            final List<Val[]> results = new ArrayList<>();
            final ExpressionPredicateFactory expressionPredicateFactory = new ExpressionPredicateFactory(null);
            reader.search(
                    criteria,
                    fieldIndex,
                    null,
                    expressionPredicateFactory,
                    results::add);
            assertThat(results.size()).isEqualTo(1);
            assertThat(results.getFirst()[0].toString()).isEqualTo("TEST");
            assertThat(results.getFirst()[1]).isEqualTo(minTime);
            assertThat(results.getFirst()[2]).isEqualTo(maxTime);


//            sessionDao.condense(Instant.now());
//            assertThat(sessionDao.count()).isOne();
//
//            sessionDao.search(new ExpressionCriteria(expression), fieldIndex, null, values -> {
//                count.incrementAndGet();
//                assertThat(values[1]).isEqualTo(minTime);
//                assertThat(values[2]).isEqualTo(maxTime);
//            });
//            assertThat(count.get()).isEqualTo(1);
//            count.set(0);
//
//            // Test in session.
//            assertThat(sessionDao.inSession(
//                    new TemporalStateRequest(
//                            "TEST",
//                            "TEST",
//                            Instant.parse("2000-01-01T00:00:00.000Z"))))
//                    .isTrue();
//            assertThat(sessionDao.inSession(
//                    new TemporalStateRequest(
//                            "TEST",
//                            "TEST",
//                            Instant.parse("1999-01-01T00:00:00.000Z"))))
//                    .isFalse();


//        ExpressionOperator expression = ExpressionOperator.builder()
//                .addTextTerm(SessionFields.KEY_FIELD, Condition.EQUALS, "TEST")
//                .build();
//        final ExpressionCriteria criteria = new ExpressionCriteria(expression);
//
//        ScyllaDbUtil.test((sessionProvider, tableName) -> {
//            final SessionDao sessionDao = new SessionDao(sessionProvider, tableName);
//
//            final Instant refTime = Instant.parse("2000-01-01T00:00:00.000Z");
//            final InstantRange highRange = insertData(sessionDao, refTime, 100, 10);
//            final InstantRange lowRange = insertData(sessionDao, refTime, 10, -10);
//            final InstantRange outerRange = InstantRange.combine(highRange, lowRange);
//
//            assertThat(sessionDao.count()).isEqualTo(109);
//
//            final AtomicInteger count = new AtomicInteger();
//            final FieldIndex fieldIndex = new FieldIndex();
//            fieldIndex.create(SessionFields.KEY);
//            fieldIndex.create(SessionFields.START);
//            fieldIndex.create(SessionFields.END);
//            fieldIndex.create(SessionFields.TERMINAL);
//
//            final ValDate minTime = ValDate.create(outerRange.min());
//            final ValDate maxTime = ValDate.create(outerRange.max());
//            sessionDao.search(criteria, fieldIndex, null, values -> {
//                count.incrementAndGet();
//                assertThat(values[1]).isEqualTo(minTime);
//                assertThat(values[2]).isEqualTo(maxTime);
//            });
//            assertThat(count.get()).isEqualTo(1);
//            count.set(0);
//
//            sessionDao.condense(Instant.now());
//            assertThat(sessionDao.count()).isOne();
//
//            sessionDao.search(new ExpressionCriteria(expression), fieldIndex, null, values -> {
//                count.incrementAndGet();
//                assertThat(values[1]).isEqualTo(minTime);
//                assertThat(values[2]).isEqualTo(maxTime);
//            });
//            assertThat(count.get()).isEqualTo(1);
//            count.set(0);
//
//            // Test in session.
//            assertThat(sessionDao.inSession(
//                    new TemporalStateRequest(
//                            "TEST",
//                            "TEST",
//                            Instant.parse("2000-01-01T00:00:00.000Z"))))
//                    .isTrue();
//            assertThat(sessionDao.inSession(
//                    new TemporalStateRequest(
//                            "TEST",
//                            "TEST",
//                            Instant.parse("1999-01-01T00:00:00.000Z"))))
//                    .isFalse();
//        });
        }
    }

    @Test
    void testMerge() throws IOException {
        final Path db1 = Files.createTempDirectory("db1");
        final Path db2 = Files.createTempDirectory("db2");
        try {
            testWrite(db1);
            testWrite(db2);

            final ByteBufferFactory byteBufferFactory = new ByteBufferFactoryImpl();
            try (final SessionWriter writer = new SessionWriter(db1, byteBufferFactory)) {
                writer.merge(db2);
            }

        } finally {
            FileUtil.deleteDir(db1);
            FileUtil.deleteDir(db2);
        }
    }

    private Ranges testWrite(final Path dbDir) {
        final byte[] key = "TEST".getBytes(StandardCharsets.UTF_8);
        final Instant refTime = Instant.parse("2000-01-01T00:00:00.000Z");
        final InstantRange highRange;
        final InstantRange lowRange;
        final ByteBufferFactory byteBufferFactory = new ByteBufferFactoryImpl();
        try (final SessionWriter writer = new SessionWriter(dbDir, byteBufferFactory)) {
            highRange = insertData(writer, key, refTime, 100, 10);
            lowRange = insertData(writer, key, refTime, 10, -10);
        }
        return new Ranges(highRange, lowRange);
    }

    private record Ranges(InstantRange highRange,
                          InstantRange lowRange) {

    }

    private void testGet(final SessionReader reader,
                         final byte[] key,
                         final Instant refTime,
                         final long deltaSeconds) {
        final Session k = Session.builder().start(refTime).end(refTime.plusSeconds(deltaSeconds)).key(key).build();
        final Optional<Session> optional = reader.get(k);
        assertThat(optional).isNotEmpty();
        final Session res = optional.get();
        assertThat(res.key()).isEqualTo(key);
    }

//    @Test
//    void testRemoveOldData() {
//        ScyllaDbUtil.test((sessionProvider, tableName) -> {
//            final SessionDao sessionDao = new SessionDao(sessionProvider, tableName);
//
//            Instant refTime = Instant.parse("2000-01-01T00:00:00.000Z");
//            insertData(sessionDao, refTime, 100, 10);
//            insertData(sessionDao, refTime, 10, -10);
//
//            assertThat(sessionDao.count()).isEqualTo(109);
//
//            sessionDao.removeOldData(refTime);
//            assertThat(sessionDao.count()).isEqualTo(100);
//
//            sessionDao.removeOldData(Instant.now());
//            assertThat(sessionDao.count()).isEqualTo(0);
//        });
//    }

    private InstantRange insertData(final SessionWriter writer,
                                    final byte[] key,
                                    final Instant refTime,
                                    final int rows,
                                    final long deltaSeconds) {
        Instant min = refTime;
        Instant max = refTime;
        for (int i = 0; i < rows; i++) {
            final Instant start = refTime.plusSeconds(i * deltaSeconds);
            final Instant end = start.plusSeconds(Math.abs(deltaSeconds));
            if (start.isBefore(min)) {
                min = start;
            }
            if (end.isAfter(max)) {
                max = end;
            }

            final Session session = Session.builder().key(key).start(start).end(end).build();
            writer.insert(session, session);
        }
        return new InstantRange(min, max);
    }
}
