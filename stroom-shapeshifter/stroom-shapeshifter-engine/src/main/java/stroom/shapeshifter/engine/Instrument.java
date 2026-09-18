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

package stroom.shapeshifter.engine;

import stroom.shapeshifter.engine.value.TypedValue;


/**
 * A way to watch a run without changing it.
 *
 * <p>An editor needs to show which template matched which bytes and what each capture held; a
 * production run needs none of that and should not pay for it. So the engine reports what it is
 * doing through this interface, and {@link #NONE} — whose methods are empty — is what it reports
 * to when nobody is listening. A single implementation at a call site is one the JIT can inline
 * away entirely, so the production cost is the branch that never happens.
 *
 * <p>Every method has a default that does nothing, so an implementation can answer only the
 * questions it cares about.
 *
 * <p>Input offsets are byte counts from the start of the input; output offsets are in the
 * sink's currency — bytes or events, as {@link OutputSink#unit()} says (design 20 §5). An
 * offset into content that came from a variable rather than from the input
 * cannot be meaningful, and the engine says so by reporting {@link #UNLOCATABLE} rather than a
 * plausible-looking number.
 */
public interface Instrument {

    /** An instrument that does nothing, and can be optimised away for doing it. */
    Instrument NONE = new Instrument() {

    };

    /**
     * The offset reported when content did not come from the input.
     *
     * <p>Content handed to a template by a variable has no position in the original bytes. Any
     * number would be a lie, so this is a number that cannot be one. Half of
     * {@code Long.MAX_VALUE} rather than the maximum, so that offset arithmetic on top of it
     * cannot overflow before a {@code >= UNLOCATABLE} guard catches it.
     */
    long UNLOCATABLE = Long.MAX_VALUE / 2;

    /** The document itself: the frame every match at depth zero descends from. */
    long ROOT_FRAME = 0;

    /**
     * Reported as a content offset when a frame's content is not a slice of its parent's — it
     * came from a variable, decoded or built — and its bytes follow in {@link #onMatchContent}.
     */
    int NOT_A_SLICE = -1;

    /**
     * A template matched: a frame opened (design 18 §5.2).
     *
     * <p>Frames are numbered from one in the order they open, and every frame names its parent —
     * the frame whose body dispatched it, or {@link #ROOT_FRAME} for a match of the document
     * itself — so that a listener never has to reconstruct the tree from event order (design 18
     * §7 G1). The events do come in execution order all the same, and they bracket: this
     * frame's {@link #onCapture}s, its children's whole sub-trees and the attempts made while
     * its body ran all arrive before its {@link #onOutput}.
     *
     * <p>The frame's <i>content</i> — what its body works on and its children match within — is
     * given twice over (G2): where it sits in the input, and where it sits in the parent frame's
     * content when it is byte-for-byte a slice of it, so that a listener can point at it in an
     * ancestor rather than hold it again. When it is not a slice — the parent dispatched a
     * variable's value — {@code contentOffset} is {@link #NOT_A_SLICE} and the bytes follow in
     * {@link #onMatchContent}.
     *
     * @param frameId       this frame, numbered from one across the run
     * @param parentFrameId the frame whose body dispatched this one, or {@link #ROOT_FRAME}
     * @param templateId    which template
     * @param templateName  its name, for display
     * @param inputOffset   where the match begins in the input, or {@link #UNLOCATABLE}
     * @param inputLength   the match's length in bytes, from where it begins to where its
     *                      consumption ends — so offset and length describe the same span
     * @param contentOffset where the frame's content begins in the parent frame's content, or
     *                      {@link #NOT_A_SLICE}
     * @param contentLength the content's length in bytes
     * @param matchIndex    which match this is for that template, counting from one
     * @param depth         how deep in the dispatch this happened
     */
    default void onMatch(final long frameId,
                         final long parentFrameId,
                         final String templateId,
                         final String templateName,
                         final long inputOffset,
                         final int inputLength,
                         final int contentOffset,
                         final int contentLength,
                         final int matchIndex,
                         final int depth) {
    }

    /**
     * A capture was bound in a frame.
     *
     * @param frameId    the frame it was bound in
     * @param templateId which template bound it
     * @param name       the variable's name
     * @param value      its value as bound: captured bytes as read, in the encoding class they
     *                   were read under (design 25; the UTF-8-compatible three report
     *                   {@code utf-8}, E43), a typed value a cast produced, or the kind the
     *                   binding declared (§9); a binding that bound nothing is not reported
     * @param matchIndex which match it belongs to
     * @param contentOffset where the captured bytes lie in the frame's content, or
     *                      {@link #NOT_A_SLICE}: a capture read from a variable, a key-value
     *                      pair, or a group the frame's content does not hold as a slice (the
     *                      streaming root copies its groups, so its captures are not placed;
     *                      a whole-buffer run that is watched slices, so they are)
     * @param contentLength the captured bytes' length, when placed
     */
    default void onCapture(final long frameId,
                           final String templateId,
                           final String name,
                           final TypedValue value,
                           final int matchIndex,
                           final int contentOffset,
                           final int contentLength) {
    }

    /**
     * A guard's verdict, as a level begins in a frame: every guarded template of the level is
     * reported once, held or refused, before any attempt is made (design 27 ruling 11 - guards
     * read once on the way in, so a template refused here is refused for the whole level).
     * The editor's strip shows it beside the guard (design 18 §5.6).
     *
     * @param parentFrameId the frame whose body is dispatching; {@link #ROOT_FRAME} at the root
     * @param templateId    the guarded template
     * @param allowed       whether the guard held
     */
    default void onGuard(final long parentFrameId, final String templateId, final boolean allowed) {
    }

    /**
     * What one instruction of a frame's body wrote: the top-level instruction at {@code index}
     * in the template's body, and the output it produced, in the sink's unit - the finer
     * attribution the editor's output pane colours by (design 18 §5.5: the body card that wrote
     * it). Only a watched run pays for it: the body interpreter's hot loop is untouched, and
     * a watched frame runs its instructions one at a time instead.
     */
    default void onInstruction(final long frameId,
                               final int index,
                               final long outputOffset,
                               final long outputLength,
                               final OutputSink.Unit unit) {
    }

    /**
     * A frame's content, when it is not a slice of its parent's: it came from a variable rather
     * than the input, so nothing could find it by offset (G2). Follows the frame's
     * {@link #onMatch}.
     */
    default void onMatchContent(final long frameId, final byte[] content) {
    }

    /**
     * About to try a template. The value returned is handed back to
     * {@link #stopTiming}, and zero means "not timing" — which is what {@link #NONE} returns, so
     * a production run never reads the clock at all.
     */
    default long startTiming() {
        return 0;
    }

    /**
     * Finished trying a template, whether or not it matched.
     *
     * <p>Attempts that <i>fail</i> are reported too, and that is the point: a template that never
     * matches but is tried at every position is exactly the thing worth finding — and reported
     * with where it was tried (design 18 §7 G3), so that "why didn't my template fire
     * <i>here</i>?" is answered by pointing at the place.
     *
     * @param parentFrameId the frame whose body was dispatching, or {@link #ROOT_FRAME}
     * @param templateId    which template was tried
     * @param token         whatever {@link #startTiming} returned
     * @param matched       whether the attempt succeeded
     * @param inputOffset   where in the input it was tried, or {@link #UNLOCATABLE}
     * @param contentOffset where in the parent frame's content it was tried, or
     *                      {@link #NOT_A_SLICE} when that content is not a slice of the input
     */
    default void stopTiming(final long parentFrameId,
                            final String templateId,
                            final long token,
                            final boolean matched,
                            final long inputOffset,
                            final int contentOffset) {
    }

    /**
     * A match's body finished writing: the frame closed.
     *
     * <p>The span is measured on the sink's {@link OutputSink#position()} before and after the
     * body, in the sink's own currency — byte offsets for a byte sink, event ordinals for an
     * event sink — and {@code unit} says which (design 20 §5). Spans nest: a parent's span
     * covers its children's. One consequence of the deferred start tag (design 21 phase 2a):
     * an enclosing element opened by a parent is written when the first child emits, so the
     * first child's span begins with the parent's start tag or start event. That is where the
     * bytes went, and the parent's span covers them too.
     *
     * @param frameId      the frame that closed
     * @param templateId   which template
     * @param matchIndex   which match
     * @param outputOffset where its output starts
     * @param outputLength how much it wrote
     * @param unit         what offset and length count
     */
    default void onOutput(final long frameId,
                          final String templateId,
                          final int matchIndex,
                          final long outputOffset,
                          final long outputLength,
                          final OutputSink.Unit unit) {
    }
}
