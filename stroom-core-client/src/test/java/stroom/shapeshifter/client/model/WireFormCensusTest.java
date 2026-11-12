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

package stroom.shapeshifter.client.model;

import stroom.shapeshifter.client.component.InstructionEditController;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.Holder;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.RefExpression.RefPart;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.json.ProjectJson;

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

    /**
     * What still opens as JSON. **Zero since 2026-11-12**, when the last transform got a form
     * (design 46 §8.3): the wire form is now the editor's escape for a construct the model
     * grows and the forms have not caught up with, and not somewhere an author is sent for
     * ordinary work.
     *
     * <p>What it was. It was 0 while 504 translates opened as the wire form, because
     * this counted only whether the form could <i>spell</i> an instruction's references and
     * never whether the form offers that kind at all: a transform is not in
     * {@link Instructions#FORM_KINDS}, so `InstructionEditController` shows it as its wire form
     * whatever its references say. Corrected 2026-11-12 to ask both questions, which is what the
     * method's name always claimed.
     *
     * <p>It may go down and not up, so the remaining transforms are a count rather than an
     * impression: this is design 18 Q20's work, measured.
     */
    private static final int WIRE_FORM_CEILING = 0;

    @Test
    void everyInstructionOpensAsAFormRatherThanAsJson() throws IOException {
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
            // Both halves of "opens as a form": the kind has one, and the form can spell what
            // the node holds.
            if (Instructions.FORM_KINDS.contains(Instructions.kind(node))
                && InstructionEditController.spellable(node)) {
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
                .as("instructions that open as the wire form rather than a form. A rise is a "
                    + "construct that has slipped; a fall is a form someone has written, and the "
                    + "ceiling comes down with it")
                .isLessThanOrEqualTo(WIRE_FORM_CEILING);
    }

    @Test
    void aTranslateRoundTripsThroughItsForm() throws IOException {
        // The one transform with a form (design 46 §8). Its replacements are two parallel lists
        // shown as one line each, and the corpus's own values include a tab, a newline and a
        // return — which a text area cannot hold, so they are spelt. If that spelling does not
        // come back exactly, pressing OK rewrites 504 of the corpus's instructions.
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
                if (node instanceof OutputNode.Translate translate) {
                    assertThat(InstructionEditController.spellable(translate))
                            .as("%s in %s should open as a form", translate.name(), config)
                            .isTrue();
                    assertThat(InstructionEditController.readReplacements(
                            InstructionEditController.replacements(translate)))
                            .as("the replacements of %s in %s", translate.name(), config)
                            .isEqualTo(List.of(translate.from(), translate.to()));
                    checked++;
                }
            }
        }
        // 276 over this test's own scope, which is narrower than the whole fixture tree: enough
        // that a translate going unchecked would show here rather than passing vacuously.
        assertThat(checked).as("the corpus's translates").isGreaterThan(250);
    }

    @Test
    void theOneShapeTransformsRoundTripThroughTheirForm() throws IOException {
        // Eight kinds, one form (design 46 §8.2). What matters is the name: 42 of the corpus's
        // 55 trims have none, because a transform without one writes its result straight out,
        // and a form that lost that would turn every one of them into a binding.
        int checked = 0;
        int nameless = 0;
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
                if (!SimpleTransforms.isSimple(node)) {
                    continue;
                }
                assertThat(InstructionEditController.spellable(node))
                        .as("%s in %s should open as a form", Instructions.kind(node), config)
                        .isTrue();
                final List<RefExpression> selects = SimpleTransforms.selectsOf(node);
                final String name = SimpleTransforms.nameOf(node);
                // Through the text the form holds, not straight back into build(): the spelling
                // and the reading of it are the part that can go wrong, and feeding the model
                // to itself would pass whatever they did.
                final StringBuilder lines = new StringBuilder();
                for (final RefExpression select : selects) {
                    if (lines.length() > 0) {
                        lines.append('\n');
                    }
                    lines.append(Instructions.spell(select));
                }
                assertThat(SimpleTransforms.build(Instructions.kind(node),
                        InstructionEditController.selects(lines.toString()), name))
                        .as("%s in %s", Instructions.kind(node), config)
                        .isEqualTo(node);
                assertThat(SimpleTransforms.arityHolds(node))
                        .as("%s in %s takes %s selects", Instructions.kind(node), config,
                                SimpleTransforms.arity(Instructions.kind(node)))
                        .isTrue();
                if (name == null) {
                    nameless++;
                }
                checked++;
            }
        }
        assertThat(checked).as("the corpus's one-shape transforms").isGreaterThan(50);
        assertThat(nameless).as("those that write their result straight out").isGreaterThan(30);
    }

    @Test
    void aTransformTheFormWouldSaveWrongStaysOnTheWireForm() {
        final List<RefExpression> one = List.of(ProjectJson.readRefOrName("a"));
        final List<RefExpression> two = List.of(ProjectJson.readRefOrName("a"),
                ProjectJson.readRefOrName("b"));
        // The arities BodyCompiler enforces (design/17 §5): a trim reads one thing, a divide
        // reads two, an add folds over one or more. A form that accepted the wrong number would
        // write a configuration that fails to compile the next time it is read.
        assertThat(SimpleTransforms.arity("trim")).isEqualTo(SimpleTransforms.Arity.ONE);
        assertThat(SimpleTransforms.arity("divide")).isEqualTo(SimpleTransforms.Arity.TWO);
        assertThat(SimpleTransforms.arity("add")).isEqualTo(SimpleTransforms.Arity.FOLD);
        assertThat(InstructionEditController.spellable(
                new OutputNode.Trim(two, "t"))).isFalse();
        assertThat(InstructionEditController.spellable(
                new OutputNode.Divide(one, "d"))).isFalse();
        assertThat(InstructionEditController.spellable(
                new OutputNode.Divide(two, "d"))).isTrue();
        assertThat(InstructionEditController.spellable(
                new OutputNode.Add(two, "a"))).isTrue();
        // A name that is present but empty would come back as no name, turning a binding into a
        // straight write — the inversion §8.1 refused for translate.
        assertThat(InstructionEditController.spellable(
                new OutputNode.Trim(one, ""))).isFalse();
        assertThat(InstructionEditController.spellable(
                new OutputNode.Trim(one, null))).isTrue();
    }

    @Test
    void aReplacementKeepsTheWhitespaceInItsValues() {
        // The corpus cannot catch this: all 504 of its translates use the XML escaping set and
        // not one has a value edged with a space. `replace "," with ", "` is the ordinary case
        // that does, and trimming the lines would have deleted the space on the way back —
        // changing what the configuration does rather than refusing to.
        final OutputNode.Translate spaces = new OutputNode.Translate(
                List.of(ProjectJson.readRefOrName("line")),
                List.of(",", "\n", " x "),
                List.of(", ", " ", "  "),
                "spaced");
        assertThat(InstructionEditController.spellable(spaces)).isTrue();
        assertThat(InstructionEditController.readReplacements(
                InstructionEditController.replacements(spaces)))
                .isEqualTo(List.of(spaces.from(), spaces.to()));
    }

    @Test
    void aTranslateTheFormCouldNotSaveStaysOnTheWireForm() {
        // Both are legal in the model and run in the engine: a nameless transform writes its
        // result straight out, and an empty from list passes the value through. The form says
        // neither, so it must not offer itself — a dialog whose OK can never succeed leaves the
        // author inventing a name, which turns a write into a binding.
        final List<RefExpression> select = List.of(ProjectJson.readRefOrName("line"));
        assertThat(InstructionEditController.spellable(new OutputNode.Translate(
                select, List.of("a"), List.of("b"), null))).isFalse();
        assertThat(InstructionEditController.spellable(new OutputNode.Translate(
                select, List.of(), List.of(), "named"))).isFalse();
        assertThat(InstructionEditController.spellable(new OutputNode.Translate(
                select, List.of("a"), List.of("b"), "named"))).isTrue();
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
        if (node instanceof OutputNode.StringJoin j) {
            return j.select();
        } else if (node instanceof OutputNode.Tokenize t) {
            return t.select();
        } else if (node instanceof OutputNode.SubstringBefore b) {
            return b.select();
        } else if (node instanceof OutputNode.SubstringAfter a) {
            return a.select();
        } else if (node instanceof OutputNode.StartsWith w) {
            return w.select();
        } else if (node instanceof OutputNode.EndsWith w) {
            return w.select();
        } else if (node instanceof OutputNode.Contains c) {
            return c.select();
        } else if (node instanceof OutputNode.FormatNumber f) {
            return f.select();
        } else if (node instanceof OutputNode.Replace r) {
            return r.select();
        } else if (node instanceof OutputNode.Substring sub) {
            return sub.select();
        } else if (node instanceof OutputNode.FormatDate f) {
            return f.select();
        } else if (node instanceof OutputNode.ParseDate d) {
            return d.select();
        } else if (node instanceof OutputNode.Decode d) {
            return d.select();
        } else if (node instanceof OutputNode.Call c) {
            return c.select();
        } else if (node instanceof OutputNode.Translate t) {
            return t.select();
        }
        if (SimpleTransforms.isSimple(node)) {
            // The eight the shared form offers: their selects are spellings the form shows, so
            // they belong to the invariant this file exists for.
            return SimpleTransforms.selectsOf(node);
        }
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
            // The sort keys are fields of the form too, so they are held to the same round trip.
            final List<RefExpression> found = new ArrayList<>();
            found.add(value.select());
            value.sort().forEach(key -> found.add(key.by()));
            return found;
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
