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

import stroom.pipeline.xml.converter.AbstractParser;
import stroom.shapeshifter.engine.compile.CompiledProject;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotSupportedException;

import java.io.IOException;
import java.util.Objects;

/**
 * A Shapeshifter configuration as a pipeline parser: bytes in, SAX events out.
 *
 * <p>Design 21 phase 0 creates the class to prove the module can exist — that this package can
 * see both {@code stroom-pipeline}'s parser contract and the engine — and nothing more. Phase 1
 * gives {@link #parse(InputSource)} its body: run the compiled project to a byte buffer, parse
 * the buffer, forward the events to the content handler, and keep the engine's messages and the
 * parser's errors apart on their way to the error handler.
 */
public class ShapeshifterParser extends AbstractParser {

    private final CompiledProject compiled;

    public ShapeshifterParser(final CompiledProject compiled) {
        this.compiled = Objects.requireNonNull(compiled, "compiled");
    }

    /** The configuration this parser runs. */
    public CompiledProject compiled() {
        return compiled;
    }

    @Override
    public void parse(final InputSource input) throws IOException, SAXException {
        throw new SAXNotSupportedException(
                "ShapeshifterParser.parse is design 21 phase 1; phase 0 only proves the module builds");
    }
}
