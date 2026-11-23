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

package stroom.gwt.workbench.client.app.shapeshifter;

import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.shapeshifter.shared.ShapeshifterDoc;

import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.List;

/// The Design tab's layout over a long sample (design 47 E): forty records of many lines, each
/// with fields matched in it, stepped through and read with the data panes open and folded. Every
/// other story's sample is a few lines, which any layout has room for.
public final class ShapeshifterLongSampleStories {

    private static final int RECORDS = 40;
    private static final String SOURCE = "00000000-0000-0000-0000-000000000001";
    private static final String RECORD = "00000000-0000-0000-0000-000000000002";
    private static final String FIELD = "00000000-0000-0000-0000-000000000003";
    private static final String JUNK = "00000000-0000-0000-0000-000000000004";
    private static final String BAD_LINE = "    ?? line cut short by the sender";
    private static final String MESSAGE = "unrecognised field: ?? line cut short by the sender";
    private static final String STEPPER_INDEX = ".breadcrumb__step .breadcrumb__index";
    private static final TextMatch NEXT = TextMatch.containing("Next match");

    private static final String PROJECT = """
            {
             "name": "long_records",
             "version": 5,
             "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "utf-8"},
             "templates": [
              {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
               "body": [
                {"text": "<events>\\n"},
                {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "record"}},
                {"text": "</events>\\n"}]},
              {"id": "00000000-0000-0000-0000-000000000002", "name": "record", "mode": "record",
               "match": {"regex": {"pattern": "event ([0-9]+) \\\\{\\\\n([^}]*)\\\\}\\\\n"}},
               "body": [{"text": "  <event id=\\""}, {"value-of": {"parts": [{"capture": {"group": 1}}]}},
                        {"text": "\\">\\n"},
                        {"apply-templates": {"select": {"parts": [{"capture": {"group": 2}}]}, "mode": "field"}},
                        {"text": "  </event>\\n"}]},
              {"id": "00000000-0000-0000-0000-000000000003", "name": "field", "mode": "field",
               "match": {"regex": {"pattern": " *([a-z_]+) = ([^\\\\n]*)\\\\n"}},
               "body": [{"text": "    <field name=\\""}, {"value-of": {"parts": [{"capture": {"group": 1}}]}},
                        {"text": "\\" value=\\""}, {"value-of": {"parts": [{"capture": {"group": 2}}]}},
                        {"text": "\\"/>\\n"}]},
              {"id": "00000000-0000-0000-0000-000000000004", "name": "junk_field", "mode": "field",
               "consume": true,
               "match": {"regex": {"pattern": "[^\\\\n]*\\\\n"}},
               "body": [{"emit-error": {"severity": "warning",
                         "message": {"parts": [{"text": "unrecognised field: "}, {"capture": {"group": 0}}]}}}]}
             ]
            }""";

    private static final Run RUN = new Run();

    private ShapeshifterLongSampleStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Shapeshifter/ShapeshifterLongSample", ShapeshifterLongSampleStories.class)
                .layout(StoryLayout.FULLSCREEN)

                // The whole of the sample and of the output, before any match is chosen
                .story("TheWholeSample", ShapeshifterLongSampleStories::render)
                .withPlay(play -> {
                    Walkthrough.expectTried(play, "record", String.valueOf(RECORDS));
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A record in the middle of it, reached by stepping
                .story("ARecordSteppedTo", ShapeshifterLongSampleStories::render)
                .withPlay(play -> {
                    record(play, 12);
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A field of that record: two levels down, with its names in scope
                .story("AFieldSteppedTo", ShapeshifterLongSampleStories::render)
                .withPlay(play -> {
                    Walkthrough.expectTried(play, "field", String.valueOf(RUN.fields));
                    ShapeshifterEditorStories.selectTemplate(play, "field");
                    play.waitFor(() -> play.expect(play.getByText("1/" + RUN.fields, STEPPER_INDEX)).toBeVisible());
                    for (int i = 0; i < 20; i++) {
                        play.click(play.getByTitle(NEXT));
                    }
                    play.waitFor(() -> play.expect(play.getByText("21/" + RUN.fields, STEPPER_INDEX)).toBeVisible());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A pattern and a card's text are shown with their spaces as they are written, on
                // one line: ` *([a-z_]+)` is not `*([a-z_]+)`
                .story("SpacesAreShownAsWritten", ShapeshifterLongSampleStories::render)
                .withPlay(play -> {
                    Walkthrough.expectTried(play, "field", String.valueOf(RUN.fields));
                    ShapeshifterEditorStories.selectTemplate(play, "field");
                    final Query pattern = play.querySelector(".template-strip__match-pattern");
                    play.waitFor(() -> play.expect(pattern.textContent()).toMatch(TextMatch.regex("^ \\*")));
                    play.expect(pattern.computedStyle("white-space")).toEqual("pre");
                    // The leading space is marked, and opened the field holds it and no mark
                    final Query mark = play.querySelector(".template-strip__match-space");
                    play.expect(mark.textContent()).toEqual(" ");
                    play.expect(mark).toBeVisible();
                    // In the pattern's own font, so that a space is as wide as its dot
                    play.expect("the space is a character wide", () ->
                            mark.element().get().getOffsetWidth() >= 7).toEqual(true);
                    final Query text = play.querySelector(".body-card__literal");
                    play.expect(text.textContent()).toMatch(TextMatch.regex("^\" {4}<field"));
                    play.expect(text.computedStyle("white-space")).toEqual("pre");
                    play.click(pattern);
                    play.waitFor(() -> play.expect(play.getByLabelText("Match pattern"))
                            .toHaveValue(" *([a-z_]+) = ([^\\n]*)\\n"));
                    play.keyboard("{Escape}");
                    // A pattern with no space at its edges has no mark
                    ShapeshifterEditorStories.selectTemplate(play, "record");
                    play.waitFor(() -> play.expect(pattern.textContent()).toMatch(TextMatch.regex("^event ")));
                    play.expect(mark).toBeNull();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // The variables and the output folded, the input with the column
                .story("ARecordWithTheInputAlone", ShapeshifterLongSampleStories::render)
                .withPlay(play -> {
                    record(play, 12);
                    fold(play, "Output");
                    fold(play, "Variables");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // The input and the variables folded, the output with the column
                .story("ARecordWithTheOutputAlone", ShapeshifterLongSampleStories::render)
                .withPlay(play -> {
                    record(play, 12);
                    fold(play, "Input");
                    fold(play, "Variables");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // A message pressed goes to the match it was said in, far down the sample
                .story("AMessageGoneTo", ShapeshifterLongSampleStories::render)
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getAllByText(MESSAGE).first()).toBeVisible());
                    play.click(play.getAllByText(MESSAGE).nth(2));
                    play.waitFor(() -> play.expect(play.getByText(TextMatch.containing("junk_field #")))
                            .toBeVisible());
                    ShapeshifterEditorStories.expectNoProblems(play);
                });
    }

    // Stepped to a record, counted from one
    private static void record(final Play play, final int which) {
        // Once there has been a run: selecting a template goes to its first match only if it has one
        Walkthrough.expectTried(play, "record", String.valueOf(RECORDS));
        ShapeshifterEditorStories.selectTemplate(play, "record");
        play.waitFor(() -> play.expect(play.getByText("1/" + RECORDS, STEPPER_INDEX))
                .toBeVisible());
        for (int i = 1; i < which; i++) {
            play.click(play.getByTitle(NEXT));
        }
        play.waitFor(() -> play.expect(play.getByText(which + "/" + RECORDS, STEPPER_INDEX))
                .toBeVisible());
    }

    private static void fold(final Play play, final String pane) {
        play.click(play.getByRole("button", StroomDom.button(pane)));
        play.waitFor(() -> play.expect(play.getByRole("button", StroomDom.button(pane)))
                .toHaveAttribute("aria-expanded", "false"));
    }

    private static Widget render(final StoryContext context) {
        final String doc = "{\"type\": \"" + ShapeshifterDoc.TYPE + "\","
                           + " \"uuid\": \"ss-strict-kv\", \"name\": \"Long Records\","
                           + " \"data\": " + ShapeshifterEditorStories.asJsonString(PROJECT) + ","
                           + " \"sampleText\": " + ShapeshifterEditorStories.asJsonString(RUN.input()) + ","
                           + " \"sampleKind\": \"PASTED\"}";
        return ShapeshifterEditorStories.render(context, RestFixtures.builder()
                .get("/shapeshifter/v1/ss-strict-kv", RestReply.json(doc))
                .post("/shapeshifter/v1/preview", RestReply.json(RUN.json()))
                .build());
    }

    // The sample and the run of it, made together so that they cannot disagree: every frame, every
    // span and every count is measured as it is written.
    private static final class Run {

        private final StringBuilder input = new StringBuilder();
        private final StringBuilder output = new StringBuilder();
        private final List<String> frames = new ArrayList<>();
        private final List<String> captures = new ArrayList<>();
        private final List<String> outputs = new ArrayList<>();
        private final List<String> instructions = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private int nextFrame = 2;
        private int fields;
        private int junk;

        private Run() {
            card(1, 0, "<events>\n");
            final int from = output.length();
            for (int i = 0; i < RECORDS; i++) {
                addRecord(i);
            }
            span(instructions, "\"frameId\": 1, \"index\": 1", from, output.length());
            card(1, 2, "</events>\n");
            span(outputs, "\"frameId\": 1", 0, output.length());
            frames.add(0, frame(1, 0, SOURCE, "source", 0, 0, 0, 0, input.length()));
        }

        private void addRecord(final int i) {
            final int id = nextFrame++;
            final int start = input.length();
            final int out = output.length();
            final int at = frames.size();
            input.append("event ").append(1000 + i).append(" {\n");
            card(id, 0, "  <event id=\"");
            card(id, 1, String.valueOf(1000 + i));
            card(id, 2, "\">\n");
            final int from = output.length();
            int index = 0;
            addField(id, start, index++, "time", "2026-11-23T10:" + (10 + i) + ":07.123Z");
            addField(id, start, index++, "host", "web" + (10 + i % 5) + ".example.org");
            addField(id, start, index++, "user", "operator" + i % 7);
            addField(id, start, index++, "action", i % 3 == 0
                    ? "logon"
                    : "read");
            addField(id, start, index++, "path", "/srv/records/2026/11/23/batch-" + i + "/part-0001.dat");
            addField(id, start, index++, "outcome", i % 4 == 0
                    ? "refused: the credential had expired and was not renewed in the time allowed"
                    : "success");
            if (i % 13 == 8) {
                addJunk(id, start);
            }
            addField(id, start, index, "bytes_sent", String.valueOf(4096 + 37 * i));
            span(instructions, "\"frameId\": " + id + ", \"index\": 3", from, output.length());
            input.append("}\n");
            card(id, 4, "  </event>\n");
            span(outputs, "\"frameId\": " + id, out, output.length());
            frames.add(at, frame(id, 1, RECORD, "record", i, 1, start, start, input.length() - start));
            capture(id, "id", String.valueOf(1000 + i), 6);
        }

        private void addField(final int record, final int recordStart, final int index,
                              final String name, final String value) {
            final int id = nextFrame++;
            final int start = input.length();
            final int out = output.length();
            input.append("    ").append(name).append(" = ").append(value).append("\n");
            card(id, 0, "    <field name=\"");
            card(id, 1, name);
            card(id, 2, "\" value=\"");
            card(id, 3, value);
            card(id, 4, "\"/>\n");
            span(outputs, "\"frameId\": " + id, out, output.length());
            frames.add(frame(id, record, FIELD, "field", index, 2, start, start - recordStart,
                    input.length() - start));
            capture(id, "name", name, 4);
            capture(id, "value", value, 4 + name.length() + 3);
            fields++;
        }

        private void addJunk(final int record, final int recordStart) {
            final int id = nextFrame++;
            final int start = input.length();
            input.append(BAD_LINE).append("\n");
            span(instructions, "\"frameId\": " + id + ", \"index\": 0", output.length(), output.length());
            span(outputs, "\"frameId\": " + id, output.length(), output.length());
            frames.add(frame(id, record, JUNK, "junk_field", 0, 2, start, start - recordStart,
                    input.length() - start));
            messages.add("{\"severity\": \"warning\", \"text\": \"" + MESSAGE + "\", \"frameId\": " + id + "}");
            junk++;
        }

        // What a card wrote
        private void card(final int frame, final int index, final String text) {
            final int from = output.length();
            output.append(text);
            span(instructions, "\"frameId\": " + frame + ", \"index\": " + index, from, output.length());
        }

        private static void span(final List<String> spans, final String of, final int from, final int to) {
            spans.add("{" + of + ", \"offset\": " + from + ", \"length\": " + (to - from) + ", \"unit\": \"BYTES\"}");
        }

        private void capture(final int frame, final String name, final String value, final int at) {
            captures.add("{\"frameId\": " + frame + ", \"name\": \"" + name + "\", \"value\": \"" + value
                         + "\", \"contentOffset\": " + at + ", \"contentLength\": " + value.length() + "}");
        }

        private String frame(final int id, final int parent, final String template, final String name,
                             final int index, final int depth, final int inputOffset, final int contentOffset,
                             final int length) {
            return "{\"id\": " + id + ", \"parentId\": " + parent + ", \"templateId\": \"" + template
                   + "\", \"templateName\": \"" + name + "\", \"matchIndex\": " + index
                   + ", \"depth\": " + depth + ", \"inputOffset\": " + inputOffset
                   + ", \"inputLength\": " + length + ", \"contentOffset\": " + contentOffset
                   + ", \"contentLength\": " + length
                   // The source's content is the input, which the trace already carries
                   + (id == 1
                    ? ""
                    : ", \"content\": " + ShapeshifterEditorStories.asJsonString(
                            input.substring(inputOffset, inputOffset + length)))
                   + "}";
        }

        private String input() {
            return input.toString();
        }

        private String json() {
            return "{\"compiled\": true, \"sampleRead\": true,"
                   + " \"input\": " + ShapeshifterEditorStories.asJsonString(input.toString()) + ","
                   + " \"output\": " + ShapeshifterEditorStories.asJsonString(output.toString()) + ","
                   + " \"frames\": " + frames + ", \"captures\": " + captures + ","
                   + " \"groups\": [], \"attempts\": [], \"guards\": [],"
                   + " \"attemptsSeen\": " + (RECORDS + fields + 2 * junk) + ","
                   + " \"timings\": [" + timing(RECORD, RECORDS, RECORDS, 90_000) + ","
                   + timing(FIELD, fields + junk, fields, 210_000) + "," + timing(JUNK, junk, junk, 4_000) + "],"
                   + " \"outputs\": " + outputs + ", \"instructions\": " + instructions + ","
                   + " \"messages\": " + messages + ", \"runNanos\": 1912000}";
        }

        private static String timing(final String template, final int attempts, final int matched,
                                     final int nanos) {
            return "{\"templateId\": \"" + template + "\", \"attempts\": " + attempts + ", \"matched\": "
                   + matched + ", \"nanos\": " + nanos + "}";
        }
    }
}
