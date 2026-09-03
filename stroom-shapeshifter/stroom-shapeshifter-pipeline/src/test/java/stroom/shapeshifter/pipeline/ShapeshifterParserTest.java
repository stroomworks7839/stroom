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

import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.ProjectReader;

import org.junit.jupiter.api.Test;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 of design 21: the module exists and sees both sides.
 */
class ShapeshifterParserTest {

    private static final String MINIMAL = """
            {"name": "phase0", "version": 3,
             "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "auto"},
             "templates": []}
            """;

    @Test
    void theFactoryCompilesOnceAndTheParserIsAnXmlReader() {
        final Project project = ProjectReader.read(MINIMAL);
        final XMLReader reader = new ShapeshifterParserFactory(project).getParser();

        assertThat(reader).isInstanceOf(ShapeshifterParser.class);
        final DefaultHandler handler = new DefaultHandler();
        reader.setContentHandler(handler);
        assertThat(reader.getContentHandler()).isSameAs(handler);
    }
}
