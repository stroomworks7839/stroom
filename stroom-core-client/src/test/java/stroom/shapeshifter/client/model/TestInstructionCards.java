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

import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.Text;
import stroom.shapeshifter.config.OutputNode.ValueOf;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.RefExpression.RefPart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A line typed into a card list, as the cards it describes (design 47 A): text is a text card,
/// a capture reference is a value-of card, and a line that mixes them is split into the sequence.
class TestInstructionCards {

    @Test
    void plainTextIsOneTextCard() {
        assertThat(Instructions.cards("<arrow/>")).isEqualTo(List.of(new Text("<arrow/>")));
    }

    @Test
    void aReferenceAloneIsOneValueOfCard() {
        assertThat(Instructions.cards("$1")).isEqualTo(List.of(group(1)));
        // The whole match is a group like any other
        assertThat(Instructions.cards("$0")).isEqualTo(List.of(group(0)));
        // and a labelled group is read as the Select field reads it
        assertThat(Instructions.cards("$host")).isEqualTo(List.of(
                new ValueOf(new RefExpression(List.of(RefPart.Capture.label("host"))))));
    }

    @Test
    void aTemplatedLineIsSplitIntoTheSequenceItDescribes() {
        // Rung 1's pair line: five cards, exactly as the strict_kv fixture holds them and as if
        // they had been added one at a time. The line is a way of typing; what is stored is
        // separate instructions, because the trace is per instruction.
        assertThat(Instructions.cards(" <pair key=\"$1\">$2</pair>\\n")).isEqualTo(List.of(
                new Text(" <pair key=\""),
                group(1),
                new Text("\">"),
                group(2),
                new Text("</pair>\n")));
        // and rung 2's token, three
        assertThat(Instructions.cards("<w>$0</w>")).isEqualTo(List.of(
                new Text("<w>"), group(0), new Text("</w>")));
    }

    @Test
    void referencesSideBySideAreSeparateCards() {
        assertThat(Instructions.cards("$1$2")).isEqualTo(List.of(group(1), group(2)));
    }

    @Test
    void aReferenceIsOneWhetherOrNotTheCaptureExists() {
        // Ruled: the same line makes the same cards whatever the pattern holds at the time, so
        // output can be written before the pattern is. `costs $5` is text and a value-of.
        assertThat(Instructions.cards("costs $5")).isEqualTo(List.of(new Text("costs "), group(5)));
    }

    @Test
    void twoDollarsWriteOne() {
        assertThat(Instructions.cards("costs $$5")).isEqualTo(List.of(new Text("costs $5")));
        assertThat(Instructions.cards("$$")).isEqualTo(List.of(new Text("$")));
    }

    @Test
    void aDollarThatStartsNoReferenceIsItself() {
        assertThat(Instructions.cards("a $ b")).isEqualTo(List.of(new Text("a $ b")));
        assertThat(Instructions.cards("total$")).isEqualTo(List.of(new Text("total$")));
    }

    @Test
    void aLabelEndsAtTheFirstCharacterALabelCannotHold() {
        // So markup after a label is text and not part of its name
        assertThat(Instructions.cards("$key\">")).isEqualTo(List.of(
                new ValueOf(new RefExpression(List.of(RefPart.Capture.label("key")))),
                new Text("\">")));
        // and a number ends at the first non-digit
        assertThat(Instructions.cards("$1px")).isEqualTo(List.of(group(1), new Text("px")));
    }

    @Test
    void aBareWordIsTextAndNeverAName() {
        // The field does not guess: a declared name goes through the kind menu, where the
        // Select field's own grammar reads it.
        assertThat(Instructions.cards("heading")).isEqualTo(List.of(new Text("heading")));
        assertThat(Instructions.cards("index()")).isEqualTo(List.of(new Text("index()")));
    }

    @Test
    void textKeepsItsSpacesAndSpellsItsLineBreaks() {
        // Enter adds the card, so a line break is typed as the other forms spell it
        assertThat(Instructions.cards("<records>\\n")).isEqualTo(List.of(new Text("<records>\n")));
        assertThat(Instructions.cards("a\\tb")).isEqualTo(List.of(new Text("a\tb")));
        // and a backslash that is wanted is doubled
        assertThat(Instructions.cards("a\\\\nb")).isEqualTo(List.of(new Text("a\\nb")));
        // Leading and trailing spaces are the author's
        assertThat(Instructions.cards("  x ")).isEqualTo(List.of(new Text("  x ")));
        assertThat(Instructions.cards(" ")).isEqualTo(List.of(new Text(" ")));
    }

    @Test
    void anEmptyLineIsNoCards() {
        assertThat(Instructions.cards("")).isEmpty();
        assertThat(Instructions.cards(null)).isEmpty();
    }

    @Test
    void aNumberTooLongToBeAGroupIsText() {
        // More digits than a group number can hold is not a group (Instructions.isGroup), so it
        // stays what was typed rather than overflowing
        assertThat(Instructions.cards("$1234567890")).isEqualTo(List.of(new Text("$1234567890")));
    }

    @Test
    void aBracedReferenceIsAnyReferenceTheSelectFieldReads() {
        // Design 47 H: a declared name, which a bare word never is, and a name at a match index
        assertThat(Instructions.cards("${record_body}"))
                .isEqualTo(List.of(new ValueOf(Instructions.read("record_body"))));
        assertThat(Instructions.cards("\\n      <data name=\"${heading[matchCount()]}\" value=\"$1\"/>"))
                .isEqualTo(List.of(new Text("\n      <data name=\""),
                        new ValueOf(Instructions.read("heading[matchCount()]")),
                        new Text("\" value=\""), group(1), new Text("\"/>")));
        // Spaces inside the braces are not the reference's
        assertThat(Instructions.cards("${ record_body }"))
                .isEqualTo(List.of(new ValueOf(Instructions.read("record_body"))));
        // A bare word is still text
        assertThat(Instructions.cards("record_body")).isEqualTo(List.of(new Text("record_body")));
    }

    @Test
    void aBraceInsideAQuotedLiteralDoesNotCloseTheReference() {
        assertThat(Instructions.cards("${\"}\" $1}x"))
                .isEqualTo(List.of(new ValueOf(Instructions.read("\"}\" $1")), new Text("x")));
    }

    @Test
    void aDollarBeforeABraceIsWrittenDoubled() {
        assertThat(Instructions.cards("$${HOME}")).isEqualTo(List.of(new Text("${HOME}")));
    }

    @Test
    void aBracedReferenceThatIsNotOneIsRefusedSayingWhy() {
        assertThatThrownBy(() -> Instructions.cards("a${record_body"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("closing }");
        assertThatThrownBy(() -> Instructions.cards("${ }"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("needs a reference");
        // A modifier that did not bind, as the Select field refuses it
        assertThatThrownBy(() -> Instructions.cards("${x as}"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("'as' comes after");
    }

    @Test
    void aSpellingTheReaderCouldNotFinishIsNotTakenForAName() {
        // The reader makes one whole name of what it cannot read; braced, that is a typo
        assertThatThrownBy(() -> Instructions.cards("${heading[matchCount()}"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("check its brackets");
        assertThatThrownBy(() -> Instructions.cards("${size(xs}"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("check its brackets");
        // and a group is written $1, not as a name of digits
        assertThatThrownBy(() -> Instructions.cards("${1}"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("a capture group is $1");
    }

    @Test
    void aQuoteLeftOpenInsideTheBracesIsWhatIsSaid() {
        // The } was typed; it is inside the quote
        assertThatThrownBy(() -> Instructions.cards("${\"abc}"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("quote inside ${…} is not closed");
    }

    private static OutputNode group(final int group) {
        return new ValueOf(RefExpression.group(group));
    }
}
