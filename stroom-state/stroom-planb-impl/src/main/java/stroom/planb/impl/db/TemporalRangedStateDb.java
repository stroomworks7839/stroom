package stroom.planb.impl.db;

import stroom.bytebuffer.impl6.ByteBufferFactory;
import stroom.lmdb2.BBKV;
import stroom.planb.impl.db.TemporalRangedState.Key;
import stroom.planb.shared.PlanBDoc;
import stroom.planb.shared.TemporalRangedStateSettings;

import org.lmdbjava.CursorIterable;
import org.lmdbjava.CursorIterable.KeyVal;
import org.lmdbjava.KeyRange;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;

public class TemporalRangedStateDb extends AbstractDb<Key, StateValue> {

    TemporalRangedStateDb(final Path path,
                          final ByteBufferFactory byteBufferFactory) {
        this(
                path,
                byteBufferFactory,
                TemporalRangedStateSettings.builder().build(),
                false);
    }

    TemporalRangedStateDb(final Path path,
                          final ByteBufferFactory byteBufferFactory,
                          final TemporalRangedStateSettings settings,
                          final boolean readOnly) {
        super(
                path,
                byteBufferFactory,
                new TemporalRangedStateSerde(byteBufferFactory),
                settings.getMaxStoreSize(),
                settings.getOverwrite(),
                readOnly);
    }

    public static TemporalRangedStateDb create(final Path path,
                                               final ByteBufferFactory byteBufferFactory,
                                               final PlanBDoc doc,
                                               final boolean readOnly) {
        if (doc.getSettings() instanceof final TemporalRangedStateSettings temporalRangedStateSettings) {
            return new TemporalRangedStateDb(path, byteBufferFactory, temporalRangedStateSettings, readOnly);
        } else {
            throw new RuntimeException("No temporal ranged state settings provided");
        }
    }

    public Optional<TemporalRangedState> getState(final TemporalRangedStateRequest request) {
        final ByteBuffer start = byteBufferFactory.acquire(Long.BYTES);
        try {
            start.putLong(request.key());
            start.flip();

            final KeyRange<ByteBuffer> keyRange = KeyRange.atLeastBackward(start);
            return read(readTxn -> {
                Optional<TemporalRangedState> result = Optional.empty();
                try (final CursorIterable<ByteBuffer> cursor = dbi.iterate(readTxn, keyRange)) {
                    final Iterator<KeyVal<ByteBuffer>> iterator = cursor.iterator();
                    while (iterator.hasNext()
                           && !Thread.currentThread().isInterrupted()) {
                        final BBKV kv = BBKV.create(iterator.next());
                        final long keyStart = kv.key().getLong(0);
                        final long keyEnd = kv.key().getLong(Long.BYTES);
                        final long effectiveTime = kv.key().getLong(Long.BYTES + Long.BYTES);
                        if (keyEnd < request.key()) {
                            return result;
                        } else if (effectiveTime >= request.effectiveTime() &&
                                   keyStart <= request.key()) {
                            final Key key = Key
                                    .builder()
                                    .keyStart(keyStart)
                                    .keyEnd(keyEnd)
                                    .effectiveTime(effectiveTime)
                                    .build();
                            final StateValue value = serde.getVal(kv);
                            result = Optional.of(new TemporalRangedState(key, value));
                        }
                    }
                }
                return result;
            });
        } finally {
            byteBufferFactory.release(start);
        }
    }

    // TODO: Note that LMDB does not free disk space just because you delete entries, instead it just frees pages for
    //  reuse. We might want to create a new compacted instance instead of deleting in place.
    @Override
    public void condense(final long condenseBeforeMs,
                         final long deleteBeforeMs) {
        write(writer -> {
            Key lastKey = null;
            StateValue lastValue = null;
            try (final CursorIterable<ByteBuffer> cursor = dbi.iterate(writer.getWriteTxn())) {
                final Iterator<KeyVal<ByteBuffer>> iterator = cursor.iterator();
                while (iterator.hasNext()
                       && !Thread.currentThread().isInterrupted()) {
                    final BBKV kv = BBKV.create(iterator.next());
                    final Key key = serde.getKey(kv);
                    final StateValue value = serde.getVal(kv);

                    if (key.effectiveTime() <= deleteBeforeMs) {
                        // If this is data we no longer want to retain then delete it.
                        dbi.delete(writer.getWriteTxn(), kv.key(), kv.val());
                        writer.tryCommit();

                    } else {
                        if (lastKey != null &&
                            lastKey.keyStart() == key.keyStart() &&
                            lastKey.keyEnd() == key.keyEnd() &&
                            lastValue.byteBuffer().equals(value.byteBuffer())) {
                            if (key.effectiveTime() <= condenseBeforeMs) {
                                // If the key and value are the same then delete the duplicate entry.
                                dbi.delete(writer.getWriteTxn(), kv.key(), kv.val());
                                writer.tryCommit();
                            }
                        }

                        lastKey = key;
                        lastValue = value;
                    }
                }
            }
        });
    }
}
