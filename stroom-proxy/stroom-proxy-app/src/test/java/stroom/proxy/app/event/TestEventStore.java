package stroom.proxy.app.event;

import stroom.meta.api.AttributeMap;
import stroom.proxy.app.DataDirProvider;
import stroom.proxy.app.ProxyConfig;
import stroom.proxy.app.handler.ReceiverFactory;
import stroom.proxy.repo.store.FileStores;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class TestEventStore {

    @Test
    void test() throws IOException {
        final Path dir = Files.createTempDirectory("stroom");
        final Path eventDir = dir.resolve("event");
        final FeedKey feedKey = new FeedKey("Test", "Raw Events");

        final ProxyConfig proxyConfig = Mockito.mock(ProxyConfig.class);
        Mockito.when(proxyConfig.getProxyId()).thenReturn("test-proxy");
        final EventStoreConfig eventStoreConfig = new EventStoreConfig();

        final ReceiverFactory receiveStreamHandlers = Mockito.mock(ReceiverFactory.class);
        final DataDirProvider dataDirProvider = () -> dir;
        final EventStore eventStore = new EventStore(
                receiveStreamHandlers,
                () -> proxyConfig,
                () -> eventStoreConfig,
                dataDirProvider,
                new FileStores());

        for (int i = 0; i < 10; i++) {
            final AttributeMap attributeMap = new AttributeMap();
            attributeMap.put("Feed", feedKey.feed());
            attributeMap.put("Type", feedKey.type());
            eventStore.consume(attributeMap, "requestUuid", "test");
        }

        final String expected =
                "\"headers\":[{\"name\":\"Feed\",\"value\":\"Test\"},{\"name\":\"Type\",\"value\":\"Raw Events\"}]," +
                        "\"detail\":\"test\"}";
        assertThat(EventStoreTestUtil.read(eventDir, feedKey)).contains(expected);
    }
}
