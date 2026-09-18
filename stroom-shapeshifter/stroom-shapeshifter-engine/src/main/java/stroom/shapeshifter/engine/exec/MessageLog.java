/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.Instrument;
import stroom.shapeshifter.engine.Message;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.LongSupplier;

/**
 * The run's messages: the list every part of the run adds to, which a watched run also tells
 * the instrument about as each is said, with the frame open at the time
 * ({@link Instrument#onMessage}). An unwatched run has a list and nothing else.
 */
final class MessageLog extends ArrayList<Message> {

    private final Instrument instrument;
    private LongSupplier frame = () -> Instrument.ROOT_FRAME;

    MessageLog(final Instrument instrument) {
        this.instrument = instrument;
    }

    /** Who says which frame is open; the level, once it exists. */
    void frames(final LongSupplier frame) {
        this.frame = frame;
    }

    @Override
    public boolean add(final Message message) {
        if (instrument != Instrument.NONE) {
            instrument.onMessage(frame.getAsLong(), message);
        }
        return super.add(message);
    }

    @Override
    public boolean addAll(final Collection<? extends Message> messages) {
        boolean changed = false;
        for (final Message message : messages) {
            changed |= add(message);
        }
        return changed;
    }
}
