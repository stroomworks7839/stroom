package stroom.planb.impl.db;

import stroom.bytebuffer.ByteBufferUtils;
import stroom.bytebuffer.impl6.ByteBufferFactory;
import stroom.lmdb2.BBKV;
import stroom.planb.impl.db.State.Key;
import stroom.query.language.functions.FieldIndex;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValNull;

import net.openhft.hashing.LongHashFunction;
import org.lmdbjava.CursorIterable.KeyVal;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * KEY =   <KEY_HASH>
 * VALUE = <KEY_LENGTH><KEY_BYTES><VALUE_TYPE><VALUE_BYTES>
 */
public class StateSerde implements Serde<Key, StateValue> {

    private static final int KEY_LENGTH = Long.BYTES;

    private final ByteBufferFactory byteBufferFactory;

    public StateSerde(final ByteBufferFactory byteBufferFactory) {
        this.byteBufferFactory = byteBufferFactory;
    }

    @Override
    public <T> T createKeyByteBuffer(final Key key, final Function<ByteBuffer, T> function) {
        final ByteBuffer keyByteBuffer = byteBufferFactory.acquire(KEY_LENGTH);
        try {
            // Hash the key.
            final long keyHash = LongHashFunction.xx3().hashBytes(key.bytes());
            keyByteBuffer.putLong(keyHash);
            keyByteBuffer.flip();
            return function.apply(keyByteBuffer);
        } finally {
            byteBufferFactory.release(keyByteBuffer);
        }
    }

    @Override
    public <R> R createValueByteBuffer(final Key key,
                                       final StateValue value,
                                       final Function<ByteBuffer, R> function) {
        final ByteBuffer valueByteBuffer = byteBufferFactory.acquire(Integer.BYTES +
                                                                     key.bytes().length +
                                                                     Byte.BYTES +
                                                                     value.byteBuffer().limit());
        try {
            putPrefix(valueByteBuffer, key.bytes());
            valueByteBuffer.put(value.typeId());
            valueByteBuffer.put(value.byteBuffer());
            valueByteBuffer.flip();
            return function.apply(valueByteBuffer);
        } finally {
            byteBufferFactory.release(valueByteBuffer);
        }
    }

    @Override
    public <R> R createPrefixPredicate(final Key key, final Function<Predicate<BBKV>, R> function) {
        final ByteBuffer prefixByteBuffer = byteBufferFactory.acquire(Integer.BYTES + key.bytes().length);
        try {
            putPrefix(prefixByteBuffer, key.bytes());
            prefixByteBuffer.flip();

            return function.apply(keyVal -> ByteBufferUtils.containsPrefix(keyVal.val(), prefixByteBuffer));
        } finally {
            byteBufferFactory.release(prefixByteBuffer);
        }
    }

    @Override
    public void createPrefixPredicate(final BBKV kv, final Consumer<Predicate<BBKV>> consumer) {
        final int keyLength = kv.val().getInt(0);
        final ByteBuffer slice = kv.val().slice(0, Integer.BYTES + keyLength);
        consumer.accept(keyVal -> ByteBufferUtils.containsPrefix(keyVal.val(), slice));
    }

    @Override
    public boolean hasPrefix() {
        return true;
    }

    private void putPrefix(final ByteBuffer byteBuffer, final byte[] keyBytes) {
        byteBuffer.putInt(keyBytes.length);
        byteBuffer.put(keyBytes);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Function<KeyVal<ByteBuffer>, Val>[] getValExtractors(final FieldIndex fieldIndex) {
        final Function<KeyVal<ByteBuffer>, Val>[] functions = new Function[fieldIndex.size()];
        for (int i = 0; i < fieldIndex.getFields().length; i++) {
            final String field = fieldIndex.getField(i);
            functions[i] = switch (field) {
                case StateFields.KEY -> kv -> {
                    final int keyLength = kv.val().getInt(0);
                    return ValUtil.getValue((byte) 0, kv.val().slice(Integer.BYTES, keyLength));
                };
                case StateFields.VALUE_TYPE -> kv -> {
                    final int keyLength = kv.val().getInt(0);
                    final byte typeId = kv.val().get(Integer.BYTES + keyLength);
                    return ValUtil.getType(typeId);
                };
                case StateFields.VALUE -> kv -> {
                    final int keyLength = kv.val().getInt(0);
                    final byte typeId = kv.val().get(Integer.BYTES + keyLength);
                    final int valueStart = Integer.BYTES + keyLength + Byte.BYTES;
                    return ValUtil.getValue(typeId, kv.val().slice(valueStart, kv.val().limit() - valueStart));
                };
                default -> byteBuffer -> ValNull.INSTANCE;
            };
        }
        return functions;
    }

    @Override
    public Key getKey(final BBKV kv) {
        final ByteBuffer byteBuffer = kv.val();
        final int keyLength = byteBuffer.getInt(0);
        final ByteBuffer slice = byteBuffer.slice(Integer.BYTES, keyLength);
        final byte[] keyBytes = ByteBufferUtils.toBytes(slice);
        return new Key(keyBytes);
    }

    @Override
    public StateValue getVal(final BBKV kv) {
        final ByteBuffer byteBuffer = kv.val();
        final int keyLength = byteBuffer.getInt(0);
        final byte typeId = byteBuffer.get(Integer.BYTES + keyLength);
        final int valueStart = Integer.BYTES + keyLength + Byte.BYTES;
        final ByteBuffer slice = byteBuffer.slice(valueStart, byteBuffer.limit() - valueStart);
        final byte[] valueBytes = ByteBufferUtils.toBytes(slice);
        return new StateValue(typeId, ByteBuffer.wrap(valueBytes));
    }

    @Override
    public int getKeyLength() {
        return KEY_LENGTH;
    }
}
