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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The corpus, generated — never committed as a file (design/13, Phase 1). Deterministic from
 * a fixed seed, {@code records:2} shaped (the input EVENTS.xsl was written for), and built so
 * neither contestant can cheat:
 *
 * <ul>
 *   <li>every record is unique — a sequential line number, a hash-varied user, a message
 *       carrying its own index;</li>
 *   <li>messages contain XML escapes ({@code &amp;}, {@code &lt;}) so both sides must treat
 *       escaping as real work;</li>
 *   <li>field order rotates across records, so nothing may assume position;</li>
 *   <li>an optional {@code Host} field appears on some records only, so conditionals run.</li>
 * </ul>
 */
final class RecordsGenerator {

    private RecordsGenerator() {
    }

    static byte[] generate(final int records) {
        final StringBuilder out = new StringBuilder(records * 260 + 256);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<records xmlns=\"records:2\" version=\"2.0\">\n");
        long seed = 0x5DEECE66DL;
        for (int i = 1; i <= records; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            final int userNo = (int) Math.floorMod(seed >>> 17, 5000);
            final int fileNo = 1 + (i % 97);
            final String date = "2026-08-21";
            final String time = String.format("%02d:%02d:%02d",
                    (i / 3600) % 24, (i / 60) % 60, i % 60);
            final String message = "Message " + i + " from run &amp; batch &lt;" + (seed & 0xFFF)
                                   + "&gt; done";
            final String[] fields = {
                    "<data name=\"Date\" value=\"" + date + "\"/>",
                    "<data name=\"Time\" value=\"" + time + "\"/>",
                    "<data name=\"FileNo\" value=\"" + fileNo + "\"/>",
                    "<data name=\"LineNo\" value=\"" + i + "\"/>",
                    "<data name=\"User\" value=\"user" + userNo + "\"/>",
                    "<data name=\"Message\" value=\"" + message + "\"/>",
            };
            out.append("  <record>");
            final int rotate = i % fields.length;
            for (int f = 0; f < fields.length; f++) {
                out.append(fields[(f + rotate) % fields.length]);
            }
            if (i % 3 == 0) {
                out.append("<data name=\"Host\" value=\"host-").append(fileNo).append("\"/>");
            }
            out.append("</record>\n");
        }
        out.append("</records>\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Convenience for tests that want the bytes as a stream-sized sanity print. */
    static int approximateBytesPerRecord() {
        final ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        return generate(1000).length / 1000;
    }
}
