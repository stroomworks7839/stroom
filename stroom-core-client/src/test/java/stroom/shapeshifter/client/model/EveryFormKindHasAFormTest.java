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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every kind in {@link Instructions#FORM_KINDS} must actually have a form.
 *
 * <p>Listing a kind there and writing no form for it is worse than leaving it off: the editor
 * takes the form path, {@code showKind} falls through to its default, and the author is shown an
 * empty wire-form box that OK cannot save — where a kind that is *not* listed at least gets a
 * JSON skeleton to edit. Seven kinds shipped in that state on 2026-11-12 and the suite was
 * green, because no fixture used any of them and
 * {@link WireFormCensusTest} can only see what the corpus holds.
 *
 * <p>This reads the two switches out of `InstructionEditController` rather than calling them,
 * because `showKind` is private and needs a GWT view that no JVM test can build. Reading source
 * is a poor instrument and is used here for the same reason the engine's `TestStoryFixtureCopy`
 * reads a file: it is the only place the invariant can run at all. If the shape of those methods
 * changes enough to defeat the parse, this test fails loudly rather than passing vacuously.
 */
class EveryFormKindHasAFormTest {

    private static final Path CONTROLLER = Paths.get(
            "src", "main", "java", "stroom", "shapeshifter", "client", "component",
            "InstructionEditController.java");

    private static final Pattern CASE = Pattern.compile("case \"([^\"]+)\":");

    @Test
    void everyKindTheMenuOffersAsAFormHasOne() throws IOException {
        final String source = Files.readString(CONTROLLER, StandardCharsets.UTF_8);
        final Set<String> shown = labels(source, "private void showKind(final String kind) {");
        final Set<String> written = labels(source, "switch (kind) {");

        assertThat(shown).as("the kinds showKind lays out; the parse has failed if this is small")
                .hasSizeGreaterThan(20);
        assertThat(written).as("the kinds write() builds; the parse has failed if this is small")
                .hasSizeGreaterThan(20);

        final List<String> noLayout = new ArrayList<>(Instructions.FORM_KINDS);
        noLayout.removeAll(shown);
        assertThat(noLayout)
                .as("kinds in FORM_KINDS that showKind does not lay out: the author would see an "
                    + "empty wire-form box with no way to save it")
                .isEmpty();

        final List<String> noBuild = new ArrayList<>(Instructions.FORM_KINDS);
        noBuild.removeAll(written);
        assertThat(noBuild)
                .as("kinds in FORM_KINDS that write() cannot build: OK would throw")
                .isEmpty();
    }

    /// The case labels of the switch that follows a marker, to the end of that switch.
    private static Set<String> labels(final String source, final String marker) {
        final int from = source.indexOf(marker);
        assertThat(from).as("%s should be in the controller", marker).isNotNegative();
        final Set<String> labels = new LinkedHashSet<>();
        final Matcher matcher = CASE.matcher(source);
        int depth = 0;
        final int at = from + marker.length();
        // To the brace that closes the switch the marker opened. A brace inside a string, a
        // character literal or a comment is text, not structure: the line's help shows `${…}`,
        // and a comment's apostrophe is not a quote.
        char quote = 0;
        for (int i = at; i < source.length(); i++) {
            final char c = source.charAt(i);
            if (quote == 0 && c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                final int end = source.indexOf('\n', i);
                i = end < 0
                        ? source.length()
                        : end;
            } else if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth == 0) {
                    matcher.region(at, i);
                    while (matcher.find()) {
                        labels.add(matcher.group(1));
                    }
                    return labels;
                }
                depth--;
            }
        }
        return labels;
    }
}
