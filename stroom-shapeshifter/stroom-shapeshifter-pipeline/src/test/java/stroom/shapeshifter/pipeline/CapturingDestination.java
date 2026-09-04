/*
 * Copyright 2016-2026 Crown Copyright
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

import stroom.pipeline.destination.Destination;
import stroom.pipeline.destination.DestinationProvider;
import stroom.pipeline.factory.AbstractElement;
import stroom.pipeline.factory.Processor;
import stroom.util.shared.ElementId;

import java.io.OutputStream;
import java.util.List;

/** A destination that is one byte array — what a {@code FileAppender} is to a file. */
final class CapturingDestination extends AbstractElement implements DestinationProvider, Destination {

    private final OutputStream bytes;

    CapturingDestination(final OutputStream bytes) {
        this.bytes = bytes;
        setElementId(new ElementId("Destination"));
    }

    @Override
    public Destination borrowDestination() {
        return this;
    }

    @Override
    public void returnDestination(final Destination destination) {
    }

    @Override
    public OutputStream getOutputStream() {
        return bytes;
    }

    @Override
    public OutputStream getOutputStream(final byte[] header, final byte[] footer) {
        return bytes;
    }

    @Override
    public List<Processor> createProcessors() {
        return List.of();
    }
}
