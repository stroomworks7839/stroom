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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design 21 phase 2a: what {@code write} means by container, Saxon's forms, and the rule that
 * decides a wrapped start tag — pinned at the boundary, since the goldens leave 81 to 92 unseen.
 */
class XmlByteSinkTest {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final XmlByteSink sink = new XmlByteSink(bytes);

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void documentLevelBytesAreRaw() {
        sink.write("<?xml version=\"1.1\"?>\n<not & escaped>");
        assertThat(output()).isEqualTo("<?xml version=\"1.1\"?>\n<not & escaped>");
        assertThat(sink.position()).isEqualTo(output().length());
    }

    @Test
    void anEmptyElementSelfClosesAndTheRootEndsTheDocumentWithANewline() {
        sink.startElement("records");
        sink.endElement();
        assertThat(output()).isEqualTo("<records/>\n");
    }

    @Test
    void attributesAreEscapedInSaxonsFormsAndContentInItsOwn() {
        sink.startElement("data");
        sink.startAttribute("value");
        sink.write("a&b \"<c>\"\n\r\t");
        sink.endAttribute();
        sink.write("x < y & \"z\"\r");
        sink.endElement();
        assertThat(output()).isEqualTo(
                "<data value=\"a&amp;b &#34;&lt;c&gt;&#34;&#xA;&#xD;&#x9;\">x &lt; y &amp; \"z\"&#xD;</data>\n");
    }

    @Test
    void anAttributeValueMaySplitAMultiByteCharacterAcrossWrites() {
        final byte[] e = "é".getBytes(StandardCharsets.UTF_8);
        sink.startElement("d");
        sink.startAttribute("v");
        sink.write(e, 0, 1);
        sink.write(e, 1, 1);
        sink.endAttribute();
        sink.endElement();
        assertThat(output()).isEqualTo("<d v=\"é\"/>\n");
    }

    @Test
    void contentMaySplitAMultiByteCharacterAcrossWritesToo() {
        final byte[] text = "a€b".getBytes(StandardCharsets.UTF_8); // € is three bytes
        sink.startElement("d");
        sink.write(text, 0, 2);
        sink.write(text, 2, 1);
        sink.write(text, 3, 2);
        sink.endElement();
        assertThat(output()).isEqualTo("<d>a€b</d>\n");

        bytes.reset();
        final XmlByteSink s = new XmlByteSink(bytes);
        final byte[] e = "é".getBytes(StandardCharsets.UTF_8);
        s.startElement("d");
        s.write(e, 0, 1);
        s.write(e, 1, 1);
        s.endElement();
        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("<d>é</d>\n");
    }

    @Test
    void whitespaceInsideTextIsKeptAndWhitespaceBetweenElementsIsNot() {
        sink.startElement("d");
        sink.write("a");
        sink.write(" ");
        sink.write("b");
        sink.endElement();
        assertThat(output()).isEqualTo("<d>a b</d>\n");

        bytes.reset();
        final XmlByteSink s = new XmlByteSink(bytes);
        s.startElement("d");
        s.write("  ");
        s.write("x");
        s.write("  ");
        s.endElement();
        // Once an element has text, its whitespace is its text — before, between and after.
        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("<d>  x  </d>\n");
    }

    @Test
    void mixedContentIsNotIndentedInsideItsText() {
        sink.startElement("p");
        sink.write("Hello ");
        sink.startElement("b");
        sink.write("big");
        sink.endElement();
        sink.write(" world");
        sink.endElement();
        assertThat(output()).isEqualTo("<p>Hello <b>big</b> world</p>\n");
    }

    @Test
    void theFaithfulLayoutAddsNothingAndDropsNothing() {
        final XmlByteSink faithful = new XmlByteSink(bytes, XmlByteSink.Layout.FAITHFUL);
        faithful.startElement("pre");
        faithful.startAttribute("name");
        faithful.write("a very long attribute value that would wrap under the indenting layout, being past eighty");
        faithful.endAttribute();
        faithful.write("  two\n   spaces\n");
        faithful.startElement("i");
        faithful.endElement();
        faithful.write("\n");
        faithful.endElement();
        assertThat(output()).isEqualTo(
                "<pre name=\"a very long attribute value that would wrap under the indenting layout, being past "
                + "eighty\">  two\n   spaces\n<i/>\n</pre>");
    }

    @Test
    void childrenIndentByThreeAndWhitespaceContentIsTheIndentersNotTheAuthors() {
        sink.startElement("records");
        sink.write("\n   ");
        sink.startElement("record");
        sink.startElement("data");
        sink.startAttribute("name");
        sink.write("n");
        sink.endAttribute();
        sink.endElement();
        sink.endElement();
        sink.endElement();
        assertThat(output()).isEqualTo("""
                <records>
                   <record>
                      <data name="n"/>
                   </record>
                </records>
                """);
    }

    @Test
    void theStartTagWrapsWhenSaxonsSumPassesEighty() {
        // name + value + 8 per attribute: 4 + 7 + 8 = 19 for name="message"; the second attribute
        // is sized so the sum lands exactly on 80, and then one more.
        for (final int valueLength : new int[]{80 - 19 - 5 - 8, 80 - 19 - 5 - 8 + 1}) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final XmlByteSink s = new XmlByteSink(out);
            s.startElement("records");
            s.startElement("data");
            s.startAttribute("name");
            s.write("message");
            s.endAttribute();
            s.startAttribute("value");
            s.write("v".repeat(valueLength));
            s.endAttribute();
            s.endElement();
            s.endElement();
            final String text = out.toString(StandardCharsets.UTF_8);
            if (valueLength == 80 - 19 - 5 - 8) {
                assertThat(text).contains("\n   <data name=\"message\" value=\"" + "v".repeat(valueLength) + "\"/>");
            } else {
                assertThat(text).contains(
                        "\n   <data name=\"message\"\n         value=\"" + "v".repeat(valueLength) + "\"/>");
            }
        }
    }

    @Test
    void namespaceDeclarationsCountTowardsTheSumAndAlignUnderTheFirst() {
        sink.startElement("records");
        sink.namespace("", "records:2");
        sink.namespace("xsi", "http://www.w3.org/2001/XMLSchema-instance");
        sink.startAttribute("xsi:schemaLocation");
        sink.write("records:2 file://records-v2.0.xsd");
        sink.endAttribute();
        sink.startAttribute("version");
        sink.write("2.0");
        sink.endAttribute();
        sink.endElement();
        assertThat(output()).isEqualTo("""
                <records xmlns="records:2"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="records:2 file://records-v2.0.xsd"
                         version="2.0"/>
                """);
    }

    @Test
    void anAttributeAfterContentIsRefusedByName() {
        sink.startElement("a");
        sink.write("text");
        assertThatThrownBy(() -> sink.startAttribute("late"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'late'")
                .hasMessageContaining("<a>");
        assertThatThrownBy(() -> sink.namespace("p", "urn:p"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'p'");
    }

    @Test
    void anAttributeMustBeClosedBeforeAnythingElse() {
        sink.startElement("a");
        sink.startAttribute("x");
        assertThatThrownBy(() -> sink.startElement("b")).hasMessageContaining("'x' is open");
        assertThatThrownBy(sink::endElement).hasMessageContaining("'x' is open");
        assertThatThrownBy(() -> new XmlByteSink(new ByteArrayOutputStream()).endAttribute())
                .hasMessageContaining("no attribute open");
    }
}
