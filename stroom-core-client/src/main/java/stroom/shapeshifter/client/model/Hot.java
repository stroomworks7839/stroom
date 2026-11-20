/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.client.model;

import java.util.Objects;

/// What is being pointed at (design 18 §5.5): one request the root broadcasts, and every
/// surface lights whatever it holds that the request names — a frame's spans in the content
/// and the output, its template's row, its segment in the crumb; a capture's span and its
/// variables row; an instruction's output and its card. Highlighting is a set of requests,
/// not a pile of toggles: the last request replaces the one before, and null clears.
public final class Hot {

    public enum Kind {
        FRAME,
        CAPTURE,
        INSTRUCTION,
        TEMPLATE
    }

    private final Kind kind;
    private final long frameId;
    private final int index;
    private final String templateId;
    // Which variable a capture bound, derived from its frame and index, so not part of equality
    private final String variable;

    private Hot(final Kind kind, final long frameId, final int index, final String templateId,
                final String variable) {
        this.kind = kind;
        this.frameId = frameId;
        this.index = index;
        this.templateId = templateId;
        this.variable = variable;
    }

    public static Hot frame(final long frameId, final String templateId) {
        return new Hot(Kind.FRAME, frameId, -1, templateId, null);
    }

    /// A capture, saying which variable it bound: pointing at one lights every capture of that
    /// variable on screen (ruled 2026-11-20), as a colour belongs to a variable rather than to one
    /// binding of it.
    ///
    /// @param frameId The frame that bound it.
    /// @param index Its index in that frame's captures.
    /// @param variable The variable, as `CaptureHues.variableOf` keys it, or null to light the
    ///                 one capture alone.
    /// @return The request.
    public static Hot capture(final long frameId, final int index, final String variable) {
        return new Hot(Kind.CAPTURE, frameId, index, null, variable);
    }

    public static Hot instruction(final long frameId, final int index) {
        return new Hot(Kind.INSTRUCTION, frameId, index, null, null);
    }

    public static Hot template(final String templateId) {
        return new Hot(Kind.TEMPLATE, -1, -1, templateId, null);
    }

    public Kind getKind() {
        return kind;
    }

    public long getFrameId() {
        return frameId;
    }

    public int getIndex() {
        return index;
    }

    public String getTemplateId() {
        return templateId;
    }

    /// The variable a capture bound, or null. Not part of equality: it follows from the frame
    /// and the index, and a request without it names the same capture.
    ///
    /// @return The variable's key.
    public String getVariable() {
        return variable;
    }

    /// The value a marked element carries for this request in the attribute it is matched by.
    public String key() {
        switch (kind) {
            case FRAME:
                return String.valueOf(frameId);
            case CAPTURE:
            case INSTRUCTION:
                return frameId + ":" + index;
            default:
                return templateId;
        }
    }

    @Override
    public boolean equals(final Object o) {
        if (!(o instanceof Hot)) {
            return false;
        }
        final Hot other = (Hot) o;
        return kind == other.kind && frameId == other.frameId && index == other.index
               && Objects.equals(templateId, other.templateId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, frameId, index, templateId);
    }
}
