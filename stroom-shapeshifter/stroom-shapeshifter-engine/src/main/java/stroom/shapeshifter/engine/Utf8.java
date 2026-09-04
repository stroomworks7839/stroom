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

package stroom.shapeshifter.engine;

/** What the sinks need to know about UTF-8 that {@link java.nio.charset} does not say cheaply. */
final class Utf8 {

    private Utf8() {
    }

    /**
     * How many bytes at the end of the array begin a sequence the array does not finish — zero
     * when it ends on a character boundary. A sink that receives bytes in pieces holds these
     * back for the next piece rather than decoding them into replacement characters.
     */
    static int incompleteTail(final byte[] bytes) {
        for (int back = 1; back <= 3 && back <= bytes.length; back++) {
            final int b = bytes[bytes.length - back] & 0xFF;
            if ((b & 0xC0) != 0x80) {
                final int needed = b < 0x80 ? 1 : b < 0xE0 ? 2 : b < 0xF0 ? 3 : 4;
                return needed > back ? back : 0;
            }
        }
        return 0;
    }
}
