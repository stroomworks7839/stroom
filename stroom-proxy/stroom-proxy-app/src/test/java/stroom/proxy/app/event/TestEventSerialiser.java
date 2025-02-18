package stroom.proxy.app.event;

import stroom.meta.api.AttributeMap;
import stroom.meta.api.StandardHeaderArguments;
import stroom.proxy.app.handler.ProxyReceiptIdGenerator;
import stroom.util.concurrent.UniqueId;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class TestEventSerialiser {

    @Test
    void test() throws IOException {
        final FeedKey feedKey = new FeedKey("test-feed", null);
        final AttributeMap attributeMap = new AttributeMap();
        attributeMap.put(StandardHeaderArguments.FEED, "test-feed");
        final String data = "this\nis some data \n with new \n\n lines";
        final EventSerialiser eventSerialiser = new EventSerialiser();
        final UniqueId receiptId = new ProxyReceiptIdGenerator(() -> "test-proxy").generateId();

        final String json = eventSerialiser.serialise(receiptId, feedKey, attributeMap, data);
        assertThat(json).contains("\"this\\nis some data \\n with new \\n\\n lines\"");
        assertThat(json).doesNotContain("\n");
    }
}
