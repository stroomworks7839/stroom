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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.Holder;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.RefExpression.RefPart;
import stroom.shapeshifter.config.Template;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How many instructions the editor can show as fields, and how many fall to the wire form, over
 * every fixture project (design 44 §5v). A ratchet in the spirit of the fixture ledger: the
 * number that fall to the wire may go down and not up, so modelling a construct is a change
 * someone has to make on purpose, and regressing one fails the build.
 *
 * <p>It also holds the invariant the form rests on: a spelling the form offers must read back as
 * the reference it came from, or pressing OK rewrites the author's configuration.
 */
class WireFormCensusTest {

    private static final Path FIXTURES = Paths.get(
            "..", "stroom-shapeshifter", "stroom-shapeshifter-engine", "src", "test", "resources", "fixtures");

    /** Lower is better; drop it when a construct is modelled. */
    private static final int WIRE_FORM_CEILING = 3;

    @Test
    void mostInstructionsOpenAsAFormRatherThanAsJson() throws IOException {
        final List<OutputNode> all = new ArrayList<>();
        int projects = 0;
        for (final Path config : configs()) {
            final Project project = read(config);
            if (project == null) {
                continue;
            }
            projects++;
            for (final Template template : project.templates()) {
                collect(template.body(), all);
            }
        }

        final Map<String, Integer> wireByKind = new TreeMap<>();
        final Map<String, Integer> whyByShape = new TreeMap<>();
        int spellable = 0;
        for (final OutputNode node : all) {
            if (InstructionEditPresenter.spellable(node)) {
                spellable++;
            } else {
                wireByKind.merge(Instructions.kind(node), 1, Integer::sum);
                whyByShape.merge(whyOf(node), 1, Integer::sum);
            }
        }
        final int wire = all.size() - spellable;

        final StringBuilder report = new StringBuilder()
                .append(all.size()).append(" instructions across ").append(projects)
                .append(" fixture projects\n")
                .append("  ").append(spellable).append(" open as a form\n")
                .append("  ").append(wire).append(" open as the wire form, by kind:\n");
        wireByKind.forEach((kind, count) -> report.append("      ")
                .append(count).append("  ").append(kind).append('\n'));
        report.append("  why, by the part the form cannot spell:\n");
        whyByShape.forEach((shape, count) -> report.append("      ")
                .append(count).append("  ").append(shape).append('\n'));
        System.out.println(report);

        assertThat(all).as("the fixtures should have been read").isNotEmpty();
        assertThat(wire)
                .as("instructions falling to the wire form; model a construct to lower it, "
                    + "and lower the ceiling with it")
                .isLessThanOrEqualTo(WIRE_FORM_CEILING);
    }

    @Test
    void everySpellingTheFormOffersReadsBackToWhatItCameFrom() throws IOException {
        // Over real references rather than invented ones, and over every reference an
        // instruction holds rather than its select alone: a labelled group in a put's key
        // round-trips or it does not, and the narrower walk never asked.
        int checked = 0;
        for (final Path config : configs()) {
            final Project project = read(config);
            if (project == null) {
                continue;
            }
            final List<OutputNode> all = new ArrayList<>();
            for (final Template template : project.templates()) {
                collect(template.body(), all);
            }
            for (final OutputNode node : all) {
                for (final RefExpression ref : refsOf(node)) {
                    if (ref == null) {
                        continue;
                    }
                    final String spelt = Instructions.spell(ref);
                    if (spelt == null) {
                        continue;
                    }
                    checked++;
                    assertThat(Instructions.read(spelt))
                            .as(config + ": " + Instructions.kind(node) + " spelt '" + spelt + "'")
                            .isEqualTo(ref);
                }
            }
        }
        assertThat(checked).as("references checked").isGreaterThan(500);
    }

    /** Why an instruction has no form: the first thing about it the form cannot show. */
    private static String whyOf(final OutputNode node) {
        if (node instanceof final OutputNode.ForEach forEach && !forEach.sort().isEmpty()) {
            return "a for-each with a sort";
        }
        for (final RefExpression ref : refsOf(node)) {
            // Absent is not a reason: an optional reference the configuration leaves out is a
            // blank field (design 44 §5x).
            if (ref == null || Instructions.spell(ref) != null) {
                continue;
            }
            for (final RefPart part : ref.parts()) {
                final RefExpression alone = new RefExpression(List.of(part));
                if (Instructions.spell(alone) == null) {
                    return shapeOf(alone) + (ref.parts().size() == 1
                            ? ", alone"
                            : ", within a sequence");
                }
            }
            return "a reference, otherwise";
        }
        return "the kind itself is not modelled";
    }

    /** Every reference an instruction holds, in the order the form would show them. */
    private static List<RefExpression> refsOf(final OutputNode node) {
        if (node instanceof final OutputNode.ValueOf value) {
            return refs(value.select());
        }
        if (node instanceof final OutputNode.ApplyTemplates apply) {
            final List<RefExpression> found = new ArrayList<>();
            found.add(apply.directive().select());
            apply.directive().withParam().forEach(param -> found.add(param.value()));
            return found;
        }
        if (node instanceof final OutputNode.CallTemplate call) {
            // The form spells a call's parameters, so the walk has to hold them to the same
            // round trip — and to say so when one of them is why the wire form kept the card.
            final List<RefExpression> found = new ArrayList<>();
            call.withParam().forEach(param -> found.add(param.value()));
            return found;
        }
        if (node instanceof final OutputNode.EmitError value) {
            return refs(value.message());
        }
        if (node instanceof final OutputNode.Switch value) {
            return refs(value.select());
        }
        if (node instanceof final OutputNode.ForEach value) {
            return refs(value.select());
        }
        if (node instanceof final OutputNode.ForEachGroup value) {
            return refs(value.select(), value.groupBy());
        }
        if (node instanceof final OutputNode.Append value) {
            return refs(value.target(), value.select());
        }
        if (node instanceof final OutputNode.Insert value) {
            return refs(value.target(), value.position(), value.select());
        }
        if (node instanceof final OutputNode.Put value) {
            return refs(value.target(), value.key(), value.select());
        }
        if (node instanceof final OutputNode.Remove value) {
            return refs(value.target(), value.key());
        }
        if (node instanceof final OutputNode.Clear value) {
            return refs(value.target());
        }
        return List.of();
    }

    /** A list that tolerates the nulls an optional reference leaves. */
    private static List<RefExpression> refs(final RefExpression... found) {
        return Arrays.asList(found);
    }

    /** Why a reference has no field spelling, named by the construct rather than the value. */
    private static String shapeOf(final RefExpression ref) {
        final RefPart part = ref.parts().get(0);
        if (part instanceof final RefPart.Capture capture) {
            if (capture.matchIndex() != null) {
                return "a match-indexed reference";
            }
            if (capture.label() != null) {
                return "a labelled group the form cannot name";
            }
            return "a group of a variable";
        }
        if (part instanceof final RefPart.Accessor accessor) {
            return "accessor " + accessor.kind().spelling()
                   + (accessor.key() != null ? " +key" : "")
                   + (accessor.orElse() != null ? " +default" : "")
                   + (accessor.as() != null ? " +as" : "")
                   + (accessor.of().parts().size() == 1
                           && accessor.of().parts().get(0) instanceof RefPart.Accessor
                                   ? " over another accessor" : "");
        }
        if (part instanceof RefPart.Text) {
            return "literal text";
        }
        return part.getClass().getSimpleName();
    }

    private static Project read(final Path config) throws IOException {
        try {
            return ProjectText.parse(Files.readString(config));
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static List<Path> configs() throws IOException {
        final List<Path> found = new ArrayList<>();
        for (final String set : List.of("projects", "native")) {
            try (Stream<Path> dirs = Files.list(FIXTURES.resolve(set))) {
                dirs.map(dir -> dir.resolve("project.json"))
                        .filter(Files::exists)
                        .forEach(found::add);
            }
        }
        return found;
    }

    /** Every instruction, at every depth: a holder's branches are instructions too. */
    private static void collect(final List<OutputNode> body, final List<OutputNode> into) {
        for (final OutputNode node : body) {
            into.add(node);
            if (node instanceof final Holder holder) {
                for (final List<OutputNode> branch : holder.bodies()) {
                    collect(branch, into);
                }
            }
        }
    }
}
