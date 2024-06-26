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

package stroom.state.impl;

import stroom.entity.shared.ExpressionCriteria;
import stroom.pipeline.refdata.store.StringValue;
import stroom.query.api.v2.ExpressionOperator;
import stroom.query.language.functions.FieldIndex;
import stroom.state.impl.dao.State;
import stroom.state.impl.dao.StateDao;
import stroom.state.impl.dao.StateFields;
import stroom.state.impl.dao.StateRequest;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TestStateDao {

    @Test
    void testDao() {
        ScyllaDbUtil.test((sessionProvider, keyspaceName) -> {
            final StateDao stateDao = new StateDao(sessionProvider);
            stateDao.dropTables();
            stateDao.createTables();

            insertData(stateDao, 100);

            final StateRequest stateRequest = new StateRequest("TEST_MAP", "TEST_KEY");
            final Optional<State> optional = stateDao.getState(stateRequest);
            assertThat(optional).isNotEmpty();
            final State res = optional.get();
            assertThat(res.key()).isEqualTo("TEST_KEY");
            assertThat(res.typeId()).isEqualTo(StringValue.TYPE_ID);
            assertThat(res.getValueAsString()).isEqualTo("test99");

            final FieldIndex fieldIndex = new FieldIndex();
            fieldIndex.create(StateFields.KEY);
            final AtomicInteger count = new AtomicInteger();
            stateDao.search(new ExpressionCriteria(ExpressionOperator.builder().build()), fieldIndex, null,
                    v -> count.incrementAndGet());
            assertThat(count.get()).isEqualTo(1);
        });
    }

    @Test
    void testRemoveOldData() {
        ScyllaDbUtil.test((sessionProvider, keyspaceName) -> {
            final StateDao stateDao = new StateDao(sessionProvider);
            stateDao.dropTables();
            stateDao.createTables();

            Instant refTime = Instant.parse("2000-01-01T00:00:00.000Z");
            insertData(stateDao, 100);
            insertData(stateDao, 10);

            assertThat(stateDao.count()).isEqualTo(1);

            stateDao.removeOldData(refTime);
            assertThat(stateDao.count()).isEqualTo(1);

            stateDao.removeOldData(Instant.now());
            assertThat(stateDao.count()).isEqualTo(0);
        });
    }

    private void insertData(final StateDao stateDao,
                            final int rows) {
        for (int i = 0; i < rows; i++) {
            final ByteBuffer byteBuffer = ByteBuffer.wrap(("test" + i).getBytes(StandardCharsets.UTF_8));
            final State state = new State(
                    "TEST_KEY",
                    StringValue.TYPE_ID,
                    byteBuffer);
            stateDao.insert(Collections.singletonList(state));
        }
    }
}
