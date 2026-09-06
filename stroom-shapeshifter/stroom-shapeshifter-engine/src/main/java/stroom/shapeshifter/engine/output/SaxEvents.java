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

package stroom.shapeshifter.engine.output;

import stroom.shapeshifter.engine.OutputSink.StructureException;

import org.xml.sax.SAXException;

/**
 * The events a SAX-delivering sink has made: each call counted, which is the position such a
 * sink reports (design 20 §5), and the handler's refusal turned into the sink's own.
 */
final class SaxEvents {

    /** One call on the handler. */
    interface Call {

        void run() throws SAXException;
    }

    private long count;

    /** Make the call and count it. */
    void make(final Call call) {
        try {
            call.run();
            count++;
        } catch (final SAXException e) {
            throw new StructureException("The event handler refused an event: " + e.getMessage());
        }
    }

    /** How many events have been made. */
    long count() {
        return count;
    }
}
