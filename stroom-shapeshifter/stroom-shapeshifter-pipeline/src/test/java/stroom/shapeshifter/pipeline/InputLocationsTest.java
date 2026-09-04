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

import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.shapeshifter.engine.ds3.Ds3Migration;

import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 21 phase 4 (and 1b's open item): the locator the pipeline sees points into the input.
 * Fixture 001 has a header line and six records on lines 2–7; each {@code <record>} and each
 * {@code <data>} inside it must report the line of the CSV row that produced it.
 */
class InputLocationsTest {

    private static final Path LEGACY = Paths.get(
            "..", "stroom-shapeshifter-engine", "src", "test", "resources", "fixtures", "legacy");

    @Test
    void everyRecordAndItsDataReportTheInputLineTheyCameFrom() throws Exception {
        final String config = Files.readString(LEGACY.resolve("001_csv_with_header.ds3.xml"));
        final byte[] input = Files.readAllBytes(LEGACY.resolve("001_csv_with_header.in"));

        final List<Integer> recordLines = new ArrayList<>();
        final List<Integer> dataLines = new ArrayList<>();
        final XMLReader reader = new ShapeshifterParserFactory(Ds3Migration.importXml(config)).getParser();
        reader.setContentHandler(new DefaultHandler() {
            private Locator locator;

            @Override
            public void setDocumentLocator(final Locator locator) {
                this.locator = locator;
            }

            @Override
            public void startElement(final String uri, final String local, final String qName, final Attributes atts) {
                if (local.equals("record")) {
                    recordLines.add(locator.getLineNumber());
                } else if (local.equals("data")) {
                    dataLines.add(locator.getLineNumber());
                }
            }
        });
        reader.setErrorHandler(Ds3Oracle.errorHandler("ShapeshifterParser", new LoggingErrorReceiver()));
        reader.parse(new InputSource(new ByteArrayInputStream(input)));

        assertThat(recordLines).containsExactly(2, 3, 4, 5, 6, 7);
        // Four data elements per record, each on its record's line.
        assertThat(dataLines).hasSize(24);
        for (int i = 0; i < dataLines.size(); i++) {
            assertThat(dataLines.get(i)).as("data " + i).isEqualTo(2 + i / 4);
        }
    }

    @Test
    void fiveThousandRecordsResolveInOneSweep() throws Exception {
        // Phase 4's audit: the first resolver scanned every span for every event, which is fine
        // for six records and a matter of minutes for a feed. This many would show it.
        final String config = Files.readString(LEGACY.resolve("001_csv_with_header.ds3.xml"));
        final StringBuilder csv = new StringBuilder("dt,who,where,what\n");
        for (int i = 0; i < 5000; i++) {
            csv.append("2020-06-17T08:00:00.000Z,user").append(i).append(",office,logon\n");
        }
        final List<Integer> recordLines = new ArrayList<>();
        final XMLReader reader = new ShapeshifterParserFactory(Ds3Migration.importXml(config)).getParser();
        reader.setContentHandler(new DefaultHandler() {
            private Locator locator;

            @Override
            public void setDocumentLocator(final Locator locator) {
                this.locator = locator;
            }

            @Override
            public void startElement(final String uri, final String local, final String qName, final Attributes atts) {
                if (local.equals("record")) {
                    recordLines.add(locator.getLineNumber());
                }
            }
        });
        reader.setErrorHandler(Ds3Oracle.errorHandler("ShapeshifterParser", new LoggingErrorReceiver()));
        reader.parse(new InputSource(new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8))));

        assertThat(recordLines).hasSize(5000);
        for (int i = 0; i < 5000; i++) {
            assertThat(recordLines.get(i)).isEqualTo(2 + i);
        }
    }
}
