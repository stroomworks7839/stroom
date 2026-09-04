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
import stroom.shapeshifter.engine.CharacterSink;
import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.SaxEventSink;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * A Shapeshifter configuration as a pipeline parser: bytes in, SAX events out.
 *
 * <p>The configuration runs straight into a sink that delivers as it goes (designs 22 and 24): a
 * structured configuration's elements as element events, a text configuration's output as
 * {@code characters} events, one per emitter write. Nothing is held and nothing is re-parsed. A
 * text configuration produces a document of characters and no elements — what {@code TextWriter}
 * consumes — and anything downstream that needs XML says so in its own terms (D42: the refusal
 * is the consumer's).
 *
 * <p>The engine's own {@link Message}s — a template that did not match, content nothing consumed
 * — are the configuration speaking about the <i>input</i>, and are reported with their severity
 * after the events, because the run collects them and the events cannot wait. The document
 * locator the content handler receives is not a parser's: every event carries the input position
 * of the innermost running match at the moment it was made ({@link InputLocations}), so stepping
 * and indicators point at the record that produced it.
 *
 * <p>The input is streamed through the engine's window (design 23), never read whole: the feed's
 * bytes as they are when the pipeline hands the element a byte stream, so the configuration's
 * {@code source.encoding} is real; or, when a reader element upstream has already decoded it,
 * characters encoded to UTF-8 as they are read, which is then the only encoding the configuration
 * can truthfully declare.
 *
 * <p>What remains of design 21 phase 1's parse-and-forward — {@link Run}, {@link #runStreamed},
 * {@link #forward(Run)} and the output parse behind it — serves the filter's byte path until
 * design 24 phase 2 moves that onto the character sink too, and goes then.
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
        // Design 23: the input is streamed through the engine's window, never read whole — a
        // byte stream as it is, a reader encoded to UTF-8 as it is read.
        final InputStream stream = streamOf(input);
        checkEncoding(input);
        parseLive(stream);
    }

    /**
     * The configuration runs straight into a sink that delivers as it goes: the event sink for a
     * structured configuration (design 22 phase 2), the character sink for a text one (design 24)
     * — no serialisation, no re-parse, no held output, and each event located live from the
     * innermost running match. The engine's messages come after the events, because the run
     * collects them and the events cannot wait. A text run has no root to end its document, so
     * the sink is told when the run is over.
     */
    private void parseLive(final InputStream input) throws SAXException {
        final ContentHandler target = getContentHandler();
        if (target == null) {
            throw new SAXException("No content handler set");
        }
        final InputLocations locations = new InputLocations();
        final InputLocations.LineIndex lines = new InputLocations.LineIndex(input);
        locations.bound(lines);
        final LiveLocatingHandler handler = new LiveLocatingHandler(target, locations, lines);
        List<Message> messages;
        if (compiled.structured()) {
            messages = Shapeshifter.run(compiled, lines, new SaxEventSink(handler), locations);
        } else {
            final CharacterSink sink = new CharacterSink(handler);
            messages = Shapeshifter.run(compiled, lines, sink, locations);
            try {
                sink.end();
            } catch (final OutputSink.StructureException e) {
                // A refusal of the document's end is reported the way the engine reports a
                // refusal mid-run: as the run's last message, after everything it had to say,
                // rather than as an exception that would carry those messages away with it
                // (design 24 phase 1 audit).
                messages = new ArrayList<>(messages);
                messages.add(new Message(stroom.shapeshifter.engine.Severity.FATAL,
                        "Output structure: " + e.getMessage()));
            }
        }
        reportAll(messages);
    }

    /**
     * A byte stream arrives with the feed's declared encoding, and the configuration declares
     * the encoding it reads bytes by. When both are known and differ, the bytes will be read
     * wrongly and nothing downstream will say why — so this does, once, as a warning: the
     * configuration is the authority, and the operator is told what it disagrees with.
     */
    private void checkEncoding(final InputSource input) throws SAXException {
        if (input.getByteStream() == null || input.getEncoding() == null) {
            return;
        }
        final String declared = compiled.project().source().encoding();
        if (declared == null || declared.equalsIgnoreCase("auto")) {
            return;
        }
        final Charset feed;
        final Charset configuration;
        try {
            feed = Charset.forName(input.getEncoding());
            configuration = Charset.forName(declared);
        } catch (final IllegalArgumentException unknownCharset) {
            return;
        }
        if (!feed.equals(configuration)) {
            report(new Message(stroom.shapeshifter.engine.Severity.WARNING,
                    "The feed is declared " + feed.name() + " but the configuration reads its bytes as "
                    + configuration.name() + " (source.encoding); the configuration is used"));
        }
    }

    void reportAll(final List<Message> messages) throws SAXException {
        for (final Message message : messages) {
            report(message);
        }
    }

    /**
     * What a run produced, before any of it is forwarded: the output, the messages and the
     * trace that maps output positions back to the input. Produced on any thread — the engine
     * needs nothing of the pipeline's — and forwarded on the pipeline's (design 22 §2).
     */
    record Run(byte[] output, List<Message> messages, InputLocations.Resolver locations) {

    }

    /** Run over a stream, in windows of the configuration's buffer size, holding the output for the parse. */
    Run runStreamed(final InputStream input) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final InputLocations locations = new InputLocations();
        final InputLocations.LineIndex lines = new InputLocations.LineIndex(input);
        final List<Message> messages = Shapeshifter.run(compiled, lines, OutputSink.of(output), locations);
        return new Run(output.toByteArray(), messages,
                locations.resolver(lines.lineStarts(), output.toByteArray()));
    }

    /** Report the run's messages, then parse and forward its output — on the pipeline's thread. */
    void forward(final Run run) throws IOException, SAXException {
        boolean fatal = false;
        for (final Message message : run.messages()) {
            report(message);
            fatal |= message.severity() == stroom.shapeshifter.engine.Severity.FATAL;
        }
        if (fatal) {
            // A run that could not read its input did not produce a document; parsing what it
            // managed to write would only add a second, misleading error to the first.
            return;
        }
        forward(run.output(), run.locations());
    }

    // -----------------------------------------------------------------------------------
    // Forwarding the output as events
    // -----------------------------------------------------------------------------------

    private void forward(final byte[] output, final InputLocations.Resolver locations)
            throws IOException, SAXException {
        final ContentHandler target = getContentHandler();
        if (target == null) {
            throw new SAXException("No content handler set");
        }
        // Every event is forwarded with the input position behind it, not the parser's position
        // in the generated text (design 21 phase 4; D10's locator).
        final ContentHandler contentHandler = new LocatingHandler(target, locations);
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

    /** A locator whose position is set by whoever forwards the events. */
    static final class LiveLocator implements Locator {

        private InputLocations.Position position = InputLocations.Position.NONE;

        void at(final InputLocations.Position position) {
            this.position = position;
        }

        @Override
        public int getLineNumber() {
            return position.line();
        }

        @Override
        public int getColumnNumber() {
            return position.column();
        }

        @Override
        public String getPublicId() {
            return null;
        }

        @Override
        public String getSystemId() {
            return null;
        }
    }

    /**
     * Forwards the event sink's events as they are emitted, each located from the innermost
     * running match at that moment — which, under the deferred start tag, is the match whose
     * emission forced the tag (design 21 phase 4's rule, the same in this currency).
     */
    static final class LiveLocatingHandler implements ContentHandler {

        private final ContentHandler target;
        private final InputLocations locations;
        private final InputLocations.Lines lines;
        private final LiveLocator locator = new LiveLocator();
        private boolean located;

        LiveLocatingHandler(final ContentHandler target, final InputLocations locations,
                            final InputLocations.Lines lines) {
            this.target = target;
            this.locations = locations;
            this.lines = lines;
        }

        private void locate() {
            if (!located) {
                target.setDocumentLocator(locator);
                located = true;
            }
            locator.at(lines.locate(locations.currentInputOffset()));
        }

        @Override
        public void setDocumentLocator(final Locator ignored) {
        }

        @Override
        public void startDocument() throws SAXException {
            locate();
            target.startDocument();
        }

        @Override
        public void endDocument() throws SAXException {
            locate();
            target.endDocument();
        }

        @Override
        public void startPrefixMapping(final String prefix, final String uri) throws SAXException {
            locate();
            target.startPrefixMapping(prefix, uri);
        }

        @Override
        public void endPrefixMapping(final String prefix) throws SAXException {
            locate();
            target.endPrefixMapping(prefix);
        }

        @Override
        public void startElement(final String uri, final String localName, final String qName,
                                 final org.xml.sax.Attributes atts) throws SAXException {
            locate();
            target.startElement(uri, localName, qName, atts);
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) throws SAXException {
            locate();
            target.endElement(uri, localName, qName);
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) throws SAXException {
            locate();
            target.characters(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(final char[] ch, final int start, final int length) throws SAXException {
            locate();
            target.ignorableWhitespace(ch, start, length);
        }

        @Override
        public void processingInstruction(final String piTarget, final String data) throws SAXException {
            locate();
            target.processingInstruction(piTarget, data);
        }

        @Override
        public void skippedEntity(final String name) throws SAXException {
            locate();
            target.skippedEntity(name);
        }
    }

    /**
     * Forwards events with the document locator replaced by the input's: before each event the
     * parser's position in the output is resolved to the input position behind it, and that is
     * what the pipeline's filters read.
     */
    private static final class LocatingHandler implements ContentHandler {

        private final ContentHandler target;
        private final InputLocations.Resolver locations;
        private Locator parser;

        private LocatingHandler(final ContentHandler target, final InputLocations.Resolver locations) {
            this.target = target;
            this.locations = locations;
        }

        private void locate() {
            if (parser != null) {
                locations.at(parser);
            }
        }

        @Override
        public void setDocumentLocator(final Locator locator) {
            parser = locator;
            target.setDocumentLocator(locations);
        }

        @Override
        public void startDocument() throws SAXException {
            locate();
            target.startDocument();
        }

        @Override
        public void endDocument() throws SAXException {
            locate();
            target.endDocument();
        }

        @Override
        public void startPrefixMapping(final String prefix, final String uri) throws SAXException {
            locate();
            target.startPrefixMapping(prefix, uri);
        }

        @Override
        public void endPrefixMapping(final String prefix) throws SAXException {
            locate();
            target.endPrefixMapping(prefix);
        }

        @Override
        public void startElement(final String uri, final String localName, final String qName,
                                 final org.xml.sax.Attributes atts) throws SAXException {
            locate();
            target.startElement(uri, localName, qName, atts);
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) throws SAXException {
            locate();
            target.endElement(uri, localName, qName);
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) throws SAXException {
            locate();
            target.characters(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(final char[] ch, final int start, final int length) throws SAXException {
            locate();
            target.ignorableWhitespace(ch, start, length);
        }

        @Override
        public void processingInstruction(final String piTarget, final String data) throws SAXException {
            locate();
            target.processingInstruction(piTarget, data);
        }

        @Override
        public void skippedEntity(final String name) throws SAXException {
            locate();
            target.skippedEntity(name);
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

    /**
     * The input as a stream of bytes. A byte stream is the feed itself, with the configuration's
     * own {@code source.encoding} to read it by; a character stream was decoded upstream by a
     * reader element and is encoded to UTF-8 as it is read, never held.
     */
    private static InputStream streamOf(final InputSource input) throws SAXException {
        final InputStream byteStream = input.getByteStream();
        if (byteStream != null) {
            return byteStream;
        }
        final Reader characterStream = input.getCharacterStream();
        if (characterStream != null) {
            return new ReaderBytes(characterStream);
        }
        throw new SAXException("The input source carries neither bytes nor characters");
    }

    /** A reader as a UTF-8 byte stream, encoded a chunk at a time. */
    static final class ReaderBytes extends InputStream {

        private final Reader reader;
        private final char[] chars = new char[4096];
        private byte[] bytes = new byte[0];
        private int at;

        ReaderBytes(final Reader reader) {
            this.reader = reader;
        }

        @Override
        public int read() throws IOException {
            if (at == bytes.length && !fill()) {
                return -1;
            }
            return bytes[at++] & 0xFF;
        }

        @Override
        public int read(final byte[] into, final int offset, final int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (at == bytes.length && !fill()) {
                return -1;
            }
            final int n = Math.min(length, bytes.length - at);
            System.arraycopy(bytes, at, into, offset, n);
            at += n;
            return n;
        }

        private char held;
        private boolean holding;

        private boolean fill() throws IOException {
            int from = 0;
            if (holding) {
                // A high surrogate ended the last chunk; its pair is the first thing in this one.
                chars[0] = held;
                from = 1;
                holding = false;
            }
            int n;
            do {
                n = reader.read(chars, from, chars.length - from);
            } while (n == 0);
            int length = from + Math.max(n, 0);
            if (n < 0 && length == 0) {
                return false;
            }
            if (n >= 0 && length > 0 && Character.isHighSurrogate(chars[length - 1])) {
                held = chars[length - 1];
                holding = true;
                length--;
            }
            bytes = new String(chars, 0, length).getBytes(StandardCharsets.UTF_8);
            at = 0;
            return bytes.length > 0 || fill();
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private static Locator unlocated() {
        final LocatorImpl locator = new LocatorImpl();
        locator.setLineNumber(-1);
        locator.setColumnNumber(-1);
        return locator;
    }
}
