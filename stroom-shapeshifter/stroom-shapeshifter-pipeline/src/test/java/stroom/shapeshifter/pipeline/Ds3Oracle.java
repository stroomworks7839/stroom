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

import stroom.pipeline.DefaultLocationFactory;
import stroom.pipeline.errorhandler.ErrorHandlerAdaptor;
import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.xml.converter.ds3.ConfigFilter;
import stroom.pipeline.xml.converter.ds3.DS3Parser;
import stroom.pipeline.xml.converter.ds3.RootFactory;
import stroom.pipeline.xml.converter.ds3.ref.VarMap;
import stroom.util.shared.ElementId;
import stroom.util.xml.SAXParserFactoryFactory;

import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.StringReader;

/**
 * Stroom's own DS3, built the way {@code TestDS3} builds it minus the schema validation: the
 * config is fed to {@link ConfigFilter} by a plain parser, compiled, and instantiated. This is the
 * oracle the legacy goldens came from, driven live.
 */
final class Ds3Oracle {

    private Ds3Oracle() {
    }

    static XMLReader parser(final String configXml) throws Exception {
        final RootFactory factory = new RootFactory();
        final ConfigFilter filter = new ConfigFilter(factory);
        final XMLReader configReader = SAXParserFactoryFactory.newInstance().newSAXParser().getXMLReader();
        configReader.setContentHandler(filter);
        filter.startProcessing();
        configReader.parse(new InputSource(new StringReader(configXml)));
        filter.endProcessing();
        factory.compile();
        return new DS3Parser(factory.newInstance(new VarMap()), RootFactory.MIN_BUFFER_SIZE, factory.getBufferSize());
    }

    /** The pipeline's error handler, which DS3 casts to and this parser recognises. */
    static ErrorHandler errorHandler(final String elementId, final LoggingErrorReceiver receiver) {
        return new ErrorHandlerAdaptor(new ElementId(elementId), new DefaultLocationFactory(), receiver);
    }
}
