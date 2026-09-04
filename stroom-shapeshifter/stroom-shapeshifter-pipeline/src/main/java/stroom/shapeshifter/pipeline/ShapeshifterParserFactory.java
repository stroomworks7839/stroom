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

import stroom.pipeline.xml.converter.ParserFactory;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.function.FunctionRegistry;

import org.xml.sax.XMLReader;

/**
 * Compiles a configuration once and hands out parsers over it, the way {@code DS3ParserFactory}
 * does for DS3: configuration is the expensive step and a parser is cheap.
 *
 * <p>How a factory is <i>found</i> — the document type D10 promised, the chooser beside
 * {@code DSChooser}, the Guice binding — is phase 1's, and depends on what phase 0's build
 * check says about where this module can live.
 */
public class ShapeshifterParserFactory implements ParserFactory {

    private final CompiledProject compiled;

    public ShapeshifterParserFactory(final Project project) {
        this(project, FunctionRegistry.EMPTY);
    }

    /** Compile against the functions the configuration may call (design 26). */
    public ShapeshifterParserFactory(final Project project, final FunctionRegistry registry) {
        this.compiled = Shapeshifter.compile(project, registry);
    }

    @Override
    public XMLReader getParser() {
        return new ShapeshifterReader(compiled);
    }
}
