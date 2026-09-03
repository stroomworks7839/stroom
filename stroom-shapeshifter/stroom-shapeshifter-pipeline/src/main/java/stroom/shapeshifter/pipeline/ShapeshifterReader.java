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

import stroom.pipeline.errorhandler.ErrorHandlerAdaptor;
import stroom.pipeline.xml.converter.AbstractParser;
import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.util.shared.Severity;
import stroom.util.xml.SAXParserFactoryFactory;

import org.xml.sax.ContentHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.LocatorImpl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * A Shapeshifter configuration as a pipeline parser: bytes in, SAX events out.
 *
 * <p>This is design 21's phase 1 — parse and forward. The configuration runs to a byte buffer
 * exactly as it would to a file, the buffer is parsed by the same hardened {@link SAXParserFactory}
 * every Stroom parser is built from, and the events go to the content handler. D37 ruled complete
 * inputs only, so the output of one input is one document, and buffering it is the honest shape
 * rather than a shortcut: a pipe would add a thread to carry a stream the engine never produces.
 *
 * <p>Two kinds of error, kept apart on their way to the error handler. The engine's own
 * {@link Message}s — a template that did not match, content nothing consumed — are the
 * configuration speaking about the <i>input</i>, and are reported with their severity and no
 * location, because the engine's messages carry none. A parse error is the output failing to be
 * XML, which is the configuration speaking about <i>itself</i>: its line and column are in text
 * the user never sees, so the message says so and quotes the offending output line, and the
 * location handed on is deliberately unknown rather than a number that would be read as an input
 * position. The document locator the content handler receives is the parser's, and its positions
 * are in the output too — a known limit of this phase that the native path (design 20, phase 2)
 * removes.
 *
 * <p>An input that arrives as characters rather than bytes — which is how the pipeline hands data
 * to a parser — is re-encoded as UTF-8 before the engine sees it. The engine matches bytes and the
 * configuration declares their encoding; by the time the pipeline has decoded the stream that
 * declaration can only truthfully be UTF-8.
 */
public class ShapeshifterReader extends AbstractParser {

    private static final SAXParserFactory PARSER_FACTORY = SAXParserFactoryFactory.newInstance();
    private static final int EXCERPT_LENGTH = 200;
    private static final Locator UNLOCATED = unlocated();

    private final CompiledProject compiled;

    public ShapeshifterReader(final CompiledProject compiled) {
        this.compiled = Objects.requireNonNull(compiled, "compiled");
    }

    /** The configuration this parser runs. */
    public CompiledProject compiled() {
        return compiled;
    }

    @Override
    public void parse(final InputSource input) throws IOException, SAXException {
        final byte[] bytes = bytesOf(input);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.runWhole(compiled, bytes, OutputSink.of(output));

        boolean fatal = false;
        for (final Message message : messages) {
            report(message);
            fatal |= message.severity() == stroom.shapeshifter.engine.Severity.FATAL;
        }
        if (fatal) {
            // A run that could not read its input did not produce a document; parsing what it
            // managed to write would only add a second, misleading error to the first.
            return;
        }
        forward(output.toByteArray());
    }

    // -----------------------------------------------------------------------------------
    // Forwarding the output as events
    // -----------------------------------------------------------------------------------

    private void forward(final byte[] output) throws IOException, SAXException {
        final ContentHandler contentHandler = getContentHandler();
        if (contentHandler == null) {
            throw new SAXException("No content handler set");
        }
        final XMLReader reader;
        try {
            reader = PARSER_FACTORY.newSAXParser().getXMLReader();
        } catch (final ParserConfigurationException e) {
            throw new SAXException(e);
        }
        final OutputErrorHandler errors = new OutputErrorHandler(output);
        reader.setContentHandler(contentHandler);
        reader.setErrorHandler(errors);
        try {
            reader.parse(new InputSource(new ByteArrayInputStream(output)));
        } catch (final SAXParseException e) {
            if (!errors.reportedFatal) {
                // Not the parser's — a downstream filter threw it — so it is not ours to swallow.
                throw e;
            }
            // The parser stops after a fatal error, which has already been reported in the output's
            // own terms. Like DS3, the stream carries the error rather than the parser throwing it.
        }
    }

    /**
     * Rewrites the parser's errors into the output's terms before handing them on.
     *
     * <p>The parser reports a line and column in the generated text. Passed on as they are, the
     * pipeline would show them as input positions, which they are not. So the message says where
     * the position is and quotes the line, and the location handed on is unknown.
     */
    private final class OutputErrorHandler implements ErrorHandler {

        private final byte[] output;
        private boolean reportedFatal;

        private OutputErrorHandler(final byte[] output) {
            this.output = output;
        }

        @Override
        public void warning(final SAXParseException exception) throws SAXException {
            final ErrorHandler handler = getErrorHandler();
            if (handler != null) {
                handler.warning(rewrite(exception));
            }
        }

        @Override
        public void error(final SAXParseException exception) throws SAXException {
            final ErrorHandler handler = getErrorHandler();
            if (handler != null) {
                handler.error(rewrite(exception));
            }
        }

        @Override
        public void fatalError(final SAXParseException exception) throws SAXException {
            reportedFatal = true;
            final ErrorHandler handler = getErrorHandler();
            if (handler != null) {
                handler.fatalError(rewrite(exception));
            }
        }

        private SAXParseException rewrite(final SAXParseException exception) {
            final int line = exception.getLineNumber();
            final int column = exception.getColumnNumber();
            final StringBuilder text = new StringBuilder()
                    .append("The configuration's output is not well-formed XML: ")
                    .append(exception.getMessage())
                    .append(" — at line ").append(line).append(", column ").append(column)
                    .append(" of the output, not the input");
            final String excerpt = line(line);
            if (excerpt != null) {
                text.append(". Output line ").append(line).append(": ").append(excerpt);
            }
            return new SAXParseException(text.toString(), null, null, -1, -1, exception);
        }

        private String line(final int number) {
            if (number < 1) {
                return null;
            }
            final String[] lines = new String(output, StandardCharsets.UTF_8).split("\n", -1);
            if (number > lines.length) {
                return null;
            }
            final String line = lines[number - 1].strip();
            return line.length() <= EXCERPT_LENGTH
                    ? line
                    : line.substring(0, EXCERPT_LENGTH) + "…";
        }
    }

    // -----------------------------------------------------------------------------------
    // The engine's messages
    // -----------------------------------------------------------------------------------

    private void report(final Message message) throws SAXException {
        final ErrorHandler handler = getErrorHandler();
        if (handler == null) {
            return;
        }
        if (handler instanceof final ErrorHandlerAdaptor adaptor) {
            // The pipeline's handler can take a severity directly, as DS3 relies on.
            adaptor.log(severity(message.severity()), UNLOCATED, message.text(), null);
            return;
        }
        final SAXParseException exception = new SAXParseException(message.text(), null, null, -1, -1);
        switch (message.severity()) {
            case INFO, WARNING -> handler.warning(exception);
            case ERROR -> handler.error(exception);
            case FATAL -> handler.fatalError(exception);
        }
    }

    private static Severity severity(final stroom.shapeshifter.engine.Severity severity) {
        return switch (severity) {
            case INFO -> Severity.INFO;
            case WARNING -> Severity.WARNING;
            case ERROR -> Severity.ERROR;
            case FATAL -> Severity.FATAL_ERROR;
        };
    }

    // -----------------------------------------------------------------------------------
    // The input's bytes
    // -----------------------------------------------------------------------------------

    private static byte[] bytesOf(final InputSource input) throws IOException, SAXException {
        final InputStream byteStream = input.getByteStream();
        if (byteStream != null) {
            return byteStream.readAllBytes();
        }
        final Reader characterStream = input.getCharacterStream();
        if (characterStream != null) {
            final StringBuilder text = new StringBuilder();
            final char[] buffer = new char[8192];
            int read;
            while ((read = characterStream.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
            return text.toString().getBytes(StandardCharsets.UTF_8);
        }
        throw new SAXException("The input source carries neither bytes nor characters");
    }

    private static Locator unlocated() {
        final LocatorImpl locator = new LocatorImpl();
        locator.setLineNumber(-1);
        locator.setColumnNumber(-1);
        return locator;
    }
}
