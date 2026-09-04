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

package stroom.shapeshifter.pipeline.function;

import stroom.meta.shared.Meta;
import stroom.pipeline.state.MetaHolder;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.shapeshifter.pipeline.RunLocations;

import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.AttributesImpl;

/**
 * {@code source}: as {@code stroom.pipeline.xsltfunctions.Source}: where the running record is —
 * stream id, part, record number, lines and columns — as a {@code source} element in the
 * {@code stroom-meta} namespace, as text (design 26 ruling 4).
 */
public final class SourceFunction extends StroomFunction {

    public SourceFunction() {
        super("source", Signature.of(Kind.STRING), Purity.CONTEXT);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final MetaHolder metaHolder = context.service(MetaHolder.class);
            final Meta meta = Holders.meta(context);
            final RunLocations locations = context.service(RunLocations.class);
            final long from = LocationFunction.from(context);
            if (meta == null || locations == null || from < 0) {
                return null;
            }
            try {
                final long to = LocationFunction.to(context);
                final XmlText xml = new XmlText();
                final ContentHandler handler = xml.handler();
                handler.startDocument();
                handler.startPrefixMapping("", MetaXml.URI);
                handler.startElement(MetaXml.URI, "source", "source", new AttributesImpl());
                MetaXml.data(handler, "id", meta.getId());
                MetaXml.data(handler, "partNo", metaHolder.getPartNo());
                MetaXml.data(handler, "recordNo", context.recordNumber());
                MetaXml.data(handler, "lineFrom", locations.line(from));
                MetaXml.data(handler, "colFrom", locations.column(from));
                MetaXml.data(handler, "lineTo", locations.line(to));
                MetaXml.data(handler, "colTo", locations.column(to));
                handler.endElement(MetaXml.URI, "source", "source");
                handler.endPrefixMapping("");
                handler.endDocument();
                return text(xml.text());
            } catch (final Exception e) {
                context.error(e.getMessage());
                return null;
            }
        };
    }
}
