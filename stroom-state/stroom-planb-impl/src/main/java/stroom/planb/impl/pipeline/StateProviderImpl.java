package stroom.planb.impl.pipeline;

import stroom.planb.impl.PlanBDocCache;
import stroom.planb.impl.data.ShardManager;
import stroom.planb.impl.db.RangedState;
import stroom.planb.impl.db.RangedStateDb;
import stroom.planb.impl.db.RangedStateRequest;
import stroom.planb.impl.db.SessionDb;
import stroom.planb.impl.db.SessionRequest;
import stroom.planb.impl.db.State;
import stroom.planb.impl.db.StateDb;
import stroom.planb.impl.db.StateRequest;
import stroom.planb.impl.db.StateValue;
import stroom.planb.impl.db.TemporalRangedState;
import stroom.planb.impl.db.TemporalRangedStateDb;
import stroom.planb.impl.db.TemporalRangedStateRequest;
import stroom.planb.impl.db.TemporalState;
import stroom.planb.impl.db.TemporalStateDb;
import stroom.planb.impl.db.TemporalStateRequest;
import stroom.planb.shared.PlanBDoc;
import stroom.query.language.functions.StateProvider;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValBoolean;
import stroom.query.language.functions.ValNull;
import stroom.query.language.functions.ValString;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class StateProviderImpl implements StateProvider {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(StateProviderImpl.class);

    private final PlanBDocCache stateDocCache;
    private final Cache<Key, Val> cache;
    private final Map<String, Optional<PlanBDoc>> stateDocMap = new HashMap<>();
    private final ShardManager shardManager;

    @Inject
    public StateProviderImpl(final PlanBDocCache stateDocCache,
                             final ShardManager shardManager) {
        this.stateDocCache = stateDocCache;
        this.shardManager = shardManager;
        cache = Caffeine.newBuilder().maximumSize(1000).build();
    }

    @Override
    public Val getState(final String mapName, final String keyName, final Instant effectiveTimeMs) {
        final String keyspace = mapName.toLowerCase(Locale.ROOT);
        final Optional<PlanBDoc> stateOptional = stateDocMap.computeIfAbsent(keyspace, k ->
                Optional.ofNullable(stateDocCache.get(keyspace)));
        return stateOptional
                .map(stateDoc -> {
                    final Key key = new Key(keyspace, keyName, effectiveTimeMs);
                    return cache.get(key,
                            k -> getState(stateDoc, keyspace, keyName, effectiveTimeMs));
                })
                .orElse(ValNull.INSTANCE);
    }

    private Val getState(final PlanBDoc doc,
                         final String mapName,
                         final String keyName,
                         final Instant eventTime) {
        try {
            return shardManager.get(mapName, reader -> {
                if (reader instanceof final StateDb db) {
                    final StateRequest request =
                            new StateRequest(keyName.getBytes(StandardCharsets.UTF_8));
                    return getVal(db
                            .getState(request)
                            .map(State::value));
                } else if (reader instanceof final TemporalStateDb db) {
                    final TemporalStateRequest request =
                            new TemporalStateRequest(keyName.getBytes(StandardCharsets.UTF_8),
                                    eventTime.toEpochMilli());
                    return getVal(db
                            .getState(request)
                            .map(TemporalState::value));
                } else if (reader instanceof final RangedStateDb db) {
                    final RangedStateRequest request =
                            new RangedStateRequest(Long.parseLong(keyName));
                    return getVal(db
                            .getState(request)
                            .map(RangedState::value));
                } else if (reader instanceof final TemporalRangedStateDb db) {
                    final TemporalRangedStateRequest request =
                            new TemporalRangedStateRequest(Long.parseLong(keyName), eventTime.toEpochMilli());
                    return getVal(db
                            .getState(request)
                            .map(TemporalRangedState::value));
                } else if (reader instanceof final SessionDb db) {
                    final SessionRequest request =
                            new SessionRequest(keyName.getBytes(StandardCharsets.UTF_8), eventTime.toEpochMilli());
                    return db
                            .getState(request)
                            .map(session -> ValBoolean.create(true))
                            .orElse(ValBoolean.create(false));
                }

                throw new RuntimeException("Unexpected state type: " + doc.getStateType());
            });
        } catch (final Exception e) {
            LOGGER.debug(e::getMessage, e);
            return ValNull.INSTANCE;
        }
    }

    private Val getVal(final Optional<StateValue> optional) {
        return optional
                .map(state -> (Val) ValString.create(state.toString()))
                .orElse(ValNull.INSTANCE);
    }

    private record Key(String mapName, String keyName, Instant eventTime) {

    }
}
