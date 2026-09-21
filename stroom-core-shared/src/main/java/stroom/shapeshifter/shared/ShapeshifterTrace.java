/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * What one run did (design 18 §5, 43 §5): the frames as a tree by explicit parent ids, every
 * capture with its type, every output span in the sink's currency, the attempts with where they
 * were tried, per-template timing always, the messages, the input and the output. The same shape
 * in the document mount and the stepping mount.
 *
 * <p>Every offset and length is in <b>characters</b> of the text it is an offset into - the
 * input, a frame's content, the output - converted on the server from the engine's bytes; an
 * output span in {@code EVENTS} counts event ordinals, as its unit says.
 */
@JsonInclude(Include.NON_NULL)
public class ShapeshifterTrace {

    /** The content offset of a frame that is not a slice of its parent's; its bytes are in {@code content}. */
    public static final int NOT_A_SLICE = -1;

    @JsonProperty
    private final boolean compiled;
    @JsonProperty
    private final String input;
    @JsonProperty
    private final String output;
    @JsonProperty
    private final List<Frame> frames;
    @JsonProperty
    private final List<Capture> captures;
    @JsonProperty
    private final List<Group> groups;
    @JsonProperty
    private final List<OutputSpan> outputs;
    @JsonProperty
    private final List<Attempt> attempts;
    @JsonProperty
    private final List<Guard> guards;
    @JsonProperty
    private final List<Instruction> instructions;
    @JsonProperty
    private final long attemptsSeen;
    @JsonProperty
    private final List<Timing> timings;
    @JsonProperty
    private final List<ShapeshifterMessage> messages;
    @JsonProperty
    private final long runNanos;

    @JsonCreator
    public ShapeshifterTrace(@JsonProperty("compiled") final boolean compiled,
                             @JsonProperty("input") final String input,
                             @JsonProperty("output") final String output,
                             @JsonProperty("frames") final List<Frame> frames,
                             @JsonProperty("captures") final List<Capture> captures,
                             @JsonProperty("groups") final List<Group> groups,
                             @JsonProperty("outputs") final List<OutputSpan> outputs,
                             @JsonProperty("attempts") final List<Attempt> attempts,
                             @JsonProperty("guards") final List<Guard> guards,
                             @JsonProperty("instructions") final List<Instruction> instructions,
                             @JsonProperty("attemptsSeen") final long attemptsSeen,
                             @JsonProperty("timings") final List<Timing> timings,
                             @JsonProperty("messages") final List<ShapeshifterMessage> messages,
                             @JsonProperty("runNanos") final long runNanos) {
        this.compiled = compiled;
        this.input = input;
        this.output = output;
        this.frames = frames;
        this.captures = captures;
        this.groups = groups;
        this.outputs = outputs;
        this.attempts = attempts;
        this.guards = guards;
        this.instructions = instructions;
        this.attemptsSeen = attemptsSeen;
        this.timings = timings;
        this.messages = messages;
        this.runNanos = runNanos;
    }

    /** Whether the project compiled; when it did not, only the messages are meaningful. */
    public boolean isCompiled() {
        return compiled;
    }

    public String getInput() {
        return input;
    }

    public String getOutput() {
        return output;
    }

    public List<Frame> getFrames() {
        return frames;
    }

    public List<Capture> getCaptures() {
        return captures;
    }

    public List<Group> getGroups() {
        return groups;
    }

    public List<OutputSpan> getOutputs() {
        return outputs;
    }

    public List<Attempt> getAttempts() {
        return attempts;
    }

    public List<Guard> getGuards() {
        return guards;
    }

    public List<Instruction> getInstructions() {
        return instructions;
    }

    /** How many attempts there were; the list holds up to the recorder's cap. */
    public long getAttemptsSeen() {
        return attemptsSeen;
    }

    public List<Timing> getTimings() {
        return timings;
    }

    public List<ShapeshifterMessage> getMessages() {
        return messages;
    }

    public long getRunNanos() {
        return runNanos;
    }

    /** A match: a frame, its parent by id (0 is the document), and where its content is. */
    @JsonInclude(Include.NON_NULL)
    public static class Frame {

        @JsonProperty
        private final long id;
        @JsonProperty
        private final long parentId;
        @JsonProperty
        private final String templateId;
        @JsonProperty
        private final String templateName;
        @JsonProperty
        private final int matchIndex;
        @JsonProperty
        private final int depth;
        /** Where the match begins in the input, in characters, or -1 when it has no place there. */
        @JsonProperty
        private final long inputOffset;
        @JsonProperty
        private final int inputLength;
        /** Where the frame's content begins in its parent's content, or {@link #NOT_A_SLICE}. */
        @JsonProperty
        private final int contentOffset;
        @JsonProperty
        private final int contentLength;
        /** The content itself, only when it is not a slice of the parent's. */
        @JsonProperty
        private final String content;

        @JsonCreator
        public Frame(@JsonProperty("id") final long id,
                     @JsonProperty("parentId") final long parentId,
                     @JsonProperty("templateId") final String templateId,
                     @JsonProperty("templateName") final String templateName,
                     @JsonProperty("matchIndex") final int matchIndex,
                     @JsonProperty("depth") final int depth,
                     @JsonProperty("inputOffset") final long inputOffset,
                     @JsonProperty("inputLength") final int inputLength,
                     @JsonProperty("contentOffset") final int contentOffset,
                     @JsonProperty("contentLength") final int contentLength,
                     @JsonProperty("content") final String content) {
            this.id = id;
            this.parentId = parentId;
            this.templateId = templateId;
            this.templateName = templateName;
            this.matchIndex = matchIndex;
            this.depth = depth;
            this.inputOffset = inputOffset;
            this.inputLength = inputLength;
            this.contentOffset = contentOffset;
            this.contentLength = contentLength;
            this.content = content;
        }

        public long getId() {
            return id;
        }

        public long getParentId() {
            return parentId;
        }

        public String getTemplateId() {
            return templateId;
        }

        public String getTemplateName() {
            return templateName;
        }

        public int getMatchIndex() {
            return matchIndex;
        }

        public int getDepth() {
            return depth;
        }

        public long getInputOffset() {
            return inputOffset;
        }

        public int getInputLength() {
            return inputLength;
        }

        public int getContentOffset() {
            return contentOffset;
        }

        public int getContentLength() {
            return contentLength;
        }

        public String getContent() {
            return content;
        }
    }

    /**
     * A capture as bound in a frame, with where its bytes lie in the frame's content when they
     * are a slice of it - the tint the content pane paints (design 18 §5.3) - or
     * {@link #NOT_A_SLICE} for a value read from a variable or a key-value pair.
     */
    @JsonInclude(Include.NON_NULL)
    public static class Capture {

        @JsonProperty
        private final long frameId;
        @JsonProperty
        private final String name;
        @JsonProperty
        private final String value;
        @JsonProperty
        private final String type;
        @JsonProperty
        private final int contentOffset;
        @JsonProperty
        private final int contentLength;

        @JsonCreator
        public Capture(@JsonProperty("frameId") final long frameId,
                       @JsonProperty("name") final String name,
                       @JsonProperty("value") final String value,
                       @JsonProperty("type") final String type,
                       @JsonProperty("contentOffset") final int contentOffset,
                       @JsonProperty("contentLength") final int contentLength) {
            this.frameId = frameId;
            this.name = name;
            this.value = value;
            this.type = type;
            this.contentOffset = contentOffset;
            this.contentLength = contentLength;
        }

        public int getContentOffset() {
            return contentOffset;
        }

        public int getContentLength() {
            return contentLength;
        }

        public long getFrameId() {
            return frameId;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        public String getType() {
            return type;
        }
    }

    /**
     * One group of a frame's match, bound to a capture or not: its number, its name where the
     * match names it, and where it lies in the frame's content in characters, or
     * {@link #NOT_A_SLICE} for a group that did not take part or that the content does not hold.
     */
    @JsonInclude(Include.NON_NULL)
    public static class Group {

        @JsonProperty
        private final long frameId;
        @JsonProperty
        private final int index;
        @JsonProperty
        private final String name;
        @JsonProperty
        private final int contentOffset;
        @JsonProperty
        private final int contentLength;

        @JsonCreator
        public Group(@JsonProperty("frameId") final long frameId,
                     @JsonProperty("index") final int index,
                     @JsonProperty("name") final String name,
                     @JsonProperty("contentOffset") final int contentOffset,
                     @JsonProperty("contentLength") final int contentLength) {
            this.frameId = frameId;
            this.index = index;
            this.name = name;
            this.contentOffset = contentOffset;
            this.contentLength = contentLength;
        }

        public long getFrameId() {
            return frameId;
        }

        public int getIndex() {
            return index;
        }

        public String getName() {
            return name;
        }

        public int getContentOffset() {
            return contentOffset;
        }

        public int getContentLength() {
            return contentLength;
        }
    }

    /** What a frame wrote: offset and length in the sink's currency, bytes or events. */
    @JsonInclude(Include.NON_NULL)
    public static class OutputSpan {

        @JsonProperty
        private final long frameId;
        @JsonProperty
        private final long offset;
        @JsonProperty
        private final long length;
        @JsonProperty
        private final String unit;

        @JsonCreator
        public OutputSpan(@JsonProperty("frameId") final long frameId,
                          @JsonProperty("offset") final long offset,
                          @JsonProperty("length") final long length,
                          @JsonProperty("unit") final String unit) {
            this.frameId = frameId;
            this.offset = offset;
            this.length = length;
            this.unit = unit;
        }

        public long getFrameId() {
            return frameId;
        }

        public long getOffset() {
            return offset;
        }

        public long getLength() {
            return length;
        }

        public String getUnit() {
            return unit;
        }
    }

    /** A guard's verdict for a template as a frame's body began dispatching (design 18 §5.6). */
    @JsonInclude(Include.NON_NULL)
    public static class Guard {

        @JsonProperty
        private final long parentFrameId;
        @JsonProperty
        private final String templateId;
        @JsonProperty
        private final boolean allowed;

        @JsonCreator
        public Guard(@JsonProperty("parentFrameId") final long parentFrameId,
                     @JsonProperty("templateId") final String templateId,
                     @JsonProperty("allowed") final boolean allowed) {
            this.parentFrameId = parentFrameId;
            this.templateId = templateId;
            this.allowed = allowed;
        }

        public long getParentFrameId() {
            return parentFrameId;
        }

        public String getTemplateId() {
            return templateId;
        }

        public boolean isAllowed() {
            return allowed;
        }
    }

    /**
     * What one top-level instruction of a frame's body wrote: the instruction by its index in
     * the template's body, and its output in the sink's currency (design 18 §5.5).
     */
    @JsonInclude(Include.NON_NULL)
    public static class Instruction {

        @JsonProperty
        private final long frameId;
        @JsonProperty
        private final int index;
        @JsonProperty
        private final long offset;
        @JsonProperty
        private final long length;
        @JsonProperty
        private final String unit;

        @JsonCreator
        public Instruction(@JsonProperty("frameId") final long frameId,
                           @JsonProperty("index") final int index,
                           @JsonProperty("offset") final long offset,
                           @JsonProperty("length") final long length,
                           @JsonProperty("unit") final String unit) {
            this.frameId = frameId;
            this.index = index;
            this.offset = offset;
            this.length = length;
            this.unit = unit;
        }

        public long getFrameId() {
            return frameId;
        }

        public int getIndex() {
            return index;
        }

        public long getOffset() {
            return offset;
        }

        public long getLength() {
            return length;
        }

        public String getUnit() {
            return unit;
        }
    }

    /** A template tried at a place in a frame's content, and whether it matched. */
    @JsonInclude(Include.NON_NULL)
    public static class Attempt {

        @JsonProperty
        private final long parentFrameId;
        @JsonProperty
        private final String templateId;
        @JsonProperty
        private final boolean matched;
        @JsonProperty
        private final long inputOffset;
        @JsonProperty
        private final int contentOffset;
        @JsonProperty
        private final long nanos;

        @JsonCreator
        public Attempt(@JsonProperty("parentFrameId") final long parentFrameId,
                       @JsonProperty("templateId") final String templateId,
                       @JsonProperty("matched") final boolean matched,
                       @JsonProperty("inputOffset") final long inputOffset,
                       @JsonProperty("contentOffset") final int contentOffset,
                       @JsonProperty("nanos") final long nanos) {
            this.parentFrameId = parentFrameId;
            this.templateId = templateId;
            this.matched = matched;
            this.inputOffset = inputOffset;
            this.contentOffset = contentOffset;
            this.nanos = nanos;
        }

        public long getParentFrameId() {
            return parentFrameId;
        }

        public String getTemplateId() {
            return templateId;
        }

        public boolean isMatched() {
            return matched;
        }

        public long getInputOffset() {
            return inputOffset;
        }

        public int getContentOffset() {
            return contentOffset;
        }

        public long getNanos() {
            return nanos;
        }
    }

    /** A template's totals: every run profiles (design 18 §5.8). */
    @JsonInclude(Include.NON_NULL)
    public static class Timing {

        @JsonProperty
        private final String templateId;
        @JsonProperty
        private final long attempts;
        @JsonProperty
        private final long matched;
        @JsonProperty
        private final long nanos;

        @JsonCreator
        public Timing(@JsonProperty("templateId") final String templateId,
                      @JsonProperty("attempts") final long attempts,
                      @JsonProperty("matched") final long matched,
                      @JsonProperty("nanos") final long nanos) {
            this.templateId = templateId;
            this.attempts = attempts;
            this.matched = matched;
            this.nanos = nanos;
        }

        public String getTemplateId() {
            return templateId;
        }

        public long getAttempts() {
            return attempts;
        }

        public long getMatched() {
            return matched;
        }

        public long getNanos() {
            return nanos;
        }
    }
}
