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
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.function.RunMode;
import stroom.shapeshifter.engine.function.Services;
import stroom.shapeshifter.engine.graph.CompiledOp;
import stroom.shapeshifter.engine.graph.CompiledProject;
import stroom.shapeshifter.engine.graph.CompiledTemplate;
import stroom.shapeshifter.engine.graph.RootPlan;
import stroom.shapeshifter.engine.match.MatchResult;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.text.RegexEncodings;
import stroom.shapeshifter.engine.text.Transcode;
import stroom.shapeshifter.engine.value.ByteSource;
import stroom.shapeshifter.engine.value.TypedValue;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

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
    /** The run's sink paired with what it accepts, once (design 25 §3). */
    private final Output out;
    /** What a prologue or a tail works on: nothing. */
    private static final TypedValue NO_CONTENT = TypedValue.utf8(new byte[0]);

    private final List<Message> messages = new ArrayList<>();
    /** The functions bound to this run, and what they may reach (design 26). */
    private final FunctionRuntime functions;

    /** The body interpreter, which the level hands a winning match's body to. */
    private final Body body;
    /** The dispatcher of one level against one region, which the body's apply-templates hands a region to. */
    private final Level level;
    /**
     * The encoding in force: the one the graph was compiled for, settled before anything runs
     * (design 32). A byte-order mark naming a transcode-family encoding refuses the run, and one
     * naming anything else the graph was not compiled for is warned about ({@link #applyMark}).
     */
    private final Encoding encoding;

    private Run(final CompiledProject compiled,
                final OutputSink sink,
                final Instrument instrument,
                final RunMode mode,
                final Services services) {
        this.compiled = compiled;
        this.out = Output.of(sink);
        this.encoding = compiled.encoding();
        this.messages.addAll(compiled.warnings());
        this.functions = new FunctionRuntime(compiled.functions(), mode, services, messages);
        this.body = new Body(compiled, instrument, messages, functions, encoding);
        this.level = body.level();
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
        return new Run(compiled, sink, instrument, mode, services).run(transcoded(compiled, input), false);
    }

    /**
     * Run a compiled configuration over an input held whole. A match sequence's absolute seek
     * needs it — meaningless over a window — and a whole buffer has no edge for a match to run
     * into.
     *
     * @param mode     normal, or a preview, which does not call impure functions (design 26 §4)
     * @param services what the functions bound to this run may reach
     * @return everything the engine had to say, in the order it said it
     */
    public static List<Message> whole(final CompiledProject compiled,
                                      final InputStream input,
                                      final OutputSink sink,
                                      final Instrument instrument,
                                      final RunMode mode,
                                      final Services services) {
        return new Run(compiled, sink, instrument, mode, services).run(transcoded(compiled, input), true);
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

    /** Run to the end, an abort included, and return everything the engine had to say. */
    private List<Message> run(final InputStream input, final boolean wholeBuffer) {
        try {
            functions.bind();
            document(input, wholeBuffer);
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

    /** The document template: its prologue, the loop over the input where its apply-templates was,
     * its tail. */
    private void document(final InputStream input, final boolean wholeBuffer) {
        // Which template is the document, where its apply-templates sits, what the loop
        // dispatches to and under which dispatch: all settled when the project compiled, because
        // none of it depends on the input. It was re-derived per run, which is per stream in a
        // pipeline processing many (design 29 §3.5).
        final RootPlan plan = compiled.rootPlan();
        final CompiledTemplate[] roots = plan.roots();
        final Dispatch rootDispatch = plan.dispatch();
        final boolean rootIgnoreErrors = plan.ignoreErrors();

        final MatchResult nothing = MatchResult.empty();

        // The source template's execution is the run (design 35 §4): what it declares lasts
        // from before the prologues to after the tails, however the input is read.
        body.enterSource();

        // What comes before the apply-templates, with any element it sits inside opened on the
        // way down (design 21 phase 2b: `element records { apply-templates }` is the shape the
        // migration takes, and the sink's deferred start tag is what makes opening-then-looping
        // serialise as if the body had run in one piece).
        for (int i = 0; i < plan.prologues().length; i++) {
            body.body(plan.prologues()[i], nothing, 0, NO_CONTENT, out, 0L, rootIgnoreErrors,
                    0);
            if (i < plan.opened().length) {
                final CompiledOp.Element element = plan.opened()[i];
                body.structure(() -> out.sink().startElement(element.name(), element.namespace(),
                        element.omitIfEmpty()),
                        "element", element.name());
            }
        }

        dispatchInput(roots, rootDispatch, rootIgnoreErrors, input, wholeBuffer);

        // And what comes after it, closing the opened elements on the way back up.
        for (int i = plan.tails().length - 1; i >= 0; i--) {
            body.body(plan.tails()[i], nothing, 0, NO_CONTENT, out, 0L, rootIgnoreErrors,
                    0);
            if (i > 0) {
                final CompiledOp.Element element = plan.opened()[i - 1];
                body.structure(out.sink()::endElement, "element", element.name());
            }
        }
        body.leaveSource();
    }

    /**
     * Hand the input to the root level: whole-buffer inputs are addressed in one piece, and the
     * roots that do not slide — classify, which consumes nothing, and any, which excises from a
     * working copy — work chunk at a time.
     * Everything else streams through the window (E13, design 23). The byte-order mark is read
     * once at the front, whichever way, and counts toward every absolute offset.
     */
    private void dispatchInput(final CompiledTemplate[] roots,
                               final Dispatch rootDispatch,
                               final boolean rootIgnoreErrors,
                               final InputStream input,
                               final boolean wholeBuffer) {
        final int bufferSize = wholeBuffer
                ? Integer.MAX_VALUE
                : Math.max(1, compiled.project().source().bufferSize());
        final boolean chunked = rootDispatch == Dispatch.CLASSIFY || rootDispatch == Dispatch.ANY;
        body.chunkedRoot(!wholeBuffer && chunked);
        if (wholeBuffer || chunked) {
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
                // A chunk is read once and never reused, but it is the root: the root copies
                // (design 37 §5), and whether it need not is phase 3d's question.
                level.dispatch(roots, chunk, from, chunk.length, out, read,
                        rootIgnoreErrors, 0, rootDispatch, encoding, new ByteSource.Copying(chunk));
                read += chunk.length - from;
            }
        } else {
            final InputWindow window = InputWindow.open(input, bufferSize);
            if (window.mark() != null) {
                applyMark(window.mark());
            }
            level.stream(roots, window, out, rootIgnoreErrors, rootDispatch, encoding);
        }
    }

    /**
     * What a byte-order mark at the front of the input means for the run.
     *
     * <p><b>It no longer means a change of encoding</b> (design 32 phase 4). The reading was
     * settled before the configuration compiled, and every pattern, delimiter and step carries
     * it — so a mark cannot move a run without leaving most of the graph behind. It used to move
     * one match kind and not the other two, which is the defect design 32 removes rather than an
     * ability being given up.
     *
     * <p>What a mark still does is get <em>skipped</em>, which the caller does with its length,
     * and get checked. A transcode-family mark refuses the run: such a source is decoded whole to
     * UTF-8 before the window sees it (design 19 phase 6), so a mark reaching here means the
     * stream was not, and matching UTF-8 machines against UTF-16 bytes is worse than stopping.
     * A mark that merely disagrees with an explicit declaration is the author's contradiction to
     * resolve, so it is said and the declaration is kept — the run was compiled for it, and
     * saying nothing would leave a reader wondering why their mark had no effect.
     */
    private void applyMark(final Encoding.ByteOrderMark mark) {
        final String declared = compiled.project().source().encoding();
        final boolean undeclared = declared == null || Encoding.fromLabel(declared) == Encoding.AUTO;
        if (RegexEncodings.needsTranscode(mark.encoding())) {
            messages.add(new Message(Severity.FATAL, "The input begins with a " + mark.encoding().label()
                    + " byte-order mark, but the source "
                    + (undeclared ? "declares no encoding" : "is declared " + declared)
                    + ": declare " + mark.encoding().label()
                    + " on the source so the stream is transcoded whole"));
            throw new AbortRun();
        }
        if (mark.encoding() != encoding) {
            // What the graph was compiled for, which is always known — where a declaration is
            // not: this can be reached through the engine's own API, by a caller that settled
            // the encoding itself and settled it differently from the mark.
            messages.add(new Message(Severity.WARNING, "The input begins with a "
                    + mark.encoding().label() + " byte-order mark, but this configuration is"
                    + " compiled to read " + encoding.label()
                    + (undeclared ? "" : ", which the source declares")
                    + ": the mark is skipped and " + encoding.label() + " used. Settle the"
                    + " encoding against the input before compiling to follow the mark."));
        }
    }
}
