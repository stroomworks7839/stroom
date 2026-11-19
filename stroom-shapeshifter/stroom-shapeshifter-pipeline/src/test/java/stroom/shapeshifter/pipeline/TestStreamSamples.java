/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.pipeline;

import stroom.data.store.api.InputStreamProvider;
import stroom.data.store.api.SegmentInputStream;
import stroom.data.store.api.Source;
import stroom.data.store.api.Store;
import stroom.pipeline.shared.SourceLocation;
import stroom.security.api.SecurityContext;
import stroom.security.shared.AppPermission;
import stroom.ui.config.shared.SourceConfig;
import stroom.util.shared.PermissionException;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// A record read as it is stored (design 46 §6.5): its bytes, not text decoded and re-encoded
class TestStreamSamples {

    // A varint length and that many bytes: "Alice", then two bytes no text decoding keeps
    private static final byte[] BINARY = {5, 'A', 'l', 'i', 'c', 'e', 2, (byte) 0x80, (byte) 0xFF};

    @Test
    void aRecordIsItsBytesAsStored() throws IOException {
        final Fixture fixture = new Fixture(new Segments(BINARY, 1));

        final StreamSamples.Sample sample = fixture.samples().read(SourceLocation.builder(42L).build());

        assertThat(sample.bytes()).containsExactly(BINARY);
        assertThat(sample.truncated()).isFalse();
        // Read as the viewer reads: by a user who may view data, with the reading lent to a
        // user who may use it
        verify(fixture.security).secureResult(eq(AppPermission.VIEW_DATA_PERMISSION), any());
        verify(fixture.security).useAsReadResult(any());
        verify(fixture.store).openSource(eq(42L), eq(true));
    }

    @Test
    void aUserWhoMayNotViewDataReadsNothing() throws IOException {
        // The viewer's fetch asks for the View Data permission; so does the preview's read
        final Fixture fixture = new Fixture(new Segments(BINARY, 1));
        when(fixture.security.secureResult(eq(AppPermission.VIEW_DATA_PERMISSION), any()))
                .thenThrow(new PermissionException(null, "You do not have permission to view data"));

        assertThatThrownBy(() -> fixture.samples().read(SourceLocation.builder(42L).build()))
                .isInstanceOf(PermissionException.class);
        verify(fixture.store, never()).openSource(anyLong(), anyBoolean());
    }

    @Test
    void aRecordPastTheLastIsTheLastAndNoneYetIsNothing() throws IOException {
        // As the viewer holds it: header, three records, footer, and record 7 asked for
        final Segments segments = new Segments(BINARY, 5);
        new Fixture(segments).samples().read(SourceLocation.builder(42L).withRecordIndex(7L).build());
        assertThat(segments.included).containsExactly(0L, 3L, 4L);

        final StreamSamples.Sample none = new Fixture(new Segments(BINARY, 5)).samples()
                .read(SourceLocation.builder(42L).withRecordIndex(-1L).build());
        assertThat(none.bytes()).isEmpty();

        // A header and a footer with no record between them is those two
        final Segments empty = new Segments(BINARY, 2);
        new Fixture(empty).samples().read(SourceLocation.builder(42L).build());
        assertThat(empty.included).containsExactly(0L, 1L);
    }

    @Test
    void aRecordCutShortEndsWhereACharacterDoes() throws IOException {
        // "aé" is 61 C3 A9: cut at two bytes, the é is left out whole rather than half kept,
        // which would make the text no UTF-8 at all
        final byte[] text = {'a', (byte) 0xC3, (byte) 0xA9, 'b'};
        assertThat(StreamSamples.readUpTo(new ByteArrayInputStream(text), 2).bytes()).containsExactly('a');
        assertThat(StreamSamples.readUpTo(new ByteArrayInputStream(text), 3).bytes())
                .containsExactly('a', (byte) 0xC3, (byte) 0xA9);
    }

    @Test
    void aRecordOfASegmentedStreamHasTheStreamsHeaderAndFooterAroundIt() throws IOException {
        // Header, three records, footer
        final Segments segments = new Segments(BINARY, 5);
        final Fixture fixture = new Fixture(segments);

        fixture.samples().read(SourceLocation.builder(42L).withRecordIndex(1L).build());

        assertThat(segments.included).containsExactly(0L, 2L, 4L);
    }

    @Test
    void noMoreIsReadThanTheViewerWouldFetch() throws IOException {
        final StreamSamples.Sample sample = StreamSamples.readUpTo(new ByteArrayInputStream(BINARY), 4);

        assertThat(sample.bytes()).containsExactly(5, 'A', 'l', 'i');
        assertThat(sample.truncated()).isTrue();
        // Exactly the limit is all of it
        assertThat(StreamSamples.readUpTo(new ByteArrayInputStream(BINARY), BINARY.length).truncated()).isFalse();
    }

    // A store holding one record, under a security context that runs what it is lent
    private static final class Fixture {

        private final Store store = mock(Store.class);
        private final SecurityContext security = mock(SecurityContext.class);

        @SuppressWarnings("unchecked")
        private Fixture(final SegmentInputStream data) throws IOException {
            final Source source = mock(Source.class);
            final InputStreamProvider provider = mock(InputStreamProvider.class);
            when(store.openSource(anyLong(), eq(true))).thenReturn(source);
            when(source.get(0L)).thenReturn(provider);
            when(provider.get(null)).thenReturn(data);
            when(security.useAsReadResult(any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(0)).get());
            when(security.secureResult(any(AppPermission.class), any()))
                    .thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
        }

        private StreamSamples samples() {
            return new StreamSamples(store, security, SourceConfig::new);
        }
    }

    // Bytes in a given number of segments, remembering which were included
    private static final class Segments extends SegmentInputStream {

        private final ByteArrayInputStream in;
        private final long size;
        private final long count;
        private final List<Long> included = new ArrayList<>();

        private Segments(final byte[] bytes, final long count) {
            this.in = new ByteArrayInputStream(bytes);
            this.size = bytes.length;
            this.count = count;
        }

        @Override
        public int read() {
            return in.read();
        }

        @Override
        public int read(final byte[] b, final int off, final int len) {
            return in.read(b, off, len);
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long count() {
            return count;
        }

        @Override
        public void include(final long segment) {
            included.add(segment);
        }

        @Override
        public void includeAll() {
        }

        @Override
        public void exclude(final long segment) {
        }

        @Override
        public void excludeAll() {
        }
    }
}
