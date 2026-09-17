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

package stroom.shapeshifter.engine.ds3;

import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.engine.fixture.EngineHarness;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A {@code <var>} stores a match, and a reference names which group of it to read.
 *
 * <p>Every test here asks for a group the corpus never asks for. The whole fixture corpus contains
 * three var-group references and all three are {@code $heading$1} — the one value of N that the
 * two hardcodes E48 describes happened to agree on, which is why reading group 2 returned group
 * 1's value for as long as it did.
 *
 * <p>The rule, taken from DS3's own {@code RefParser} and {@code StoreNode}: a bare {@code $h} is
 * group 0, {@code $h$N} is group N, and only the groups something reads are ever stored.
 */
class VarGroupTest {

    // -----------------------------------------------------------------------------------
    // Reading groups of a regex var
    // -----------------------------------------------------------------------------------

    /**
     * Three groups of one var, read in one configuration, each giving its own value.
     *
     * <p>This is E48's reproduction. Before the fix all three read {@code alpha}.
     */
    @Test
    void differentGroupsOfOneVarReadDifferentValues() {
        assertThat(run("""
                <data name="zero" value="$h$0" />
                <data name="one" value="$h$1" />
                <data name="two" value="$h$2" />"""))
                .contains("""
                        <data name="zero" value="H:alpha,beta"/>""")
                .contains("""
                        <data name="one" value="alpha"/>""")
                .contains("""
                        <data name="two" value="beta"/>""");
    }

    /**
     * A reference that names no group reads group 0 — the whole match.
     *
     * <p>The two spellings that name no group are {@code $h$} and {@code @h}. A bare {@code $h}
     * is not the group-0 form but a malformed one: both this parser and DS3's {@code RefParser}
     * read what follows the id as the group number and refuse {@code h}.
     */
    @Test
    void referenceWithNoGroupReadsGroupZero() {
        assertThat(run("""
                <data name="dollar" value="$h$" />
                <data name="at" value="@h" />"""))
                .contains("""
                        <data name="dollar" value="H:alpha,beta"/>""")
                .contains("""
                        <data name="at" value="H:alpha,beta"/>""");
    }

    /** And the bare {@code $h} is refused rather than quietly read as something. */
    @Test
    void bareDollarNameIsRefused() {
        assertThatThrownBy(() -> Ds3Migration.importXml(config("""
                <data name="bare" value="$h" />""")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("malformed number");
    }

    /** The {@code @name.group} spelling of the same thing. */
    @Test
    void atSpellingReadsTheSameGroup() {
        assertThat(run("""
                <data name="two" value="@h.2" />"""))
                .contains("""
                        <data name="two" value="beta"/>""");
    }

    // -----------------------------------------------------------------------------------
    // Reading groups of a split var
    // -----------------------------------------------------------------------------------

    /**
     * For a split, group 0 keeps the container and group 1 has it stripped.
     *
     * <p>Which is why {@code $heading$1} is what a DS3 CSV configuration writes, and why forcing
     * every var to group 1 looked right for so long.
     */
    @Test
    void splitGroupZeroKeepsTheContainerAndGroupOneStripsIt() {
        final String ds3 = """
                <?xml version="1.1" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" version="3.0">
                  <split delimiter="\\n" maxMatch="1">
                    <group value="$1">
                      <split delimiter="," containerStart="&#34;" containerEnd="&#34;">
                        <var id="f" />
                      </split>
                    </group>
                  </split>
                  <split delimiter="\\n">
                    <group value="$1">
                      <data name="raw" value="$f$0" />
                      <data name="stripped" value="$f$1" />
                    </group>
                  </split>
                </dataSplitter>
                """;
        final String out = output(ds3, "\"quoted\"\nx\n");
        assertThat(out).contains("""
                <data name="raw" value="&#34;quoted&#34;"/>""");
        assertThat(out).contains("""
                <data name="stripped" value="quoted"/>""");
    }

    // -----------------------------------------------------------------------------------
    // What gets bound
    // -----------------------------------------------------------------------------------

    /** Only the groups something reads are bound, which is what DS3's StoreNode does. */
    @Test
    void onlyTheGroupsThatAreReadAreBound() {
        final Project project = Ds3Migration.importXml(config("""
                <data name="two" value="$h$2" />"""));
        assertThat(captureNames(project)).containsExactly("h$2");
    }

    /** A var nothing reads binds nothing at all. */
    @Test
    void unreadVarBindsNothing() {
        final Project project = Ds3Migration.importXml(config("""
                <data name="literal" value="nothing reads h" />"""));
        assertThat(captureNames(project)).isEmpty();
    }

    /**
     * A group's own value is rewritten too — the one read the old group-forcing never reached,
     * because {@code <group value="...">} parsed without going through it.
     */
    @Test
    void groupValueReadsAVarGroup() {
        final String ds3 = """
                <?xml version="1.1" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" version="3.0">
                  <regex pattern="H:(\\w+),(\\w+)\\n" maxMatch="1">
                    <var id="h" />
                  </regex>
                  <regex pattern="D:\\w+\\n">
                    <group value="$h$2">
                      <regex pattern="(\\w+)">
                        <data name="viaGroup" value="$1" />
                      </regex>
                    </group>
                  </regex>
                </dataSplitter>
                """;
        assertThat(output(ds3, "H:alpha,beta\nD:x\n")).contains("""
                <data name="viaGroup" value="beta"/>""");
    }

    // -----------------------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------------------

    private static String config(final String reads) {
        return """
                <?xml version="1.1" encoding="UTF-8"?>
                <dataSplitter xmlns="data-splitter:3" version="3.0">
                  <regex pattern="H:(\\w+),(\\w+)\\n" maxMatch="1">
                    <var id="h" />
                  </regex>
                  <regex pattern="D:\\w+\\n">
                    READS
                  </regex>
                </dataSplitter>
                """.replace("READS", reads);
    }

    private static String run(final String reads) {
        return output(config(reads), "H:alpha,beta\nD:x\n");
    }

    private static String output(final String ds3, final String input) {
        final EngineHarness.Outcome outcome =
                EngineHarness.runDs3(ds3, input.getBytes(StandardCharsets.UTF_8));
        assertThat(outcome.messages()).isEmpty();
        return new String(outcome.output(), StandardCharsets.UTF_8);
    }

    private static List<String> captureNames(final Project project) {
        return project.templates().stream()
                .map(Template::captures)
                .flatMap(List::stream)
                .map(CaptureBinding::name)
                .toList();
    }
}
