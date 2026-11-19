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

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;

/// A stored record's bytes as they are stored: what the preview runs over when the sample is a
/// stream (design 46 §6.5). The record used to be fetched as text, decoded in the feed's
/// encoding and re-encoded as UTF-8, so a binary sample's bytes from 0x80 up reached the
/// engine as something else — and a binary match, which counts and reads bytes, met different
/// ones. Read here, they are the bytes the engine meets in production.
///
/// The record is read as the stream viewer reads it (`DataServiceImpl.fetch`): only by a user
/// with the app permission to view data, then under their own permissions on the feed, with the
/// reading lent to a user who may use the data rather than view it
/// ([SecurityContext#useAsReadResult]); a record of a segmented stream with the stream's header
/// and footer around it, its index held within the stream as the viewer holds it; and no more
/// of it than the viewer would fetch, cut where a character ends.
class StreamSamples {

    private final Store store;
    private final SecurityContext securityContext;
    private final Provider<SourceConfig> sourceConfigProvider;

    @Inject
    StreamSamples(final Store store,
                  final SecurityContext securityContext,
                  final Provider<SourceConfig> sourceConfigProvider) {
        this.store = store;
        this.securityContext = securityContext;
        this.sourceConfigProvider = sourceConfigProvider;
    }

    /// The bytes of the record a location names.
    ///
    /// @param location The record.
    /// @return Its bytes, and whether there were more than the limit allowed.
    /// @throws UncheckedIOException When the record cannot be read.
    Sample read(final SourceLocation location) {
        final long limit = sourceConfigProvider.get().getMaxCharactersPerFetch();
        return securityContext.secureResult(AppPermission.VIEW_DATA_PERMISSION, () ->
                securityContext.useAsReadResult(() -> readPermitted(location, limit)));
    }

    private Sample readPermitted(final SourceLocation location, final long limit) {
        if (location.getPartIndex() < 0) {
            return new Sample(new byte[0], false);
        }
        try (final Source source = store.openSource(location.getMetaId(), true);
                final InputStreamProvider provider = source.get(location.getPartIndex());
                final SegmentInputStream in = provider.get(location.getOptChildType().orElse(null))) {
            final long count = in.count();
            if (count > 1) {
                if (location.getRecordIndex() < 0) {
                    // Not yet on a record, as the viewer treats it: nothing to show
                    return new Sample(new byte[0], false);
                }
                in.include(0);
                if (count > 2) {
                    // One record of a segmented stream, as the viewer shows it: the header, the
                    // record (the segments between the header and the footer), and the footer,
                    // a record past the last being the last
                    in.include(Math.min(location.getRecordIndex(), count - 3) + 1);
                }
                in.include(count - 1);
            }
            return readUpTo(in, limit);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// At most `limit` bytes of a stream, and whether it held more. Cut short, the bytes end where
    /// a UTF-8 character does: a text record cut inside a character would no longer be UTF-8, and
    /// would be shown as no text at all.
    ///
    /// @param in The stream.
    /// @param limit The most to read.
    /// @return What was read.
    /// @throws IOException When the stream cannot be read.
    static Sample readUpTo(final InputStream in, final long limit) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        long left = limit;
        while (left > 0) {
            final int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) {
                return new Sample(out.toByteArray(), false);
            }
            out.write(buffer, 0, read);
            left -= read;
        }
        final byte[] bytes = out.toByteArray();
        if (in.read() < 0) {
            return new Sample(bytes, false);
        }
        return new Sample(Arrays.copyOf(bytes, characterEnd(bytes)), true);
    }

    // Where the bytes end if a UTF-8 character begun in the last three is left out: the length
    // itself where none is. Binary bytes may look like a character begun; cut short anyway, a
    // binary record loses nothing it had.
    private static int characterEnd(final byte[] bytes) {
        for (int back = 1; back <= Math.min(3, bytes.length); back++) {
            final int b = bytes[bytes.length - back] & 0xff;
            if ((b & 0xC0) == 0x80) {
                // A continuation: keep looking for what it continues
                continue;
            }
            final int length = b >= 0xF0
                    ? 4
                    : b >= 0xE0
                            ? 3
                            : b >= 0xC0
                                    ? 2
                                    : 1;
            return length > back
                    ? bytes.length - back
                    : bytes.length;
        }
        return bytes.length;
    }

    /// A record's bytes, as many as were read.
    ///
    /// @param bytes The bytes.
    /// @param truncated Whether the record held more than were read.
    record Sample(byte[] bytes, boolean truncated) {

    }
}
