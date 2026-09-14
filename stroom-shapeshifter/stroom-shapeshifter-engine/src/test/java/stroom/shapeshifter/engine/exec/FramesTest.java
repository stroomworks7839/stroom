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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The execution frames, and the scoping the registry used to give them by shadowing names.
 *
 * <p>The golden fixtures cover what a configuration can see; what they cannot state is the rule
 * underneath it. Three things here are load-bearing and were previously implied by which names
 * a push happened to shadow: an unopened frame reads as <b>absent</b>, an index-only push
 * <b>inherits</b> the enclosing walk's position and last, and the match frame is <b>not</b> a
 * stack — a nested level overwrites its parent's count and does not restore it.
 */
class FramesTest {

    private static Long number(final Frames frames, final EngineVars var) {
        final TypedValue value = frames.value(var);
        return value == null ? null : value.asInteger();
    }

    @Test
    void nothingOpenReadsAsAbsent() {
        final Frames frames = new Frames();

        assertThat(frames.value(EngineVars.MATCH_COUNT)).isNull();
        assertThat(frames.value(EngineVars.MATCH_INDEX)).isNull();
        assertThat(frames.value(EngineVars.INDEX)).isNull();
        assertThat(frames.value(EngineVars.POSITION)).isNull();
        assertThat(frames.value(EngineVars.LAST)).isNull();
        assertThat(frames.value(EngineVars.GROUP_KEY)).isNull();
        assertThat(frames.value(EngineVars.GROUP_SIZE)).isNull();
    }

    @Test
    void oneCountAnswersBothNames() {
        final Frames frames = new Frames();
        frames.match(3);

        assertThat(number(frames, EngineVars.MATCH_COUNT)).isEqualTo(3L);
        assertThat(number(frames, EngineVars.MATCH_INDEX)).isEqualTo(2L);
    }

    @Test
    void nestedLevelDoesNotRestoreTheCount() {
        final Frames frames = new Frames();
        frames.match(4);
        frames.match(1);

        // The behaviour the single global cell had: nothing pops a match frame, because a
        // level's count outlives the level that set it.
        assertThat(number(frames, EngineVars.MATCH_COUNT)).isEqualTo(1L);
    }

    @Test
    void anIterationIsAbsentAgainAfterItCloses() {
        final Frames frames = new Frames();
        frames.pushIteration();
        frames.last(5);
        frames.index(2);
        frames.position(1);

        assertThat(number(frames, EngineVars.INDEX)).isEqualTo(2L);
        assertThat(number(frames, EngineVars.POSITION)).isEqualTo(1L);
        assertThat(number(frames, EngineVars.LAST)).isEqualTo(5L);

        frames.popIteration();
        assertThat(frames.value(EngineVars.INDEX)).isNull();
        assertThat(frames.value(EngineVars.POSITION)).isNull();
        assertThat(frames.value(EngineVars.LAST)).isNull();
    }

    @Test
    void anIndexOnlyPushInheritsPositionAndLast() {
        final Frames frames = new Frames();
        frames.pushIteration();
        frames.last(9);
        frames.index(4);
        frames.position(2);

        // What a grouping's filing walk and an ordering's key evaluation do: they bind the
        // index and nothing else, so the enclosing walk's position stays readable.
        frames.pushIteration();
        assertThat(frames.value(EngineVars.INDEX)).isNull();
        frames.index(7);

        assertThat(number(frames, EngineVars.INDEX)).isEqualTo(7L);
        assertThat(number(frames, EngineVars.POSITION)).isEqualTo(2L);
        assertThat(number(frames, EngineVars.LAST)).isEqualTo(9L);

        frames.popIteration();
        assertThat(number(frames, EngineVars.INDEX)).isEqualTo(4L);
    }

    @Test
    void anInnerWalkShadowsTheOneAroundIt() {
        final Frames frames = new Frames();
        frames.pushIteration();
        frames.last(3);
        frames.index(0);
        frames.position(1);

        frames.pushIteration();
        frames.last(8);
        frames.index(5);
        frames.position(6);
        assertThat(number(frames, EngineVars.POSITION)).isEqualTo(6L);

        frames.popIteration();
        assertThat(number(frames, EngineVars.INDEX)).isEqualTo(0L);
        assertThat(number(frames, EngineVars.POSITION)).isEqualTo(1L);
        assertThat(number(frames, EngineVars.LAST)).isEqualTo(3L);
    }

    @Test
    void anIterationOutsideAnyOtherInheritsNothing() {
        final Frames frames = new Frames();
        frames.pushIteration();
        frames.index(1);

        assertThat(frames.value(EngineVars.POSITION)).isNull();
        assertThat(frames.value(EngineVars.LAST)).isNull();
    }

    @Test
    void groupsNest() {
        final Frames frames = new Frames();
        frames.pushGroup();
        frames.groupKey(TypedValue.of("outer"));
        frames.groupSize(2);

        frames.pushGroup();
        frames.groupKey(TypedValue.of("inner"));
        frames.groupSize(7);
        assertThat(frames.value(EngineVars.GROUP_KEY).asString()).isEqualTo("inner");
        assertThat(number(frames, EngineVars.GROUP_SIZE)).isEqualTo(7L);

        frames.popGroup();
        assertThat(frames.value(EngineVars.GROUP_KEY).asString()).isEqualTo("outer");
        assertThat(number(frames, EngineVars.GROUP_SIZE)).isEqualTo(2L);

        frames.popGroup();
        assertThat(frames.value(EngineVars.GROUP_KEY)).isNull();
        assertThat(frames.value(EngineVars.GROUP_SIZE)).isNull();
    }

    @Test
    void groupWithNoKeyReadsAsAbsent() {
        final Frames frames = new Frames();
        frames.pushGroup();
        frames.groupKey(null);
        frames.groupSize(1);

        // The entries that grouped on nothing: the key is absent, the group is not.
        assertThat(frames.value(EngineVars.GROUP_KEY)).isNull();
        assertThat(number(frames, EngineVars.GROUP_SIZE)).isEqualTo(1L);
    }

    @Test
    void theMembersAreAFrameField() {
        // The group's members are a list the frame holds, like its key and size: absent outside
        // any group, the list inside one, and gone again when the group closes.
        final Frames frames = new Frames();
        assertThat(frames.value(EngineVars.GROUP)).isNull();
        frames.pushGroup();
        assertThat(frames.value(EngineVars.GROUP)).isNull();
        final TypedValue.List members = new TypedValue.List();
        members.append(new TypedValue.Integer(2));
        members.append(new TypedValue.Integer(5));
        frames.groupMembers(members);
        assertThat(frames.value(EngineVars.GROUP)).isSameAs(members);
        frames.popGroup();
        assertThat(frames.value(EngineVars.GROUP)).isNull();
    }
}
