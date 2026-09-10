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

import stroom.shapeshifter.engine.config.EngineVars;
import stroom.shapeshifter.engine.value.TypedValue;

import java.util.Arrays;

/**
 * The execution context: where the engine keeps its own variables (design 30 phase 4).
 *
 * <p>{@link EngineVars} were in the {@link VarRegistry} because it was the mechanism to hand,
 * not because they are variables. They are three frames — the match, the iteration and the
 * group — and the registry was giving those frames their nesting by shadowing eight names at
 * every push. Here each frame holds its own fields, so a write is an assignment and a read is
 * a field.
 *
 * <p><b>What the counting found</b> (design 30 §5.3): on {@code ausearch} 72% and on
 * {@code element_storm} 100% of all name resolutions were engine variables, almost all of them
 * {@code __match_count} and {@code __match_idx} written per match by {@code Level} — and
 * {@code element_storm}'s configuration reads neither. That traffic is not resolving
 * references; it is a hash per match to store two numbers nobody asks for.
 *
 * <p><b>The stacks are exact, not approximate.</b> An iteration frame inherits its enclosing
 * frame's position and last at the push, because the two index-only pushes — a grouping's
 * filing walk and an ordering's key evaluation — deliberately leave those reading the
 * enclosing walk's values, which is what shadowing only {@code __index} used to say. Nothing
 * is allocated per push after the first: the frame objects are reused down the stack.
 *
 * <p><b>Values are made on the read, not on the write.</b> A frame keeps a {@code long} and
 * remembers the {@link TypedValue} it was last asked for. A configuration that never reads
 * {@code __match_count} never builds one.
 */
public final class Frames {

    /** Whether any level has counted a match yet; before that the match frame is absent. */
    private boolean matched;
    private long matchCount;
    private TypedValue matchCountValue;
    private TypedValue matchIndexValue;

    private Iteration[] iterations = new Iteration[8];
    private int iterationDepth;

    private Group[] groups = new Group[4];
    private int groupDepth;

    /**
     * Count a match, which is what {@code __match_count} and {@code __match_idx} both read.
     *
     * <p>One field for two names: the index is the count less one, so there is nothing to keep
     * consistent. The match frame is <b>not</b> a stack, which is the behaviour it replaces —
     * a nested level overwrites its parent's count and does not restore it on the way out.
     */
    public void match(final long count) {
        matched = true;
        matchCount = count;
        matchCountValue = null;
        matchIndexValue = null;
    }

    /** Open an iteration frame, inheriting the enclosing one's position and last. */
    public void pushIteration() {
        if (iterationDepth == iterations.length) {
            iterations = Arrays.copyOf(iterations, iterations.length * 2);
        }
        Iteration frame = iterations[iterationDepth];
        if (frame == null) {
            frame = new Iteration();
            iterations[iterationDepth] = frame;
        }
        frame.inherit(iterationDepth == 0 ? null : iterations[iterationDepth - 1]);
        iterationDepth++;
    }

    /** Close the innermost iteration frame. */
    public void popIteration() {
        iterationDepth--;
    }

    /** The entry this iteration is on, as a store index (design/16 §4.3). */
    public void index(final long index) {
        final Iteration frame = iterations[iterationDepth - 1];
        frame.index = index;
        frame.hasIndex = true;
        frame.indexValue = null;
    }

    /** The 1-based position this iteration is at, which follows any ordering. */
    public void position(final long position) {
        final Iteration frame = iterations[iterationDepth - 1];
        frame.position = position;
        frame.hasPosition = true;
        frame.positionValue = null;
    }

    /** How many entries the iteration will run, known before the first body does. */
    public void last(final long last) {
        final Iteration frame = iterations[iterationDepth - 1];
        frame.last = last;
        frame.hasLast = true;
        frame.lastValue = null;
    }

    /** Open a group frame. Its key and size are absent until the group sets them. */
    public void pushGroup() {
        if (groupDepth == groups.length) {
            groups = Arrays.copyOf(groups, groups.length * 2);
        }
        Group frame = groups[groupDepth];
        if (frame == null) {
            frame = new Group();
            groups[groupDepth] = frame;
        }
        frame.key = null;
        frame.hasSize = false;
        frame.sizeValue = null;
        groupDepth++;
    }

    /** Close the innermost group frame. */
    public void popGroup() {
        groupDepth--;
    }

    /** The key this group was formed on, or null for the group of entries that had none. */
    public void groupKey(final TypedValue key) {
        groups[groupDepth - 1].key = key;
    }

    /** How many members this group has. */
    public void groupSize(final long size) {
        final Group frame = groups[groupDepth - 1];
        frame.size = size;
        frame.hasSize = true;
        frame.sizeValue = null;
    }

    /**
     * What an engine variable currently reads as, or null when its frame is not open — the
     * absence that {@code $__position} outside a {@code for-each} has always had.
     */
    public TypedValue value(final EngineVars var) {
        final Iteration iteration = iterationDepth == 0 ? null : iterations[iterationDepth - 1];
        final Group group = groupDepth == 0 ? null : groups[groupDepth - 1];
        return switch (var) {
            case MATCH_COUNT -> !matched ? null : matchCountValue();
            case MATCH_INDEX -> !matched ? null : matchIndexValue();
            case INDEX -> iteration == null || !iteration.hasIndex ? null : iteration.indexValue();
            case POSITION -> iteration == null || !iteration.hasPosition
                    ? null
                    : iteration.positionValue();
            case LAST -> iteration == null || !iteration.hasLast ? null : iteration.lastValue();
            case GROUP_KEY -> group == null ? null : group.key;
            case GROUP_SIZE -> group == null || !group.hasSize ? null : group.sizeValue();
            // Nothing routes it here: a sequence is a store, and EngineVars.framed() says so.
            case GROUP -> throw new IllegalStateException("__group is a store, not a frame field");
        };
    }

    private TypedValue matchCountValue() {
        if (matchCountValue == null) {
            matchCountValue = new TypedValue.Integer(matchCount);
        }
        return matchCountValue;
    }

    private TypedValue matchIndexValue() {
        if (matchIndexValue == null) {
            matchIndexValue = new TypedValue.Integer(matchCount - 1);
        }
        return matchIndexValue;
    }

    /** One walk's frame: the entry it is on, and where that entry sits in the walk. */
    private static final class Iteration {

        private long index;
        private boolean hasIndex;
        private TypedValue indexValue;

        private long position;
        private boolean hasPosition;
        private TypedValue positionValue;

        private long last;
        private boolean hasLast;
        private TypedValue lastValue;

        /**
         * Start this frame from the one enclosing it. The index never carries — every push
         * that opens a frame binds its own — while position and last do, because the two
         * index-only pushes read the enclosing walk's.
         */
        private void inherit(final Iteration parent) {
            hasIndex = false;
            indexValue = null;
            if (parent == null) {
                hasPosition = false;
                positionValue = null;
                hasLast = false;
                lastValue = null;
            } else {
                position = parent.position;
                hasPosition = parent.hasPosition;
                positionValue = parent.positionValue;
                last = parent.last;
                hasLast = parent.hasLast;
                lastValue = parent.lastValue;
            }
        }

        private TypedValue indexValue() {
            if (indexValue == null) {
                indexValue = new TypedValue.Integer(index);
            }
            return indexValue;
        }

        private TypedValue positionValue() {
            if (positionValue == null) {
                positionValue = new TypedValue.Integer(position);
            }
            return positionValue;
        }

        private TypedValue lastValue() {
            if (lastValue == null) {
                lastValue = new TypedValue.Integer(last);
            }
            return lastValue;
        }
    }

    /** One grouping's frame. The members themselves stay a store, bound as {@code __group}. */
    private static final class Group {

        private TypedValue key;

        private long size;
        private boolean hasSize;
        private TypedValue sizeValue;

        private TypedValue sizeValue() {
            if (sizeValue == null) {
                sizeValue = new TypedValue.Integer(size);
            }
            return sizeValue;
        }
    }
}
