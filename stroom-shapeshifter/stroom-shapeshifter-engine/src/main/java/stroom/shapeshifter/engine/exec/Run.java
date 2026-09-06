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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.Instrument;
import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.compile.CompiledMatch;
import stroom.shapeshifter.engine.compile.CompiledOp;
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.compile.CompiledTemplate;
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.function.RunMode;
import stroom.shapeshifter.engine.function.Services;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.text.RegexEncodings;
import stroom.shapeshifter.engine.text.Transcode;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One run of a compiled configuration over one input.
 *
 * <p>Owns what a run is: the graph, the sink, the messages, the function runtime, the body
 * interpreter and the level dispatcher wired to each other, and the encoding in force. It
 * settles the byte-order mark, splits the document template's body around its
 * {@code apply-templates} — the prologue written once at the start, the tail once at the end,
 * the loop between them handing the input to the level, window by window or chunk by chunk —
 * and turns an abort into the run's last message.
 */
public final class Run {

    private final CompiledProject compiled;
    private final OutputSink output;
    private final List<Message> messages = new ArrayList<>();
    /** The functions bound to this run, and what they may reach (design 26). */
    private final FunctionRuntime functions;

    /** The body interpreter, and the dispatcher of one level against one region, wired to each other. */
    private final Body body;
    private final Level level;
    /**
     * The encoding in force: what the configuration declared, or UTF-8 once a UTF-8 byte-order
     * mark has confirmed it. A mark naming anything else refuses the run ({@link #applyMark}).
     */
    private Encoding encoding;

    private Run(final CompiledProject compiled,
                     final OutputSink sink,
                     final Instrument instrument,
                     final RunMode mode,
                     final Services services) {
        this.compiled = compiled;
        this.output = sink;
        this.encoding = compiled.encoding();
        this.messages.addAll(compiled.warnings());
        this.functions = new FunctionRuntime(compiled.functions(), mode, services, messages);
        this.body = new Body(compiled, instrument, messages, functions, encoding);
        this.level = new Level(compiled, instrument, messages, body.vars(), functions, body);
        body.attach(level);
    }

    /**
     * Run a compiled configuration over a stream, through the window: memory is bounded by the
     * configuration's buffer size, and a single match must fit within it (E13, design 23).
     *
     * @param mode     normal, or a preview, which does not call impure functions (design 26 §4)
     * @param services what the functions bound to this run may reach
     * @return everything the engine had to say, in the order it said it
     */
    public static List<Message> stream(final CompiledProject compiled,
                                       final InputStream input,
                                       final OutputSink sink,
                                       final Instrument instrument,
                                       final RunMode mode,
                                       final Services services) {
        return new Run(compiled, sink, instrument, mode, services).execute(transcoded(compiled, input), false);
    }

    /**
     * Run a compiled configuration over an input held whole. The progressive matches need it —
     * an absolute seek is meaningless over a window — and a whole buffer has no edge for a
     * match to run into.
     *
     * @return everything the engine had to say, in the order it said it
     */
    public static List<Message> whole(final CompiledProject compiled,
                                      final InputStream input,
                                      final OutputSink sink,
                                      final Instrument instrument,
                                      final RunMode mode,
                                      final Services services) {
        return new Run(compiled, sink, instrument, mode, services).execute(transcoded(compiled, input), true);
    }

    /**
     * A transcode-family source becomes UTF-8 bytes before the window machinery reads it (design
     * 19 phase 6): reported by default, replaced under {@code ignore_errors}.
     */
    private static InputStream transcoded(final CompiledProject compiled, final InputStream input) {
        return compiled.transcodeFrom() != null
                ? Transcode.wrap(input, compiled.transcodeFrom().charset(), compiled.project().source().ignoreErrors())
                : input;
    }

    private List<Message> execute(final InputStream input, final boolean wholeBuffer) {
        try {
            functions.bind();
            run(input, wholeBuffer);
        } catch (final AbortRun ignored) {
            // The fatal message is the last thing the run has to say.
        } catch (final UncheckedIOException e) {
            // The input failed mid-read. Everything already said still stands, so the failure
            // joins the messages rather than throwing them away.
            messages.add(new Message(Severity.FATAL, "Reading the input failed: " + e.getCause()));
        } catch (final OutputSink.StructureException e) {
            // A write the sink could not place — text at document level on the event sink is
            // the case — rather than a structural call, which structure() has already named.
            // The same rule as above: a misplaced write is the run's last message, not an
            // exception through the caller.
            messages.add(new Message(Severity.FATAL, "Output structure: " + e.getMessage()));
        }
        return List.copyOf(messages);
    }

    private void run(final InputStream input, final boolean wholeBuffer) {
        final CompiledTemplate source = compiled.templates().stream()
                .filter(t -> t.match() instanceof CompiledMatch.Source)
                .findFirst()
                .orElse(null);

        // The document template's body is split at its apply-templates: what comes before is
        // written once at the start, what comes after once at the end, and the apply-templates
        // itself is the loop over the input. Everything the loop dispatches to is the templates
        // of the mode it names.
        final ApplyDirective streamDirective = source == null ? null : applyDirective(source.template());
        final String streamMode = streamDirective == null ? null : streamDirective.mode();
        final Dispatch rootDispatch = Dispatch.effective(
                streamDirective == null ? null : streamDirective.dispatch(), compiled.project());
        final List<CompiledTemplate> roots = compiled.templates().stream()
                .filter(t -> !(t.match() instanceof CompiledMatch.Source))
                .filter(t -> Objects.equals(t.template().mode(), streamMode))
                .toList();

        // The root level's gate is the configuration's own ignoreErrors — DS3's flag on the
        // dataSplitter element itself — or the document template's directive saying so.
        final boolean rootIgnoreErrors = compiled.project().source().ignoreErrors()
                || (streamDirective != null && streamDirective.ignoreErrors());

        final MatchResult nothing = MatchResult.empty();
        final RootSplit split = source == null ? RootSplit.NONE : RootSplit.of(source.body());

        body.registerCaptures();

        // What comes before the apply-templates, with any element it sits inside opened on the
        // way down (design 21 phase 2b: `element records { apply-templates }` is the shape the
        // migration takes, and the sink's deferred start tag is what makes opening-then-looping
        // serialise as if the body had run in one piece).
        for (int i = 0; i < split.prologues.size(); i++) {
            body.body(split.prologues.get(i), nothing, 0, new byte[0], output, 0L, rootIgnoreErrors, 0, encoding);
            if (i < split.opened.size()) {
                final CompiledOp.Element element = split.opened.get(i);
                body.structure(() -> output.startElement(element.name(), element.namespace(), element.omitIfEmpty()),
                        "element '" + element.name() + "'");
            }
        }

        final int bufferSize = wholeBuffer
                ? Integer.MAX_VALUE
                : Math.max(1, compiled.project().source().bufferSize());
        body.chunkedRoot(!wholeBuffer && (rootDispatch == Dispatch.CLASSIFY || rootDispatch == Dispatch.ANY));
        if (wholeBuffer || rootDispatch == Dispatch.CLASSIFY || rootDispatch == Dispatch.ANY) {
            // Whole-buffer inputs are addressed in one piece, and the non-consuming root
            // dispatches work window-at-a-time; neither slides.
            boolean first = true;
            long read = 0;
            for (byte[] chunk = InputWindow.read(input, bufferSize);
                 chunk != null;
                 chunk = InputWindow.read(input, bufferSize)) {
                int from = 0;
                if (first) {
                    first = false;
                    final Encoding.ByteOrderMark mark = InputWindow.byteOrderMark(chunk, chunk.length);
                    if (mark != null) {
                        applyMark(mark);
                        from = mark.length();
                        // The mark is part of the input: absolute offsets count its bytes.
                        read = from;
                    }
                }
                if (from >= chunk.length) {
                    continue;
                }
                level.dispatch(roots, chunk, from, chunk.length, output, read,
                        rootIgnoreErrors, 0, rootDispatch, encoding);
                read += chunk.length - from;
            }
        } else {
            final InputWindow window = InputWindow.open(input, bufferSize);
            if (window.mark() != null) {
                applyMark(window.mark());
            }
            level.stream(roots, window, bufferSize, output, rootIgnoreErrors, rootDispatch, encoding);
        }

        // And what comes after it, closing the opened elements on the way back up.
        for (int i = split.tails.size() - 1; i >= 0; i--) {
            body.body(split.tails.get(i), nothing, 0, new byte[0], output, 0L, rootIgnoreErrors, 0, encoding);
            if (i > 0) {
                final CompiledOp.Element element = split.opened.get(i - 1);
                body.structure(output::endElement, "element '" + element.name() + "'");
            }
        }
    }

    /**
     * What a byte-order mark at the front of the input means for the run. A UTF-8 mark is
     * skipped and confirms the encoding. A UTF-16 mark names an encoding the regex library has
     * no lowering for: such a source is transcoded whole to UTF-8 before the window ever sees
     * it (design 19 phase 6), so a mark reaching the window means the source was declared as
     * something else, and the run is refused by name rather than matching UTF-8 machines
     * against UTF-16 bytes.
     */
    private void applyMark(final Encoding.ByteOrderMark mark) {
        if (RegexEncodings.needsTranscode(mark.encoding())) {
            messages.add(new Message(Severity.FATAL, "The input begins with a " + mark.encoding().label()
                    + " byte-order mark, but the source "
                    + (encoding == Encoding.AUTO ? "declares no encoding" : "is declared " + encoding.label())
                    + ": declare " + mark.encoding().label()
                    + " on the source so the stream is transcoded whole"));
            throw new AbortRun();
        }
        encoding = mark.encoding();
        body.encoding(encoding);
    }

    /**
     * The document template's first {@code apply-templates}, looked for at the top of its body
     * and inside any {@code element} that encloses it — the same descent {@link RootSplit} makes.
     */
    private static ApplyDirective applyDirective(final Template template) {
        return applyDirective(template.body());
    }

    private static ApplyDirective applyDirective(final List<OutputNode> body) {
        for (final OutputNode node : body) {
            if (node instanceof OutputNode.ApplyTemplates apply) {
                return apply.directive();
            }
            if (node instanceof OutputNode.Element element) {
                final ApplyDirective inside = applyDirective(element.body());
                if (inside != null) {
                    return inside;
                }
            }
        }
        return null;
    }

    /**
     * The document template's body, split around its apply-templates.
     *
     * <p>Level 0 is the body itself; each element enclosing the apply adds a level. Running the
     * prologues in order with each level's element opened after its prologue, then the loop,
     * then the tails in reverse with each element closed after its tail, is the body run in one
     * piece with the loop where the apply-templates was. A body with no apply-templates is all
     * prologue.
     */
    private record RootSplit(List<List<CompiledOp>> prologues,
                             List<CompiledOp.Element> opened,
                             List<List<CompiledOp>> tails) {

        private static final RootSplit NONE = new RootSplit(List.of(), List.of(), List.of());

        static RootSplit of(final List<CompiledOp> body) {
            final List<List<CompiledOp>> prologues = new ArrayList<>();
            final List<CompiledOp.Element> opened = new ArrayList<>();
            final List<List<CompiledOp>> tails = new ArrayList<>();
            List<CompiledOp> level = body;
            while (true) {
                final int at = indexOfApplyOrEnclosingElement(level);
                if (at < 0) {
                    prologues.add(level);
                    tails.add(List.of());
                    break;
                }
                prologues.add(level.subList(0, at));
                tails.add(level.subList(at + 1, level.size()));
                if (level.get(at) instanceof CompiledOp.Element element) {
                    opened.add(element);
                    level = element.body();
                } else {
                    break;
                }
            }
            return new RootSplit(prologues, opened, tails);
        }

        private static int indexOfApplyOrEnclosingElement(final List<CompiledOp> body) {
            for (int i = 0; i < body.size(); i++) {
                final CompiledOp op = body.get(i);
                if (op instanceof CompiledOp.Apply
                    || (op instanceof CompiledOp.Element element && containsApply(element.body()))) {
                    return i;
                }
            }
            return -1;
        }

        private static boolean containsApply(final List<CompiledOp> body) {
            for (final CompiledOp op : body) {
                if (op instanceof CompiledOp.Apply
                    || (op instanceof CompiledOp.Element element && containsApply(element.body()))) {
                    return true;
                }
            }
            return false;
        }
    }
}
