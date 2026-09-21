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

import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.shared.ShapeshifterLibrary;
import stroom.shapeshifter.shared.ShapeshifterMessage;
import stroom.shapeshifter.shared.ShapeshifterPatternInfo;
import stroom.shapeshifter.shared.ShapeshifterPatternRequest;
import stroom.shapeshifter.shared.ShapeshifterPreviewRequest;
import stroom.shapeshifter.shared.ShapeshifterText;
import stroom.shapeshifter.shared.ShapeshifterTrace;
import stroom.shapeshifter.shared.ShapeshifterValidation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The editor's four questions (design 43 §5), answered by the resource over the engine — with
 * no document behind them, so the resource is built by hand and the stores are never touched.
 */
class ShapeshifterResourceEndpointsTest {

    private static final String PROJECT = """
            {"name": "t", "version": 5,
             "templates": [
              {"id": "00000000-0000-0000-0000-000000000001", "name": "root", "match": "source",
               "declarations": [{"name": "who", "type": "scalar"}],
               "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "rows"}}]},
              {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "rows",
               "match": {"regex": {"pattern": "(?<who>[a-z]+)\\n"}},
               "captures": [{"name": "who", "select": {"group": 1}}],
               "body": [{"value-of": {"parts": [{"capture": {"var_id": "who", "group": 0}}]}}]}]}
            """;

    private final ShapeshifterResourceImpl resource = new ShapeshifterResourceImpl(
            null, null, () -> new StroomFunctionLibrary(Set.of()));

    @Test
    void goodProjectValidatesAndComesBackCanonical() {
        final ShapeshifterValidation validation = resource.validate(PROJECT);
        assertThat(validation.isValid()).as(validation.getMessages().toString()).isTrue();
        assertThat(validation.getCanonical()).startsWith("{\n  \"name\": \"t\",");
        assertThat(resource.validate(validation.getCanonical()).isValid()).isTrue();
    }

    @Test
    void badJsonIsOneFatalMessageWithNoCanonicalForm() {
        final ShapeshifterValidation validation = resource.validate("{\"name\": ");
        assertThat(validation.isValid()).isFalse();
        assertThat(validation.getCanonical()).isNull();
        assertThat(validation.getMessages()).hasSize(1);
        assertThat(validation.getMessages().get(0).getSeverity()).isEqualTo("FATAL");
        assertThat(validation.getMessages().get(0).getText()).contains("not valid JSON");
    }

    @Test
    void projectThatReadsButWillNotCompileKeepsItsCanonicalForm() {
        final ShapeshifterValidation validation = resource.validate(PROJECT.replace(
                "\"declarations\": [{\"name\": \"who\", \"type\": \"scalar\"}],", ""));
        assertThat(validation.isValid()).isFalse();
        assertThat(validation.getCanonical()).isNotNull();
        assertThat(validation.getMessages().get(0).getText()).contains("who");
    }

    @Test
    void patternInfoNamesTheGroupsAndExplainsTheRun() {
        final ShapeshifterPatternInfo info = resource.patternInfo(
                new ShapeshifterPatternRequest("(?<a>[0-9]+)-([a-z]+)", false, false));
        assertThat(info.isValid()).isTrue();
        assertThat(info.getGroups()).hasSize(2);
        assertThat(info.getGroups().get(0).getName()).isEqualTo("a");
        assertThat(info.getGroups().get(1).getName()).isNull();
        assertThat(info.getExplain()).contains("tier");

        final ShapeshifterPatternInfo broken = resource.patternInfo(new ShapeshifterPatternRequest("(", false, false));
        assertThat(broken.isValid()).isFalse();
        assertThat(broken.getError()).isNotBlank();
        assertThat(broken.getExplain()).isNull();
    }

    @Test
    void explodeAndPrintAreEachOthersInverse() {
        final String regex = "^(?<level>ERROR|WARN) +(?<msg>.*)$";
        final ShapeshifterText tree = resource.explode(new ShapeshifterPatternRequest(regex, false, false));
        assertThat(tree.getText()).contains("\"sequence\"").contains("\"label\": \"level\"");
        final ShapeshifterText printed = resource.print(new ShapeshifterPatternRequest(tree.getText(), false, false));
        assertThat(printed.getText()).isEqualTo("^(?<level>ERROR|WARN) +(?<msg>.*)$");
    }

    @Test
    void printResolvesARefAgainstTheProjectsPatternsWhenTheyAreSent() {
        // The tree names a part of the project's; sent with the library it prints as the part,
        // and without it the name is unknown (design 44 §3).
        final String tree = "{\"sequence\": [{\"ref\": \"KEY\", \"label\": \"k\"}, {\"tag\": \"=\"}]}";
        final String patterns = "{\"KEY\": {\"take_while\": \"[a-z]\"}}";
        assertThat(resource.print(new ShapeshifterPatternRequest(tree, false, false, patterns)).getText())
                .isEqualTo("(?<k>[a-z]+)=");
        assertThatThrownBy(() -> resource.print(new ShapeshifterPatternRequest(tree, false, false)))
                .hasMessageContaining("KEY");
    }

    @Test
    void encodingsAreTheEnginesLabelsInItsOrderLessTheUnavailable() {
        final List<String> labels = resource.encodings();
        assertThat(labels).startsWith("utf-8", "utf-16le", "utf-16be").contains("raw", "auto");
        for (final String label : labels) {
            assertThat(Encoding.fromLabel(label)).isNotNull();
            assertThat(Encoding.fromLabel(label).isAvailable()).isTrue();
        }
    }

    @Test
    void libraryListsTheStandardEntriesAsRegexes() {
        final ShapeshifterLibrary library = resource.library();
        assertThat(library.getEntries()).extracting(ShapeshifterLibrary.Entry::getName)
                .startsWith("digits", "word", "identifier").contains("ipv4", "csvField");
        assertThat(library.getEntries().get(0).getRegex()).isEqualTo("[0-9]+");
        // Every entry prints to something the engine will compile back: print is the inverse of
        // explode, and a ref prints as its definition.
        for (final ShapeshifterLibrary.Entry entry : library.getEntries()) {
            final String tree = "{\"ref\": \"" + entry.getName() + "\"}";
            assertThat(resource.print(new ShapeshifterPatternRequest(tree, false, false)).getText())
                    .isEqualTo(entry.getRegex());
        }
    }

    @Test
    void previewRunsTheSampleAndReturnsTheTrace() {
        final ShapeshifterTrace trace = resource.preview(new ShapeshifterPreviewRequest(PROJECT, "ab\ncd\n"));
        assertThat(trace.isCompiled()).as(trace.getMessages().toString()).isTrue();
        assertThat(trace.getInput()).isEqualTo("ab\ncd\n");
        assertThat(trace.getOutput()).contains("ab").contains("cd");
        // Two rows, each a child of the document, each with its capture typed, each writing output.
        assertThat(trace.getFrames()).hasSize(2);
        assertThat(trace.getFrames()).extracting(ShapeshifterTrace.Frame::getParentId).containsOnly(0L);
        assertThat(trace.getFrames()).extracting(ShapeshifterTrace.Frame::getTemplateName).containsOnly("row");
        assertThat(trace.getFrames().get(1).getContentOffset()).isEqualTo(3);
        assertThat(trace.getFrames().get(1).getContent()).as("a slice carries no bytes").isNull();
        assertThat(trace.getCaptures()).extracting(ShapeshifterTrace.Capture::getName).containsOnly("who");
        assertThat(trace.getCaptures()).extracting(ShapeshifterTrace.Capture::getValue).containsExactly("ab", "cd");
        assertThat(trace.getCaptures()).extracting(ShapeshifterTrace.Capture::getType).containsOnly("string");
        assertThat(trace.getOutputs()).extracting(ShapeshifterTrace.OutputSpan::getUnit).containsOnly("BYTES");
        assertThat(trace.getOutputs()).allMatch(o -> o.getLength() > 0);
        // Every run profiles: the row template was tried twice and matched twice.
        assertThat(trace.getTimings()).extracting(ShapeshifterTrace.Timing::getMatched).contains(2L);
        assertThat(trace.getAttempts()).extracting(ShapeshifterTrace.Attempt::getContentOffset).contains(0, 3);
        assertThat(trace.getAttemptsSeen()).isEqualTo(trace.getAttempts().size());
        assertThat(trace.getRunNanos()).isPositive();
    }

    @Test
    void previewOfAProjectThatWillNotCompileIsMessagesOnly() {
        final ShapeshifterTrace trace = resource.preview(new ShapeshifterPreviewRequest("{\"name\": \"x\"}", "abc"));
        assertThat(trace.isCompiled()).isFalse();
        assertThat(trace.getFrames()).isEmpty();
        assertThat(trace.getMessages()).isNotEmpty();
        assertThat(trace.getInput()).isEqualTo("abc");
    }

    @Test
    void previewOffsetsAreCharactersNotBytes() {
        // "é" is two bytes and one character: the first row ("é" and its newline) is two
        // characters long, not three bytes, and the second row begins at character 2, not byte 3.
        final ShapeshifterTrace trace = resource.preview(new ShapeshifterPreviewRequest(PROJECT_UTF8, "é\ncd\n"));
        assertThat(trace.isCompiled()).as(trace.getMessages().toString()).isTrue();
        assertThat(trace.getFrames().get(1).getContentOffset()).isEqualTo(2);
        assertThat(trace.getFrames().get(1).getInputOffset()).isEqualTo(2);
        assertThat(trace.getFrames().get(0).getContentLength()).isEqualTo(2);
        assertThat(trace.getInput().substring(trace.getFrames().get(1).getContentOffset()))
                .startsWith("cd");
        assertThat(trace.getAttempts()).extracting(ShapeshifterTrace.Attempt::getContentOffset).contains(0, 2);
        // The captures are placed in their frames' content, in characters: "who" is "cd", at 0
        // in the second row; the first row's "é" is one character long.
        final ShapeshifterTrace.Capture who = trace.getCaptures().get(1);
        assertThat(who.getName()).isEqualTo("who");
        assertThat(who.getContentOffset()).isEqualTo(0);
        assertThat(who.getContentLength()).isEqualTo(2);
        assertThat(trace.getCaptures().get(0).getContentLength()).isEqualTo(1);
        // And every group of every match, bound or not, placed the same way: the first row's
        // group 0 is its two characters, its named group 1 the one "é".
        assertThat(trace.getGroups()).extracting(ShapeshifterTrace.Group::getFrameId).containsExactly(1L, 1L, 2L, 2L);
        assertThat(trace.getGroups()).extracting(ShapeshifterTrace.Group::getName)
                .containsExactly(null, "who", null, "who");
        assertThat(trace.getGroups().get(0).getContentLength()).isEqualTo(2);
        assertThat(trace.getGroups().get(1).getContentOffset()).isEqualTo(0);
        assertThat(trace.getGroups().get(1).getContentLength()).isEqualTo(1);
        for (final ShapeshifterTrace.OutputSpan span : trace.getOutputs()) {
            assertThat(trace.getOutput().substring((int) span.getOffset(), (int) (span.getOffset() + span.getLength())))
                    .isNotEmpty();
        }
    }

    @Test
    void previewMessagesNameTheirFrameAndAreSaidOnce() {
        // "12" matches no row, so the document complains once, at the root frame; the compile's
        // warnings (none here) would come first and not twice.
        final ShapeshifterTrace trace = resource.preview(new ShapeshifterPreviewRequest(PROJECT, "ab\n12\n"));
        assertThat(trace.isCompiled()).isTrue();
        assertThat(trace.getMessages()).extracting(ShapeshifterMessage::getFrameId).containsExactly(0L);
        assertThat(trace.getMessages().get(0).getText()).contains("failed to match all");
        assertThat(trace.getMessages().get(0).getSeverity()).isEqualTo("ERROR");
    }

    private static final String PROJECT_UTF8 = PROJECT.replace("[a-z]+", "[^\\\\n]+");
}
