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

package stroom.shapeshifter.ai.stage;

import stroom.shapeshifter.ai.scoring.OutputRecords;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestShapeSignature {

    private static final String ONE_RECORD = """
            <?xml version="1.0"?>
            <records xmlns="records:2">
              <record><data name="user" value="jb"/><data name="door" value="7"/></record>
            </records>
            """;
    private static final String THREE_RECORDS = """
            <records xmlns="records:2">
              <record><data name="user" value="ab"/><data name="door" value="1"/></record>
              <record><data name="user" value="cd"/><data name="door" value="2"/></record>
              <record><data name="user" value="ef"/><data name="door" value="3"/></record>
            </records>
            """;
    private static final String OTHER_SHAPE = """
            <records xmlns="records:2">
              <record><data name="user" value="jb"/></record>
            </records>
            """;

    @Test
    void anXmlSignatureIsTheFirstRecordsSkeletonNotTheStreams() {
        // Design 01 §5: stable under value variation and record count, sensitive to structure — otherwise
        // every stream is a new shape and no selector, ledger row or regression set is ever hit twice.
        assertThat(ShapeSignature.of(ONE_RECORD)).isEqualTo(ShapeSignature.of(THREE_RECORDS));
        assertThat(ShapeSignature.of(ONE_RECORD)).isNotEqualTo(ShapeSignature.of(OTHER_SHAPE));
        assertThat(ShapeSignature.xmlSkeleton(THREE_RECORDS))
                .isEqualTo("<records><record><data></data><data></data></record>");
    }

    @Test
    void aTextSignatureIsTheFirstLinesTokenClasses() {
        assertThat(ShapeSignature.of("2026-09-17 10:00:01 door7 open\n2026-09-17 10:00:02 door8 shut\n"))
                .isEqualTo(ShapeSignature.of("1999-01-01 00:00:00 gate8 shut\n"));
        assertThat(ShapeSignature.of("a,b,c\n")).isNotEqualTo(ShapeSignature.of("a b c\n"));
    }

    @Test
    void aBlankStreamHasAnEmptyLearningPrefix() {
        assertThat(Stage.learningPrefix("\n\n", ShapeshifterAiDoc.builder().uuid("d").build())).isEmpty();
        assertThat(Stage.learningPrefix("only\n", ShapeshifterAiDoc.builder().uuid("d").heldOutFraction(0.9).build()))
                .describedAs("at least one line is kept when there is one")
                .isEqualTo("only\n");
    }

    @Test
    void markupOverTheSampleSizeLimitIsCutAtARecordBoundary() {
        final StringBuilder document = new StringBuilder("<?xml version=\"1.0\"?>\n<log xmlns=\"urn:x\" v=\"a>b\">\n");
        for (int i = 0; i < 40; i++) {
            document.append("  <entry id=\"").append(i).append("\"><who>user").append(i).append("</who></entry>\n");
        }
        document.append("</log>\n");
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder().uuid("d").sampleSizeLimit(600).build();

        final String prefix = Stage.learningPrefix(document.toString(), doc);

        // Still a document — the root's start tag whole, whole children, the root closed — within the limit.
        assertThat(prefix).startsWith("<log xmlns=\"urn:x\" v=\"a>b\">").endsWith("</log>");
        assertThat(prefix.length()).isLessThanOrEqualTo(600 + "</log>".length());
        assertThat(OutputRecords.parse(prefix)).isPresent();
        assertThat(OutputRecords.parse(prefix).orElseThrow().records()).hasSizeBetween(1, 39);
        // A document too small to cut is shown whole; one whose first child alone exceeds the limit still shows it.
        assertThat(Stage.learningPrefix("<a><b/></a>", doc)).isEqualTo("<a><b/></a>");
        assertThat(OutputRecords.parse(Stage.learningPrefix(document.toString(),
                ShapeshifterAiDoc.builder().uuid("d").sampleSizeLimit(10).build())).orElseThrow().records())
                .hasSize(1);
    }

    @Test
    void textThatLooksLikeMarkupButIsNotADocumentIsCutByWholeLines() {
        final String chat = "<alice> hello there everyone\n<bob> hi alice\n<carol> morning all\n";
        assertThat(ShapeSignature.isMarkup(chat)).isTrue();
        assertThat(Stage.learningPrefix(chat, ShapeshifterAiDoc.builder().uuid("d").sampleSizeLimit(45).build()))
                .isEqualTo("<alice> hello there everyone\n<bob> hi alice\n");
    }

    @Test
    void aRecordsKindIsItsFieldsNotItsValuesNorItsArrayLengths() {
        // Named Data repeat among their siblings, so their Names are the fields and tell a logon from a
        // process creation; a lone User's Name is a value and does not make every user a kind.
        final String logon = "<Event><System><EventID>4624</EventID></System><EventData>"
                             + "<Data Name=\"TargetUserName\">alice</Data><Data Name=\"LogonType\">2</Data>"
                             + "</EventData><User Name=\"alice\"/></Event>";
        final String otherLogon = logon.replace("alice", "bob");
        final String process = "<Event><System><EventID>4688</EventID></System><EventData>"
                               + "<Data Name=\"NewProcessName\">cmd.exe</Data><Data Name=\"LogonType\">2</Data>"
                               + "</EventData><User Name=\"carol\"/></Event>";
        assertThat(ShapeSignature.recordSkeleton(logon)).isEqualTo(ShapeSignature.recordSkeleton(otherLogon));
        assertThat(ShapeSignature.recordSkeleton(logon)).isNotEqualTo(ShapeSignature.recordSkeleton(process));
        assertThat(ShapeSignature.recordSkeleton(logon))
                .contains("<Data Name=TargetUserName>", "<User Name>")
                .doesNotContain("alice");
        // A JSON key always names a field; an array is one kind however many items it holds.
        final String twoRoles = "<map xmlns=\"http://www.w3.org/2013/XSL/json\"><string key=\"user\">a</string>"
                                + "<array key=\"roles\"><string>x</string><string>y</string></array></map>";
        final String threeRoles = twoRoles.replace("<string>y</string>", "<string>y</string><string>z</string>");
        final String noRoles = twoRoles.replace("<array key=\"roles\">", "<array key=\"groups\">");
        assertThat(ShapeSignature.recordSkeleton(twoRoles)).isEqualTo(ShapeSignature.recordSkeleton(threeRoles));
        assertThat(ShapeSignature.recordSkeleton(twoRoles)).isNotEqualTo(ShapeSignature.recordSkeleton(noRoles));
        assertThat(ShapeSignature.recordSkeleton(twoRoles))
                .isEqualTo("<map><string key=user></string><array key=roles><string></string></array></map>");
        // What does not parse falls back to the text skeleton rather than throwing.
        assertThat(ShapeSignature.recordSkeleton("<a><b></a>")).isEqualTo(ShapeSignature.textSkeleton("<a><b></a>"));
    }

    @Test
    void aJsonDocumentIsOneValueOverLinesAndABracketedLogIsNot() {
        assertThat(Stage.isJsonDocument("{\n  \"events\": [\n    {\"a\": 1}\n  ]\n}\n")).isTrue();
        assertThat(Stage.isJsonDocument("[\n  {\"a\": 1},\n  {\"a\": 2}\n]\n")).isTrue();
        assertThat(Stage.isJsonDocument("{\"a\": 1}\n{\"a\": 2}\n")).describedAs("JSON lines").isFalse();
        final String apache = "[Mon Sep 21 10:00:00 2026] [error] [client 10.0.0.1] File does not exist\n"
                              + "[Mon Sep 21 10:00:01 2026] [error] [client 10.0.0.2] File does not exist\n";
        assertThat(Stage.isJsonDocument(apache)).isFalse();
        assertThat(Stage.isJsonDocument("[1.2.3.4] up\n[1.2.3.5] down\n")).isFalse();
        assertThat(Stage.isJsonDocument("{\n\"a\": 1}\n{\"b\": 2}\n")).describedAs("two values").isFalse();
        // A bracketed log is cut by lines and held out like any text, not learned whole.
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder().uuid("d").heldOutFraction(0.5).sampleSizeLimit(1000)
                .build();
        assertThat(Stage.learningPrefix(apache, doc)).isEqualTo(apache.lines().findFirst().orElseThrow() + "\n");
    }

    @Test
    void textOverTheSampleSizeLimitIsCutByWholeLines() {
        final String lines = "one,1\ntwo,2\nthree,3\nfour,4\n";
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder().uuid("d").heldOutFraction(0.0).sampleSizeLimit(14)
                .build();
        assertThat(Stage.learningPrefix(lines, doc)).isEqualTo("one,1\ntwo,2\n");
        assertThat(Stage.learningPrefix(lines, ShapeshifterAiDoc.builder().uuid("d").heldOutFraction(0.0)
                .sampleSizeLimit(2).build())).describedAs("one line is always shown").isEqualTo("one,1\n");
    }
}
