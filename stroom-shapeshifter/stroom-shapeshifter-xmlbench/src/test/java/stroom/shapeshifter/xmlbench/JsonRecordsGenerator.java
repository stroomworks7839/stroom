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

package stroom.shapeshifter.xmlbench;

import java.nio.charset.StandardCharsets;

/**
 * The records corpus as JSON lines — one object per line, the same fields, values and count
 * as {@link RecordsGenerator}'s XML, so the two parse head-to-heads (design 40) read the
 * same data in the two shapes. Numbers are numbers, the message carries the two escapes a
 * configuration can decode with a replace chain — {@code \"} and {@code \n} — and no
 * {@code \\}, which a chain of replaces cannot decode correctly; the corpus says so rather
 * than pretending.
 */
final class JsonRecordsGenerator {

    private JsonRecordsGenerator() {
    }

    static byte[] generate(final int records) {
        final StringBuilder out = new StringBuilder(records * 200 + 64);
        long seed = 0x5DEECE66DL;
        for (int i = 1; i <= records; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            final int userNo = (int) Math.floorMod(seed >>> 17, 5000);
            final int fileNo = 1 + (i % 97);
            final String time = String.format("%02d:%02d:%02d", (i / 3600) % 24, (i / 60) % 60, i % 60);
            out.append("{\"date\":\"2026-08-21\",\"time\":\"").append(time)
                    .append("\",\"fileNo\":").append(fileNo)
                    .append(",\"lineNo\":").append(i)
                    .append(",\"user\":\"user").append(userNo)
                    .append("\",\"message\":\"Message ").append(i).append(" from run \\\"batch\\\" ")
                    .append(seed & 0xFFF).append("\\ndone\"");
            if (i % 3 == 0) {
                out.append(",\"host\":\"host-").append(fileNo).append('"');
            }
            out.append("}\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
