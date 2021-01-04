package stroom.query.common.v2;

import stroom.dashboard.expression.v1.Val;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.util.concurrent.TimeUnit;

public interface DataStore {
    Items get();

    Items get(final RawKey key);

    long getSize();

    long getTotalSize();


    void add(Val[] values);

    boolean readPayload(Input input);

    void writePayload(Output output);

    void clear();

    void complete();

    void awaitCompletion() throws InterruptedException;

    boolean awaitCompletion(final long timeout, final TimeUnit unit) throws InterruptedException;
}
