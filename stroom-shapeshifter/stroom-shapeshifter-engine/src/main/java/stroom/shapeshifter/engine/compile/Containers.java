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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.engine.config.OutputNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Which instructions hold bodies, said once.
 *
 * <p>Three walks look for something anywhere inside a body — the patterns to intern, the
 * templates referred to — and each used to carry its own list of container instructions, with
 * a {@code default} arm for the rest. Twice that list was found to stop short of an iteration's
 * body (design 27 phase 1 audits), because a {@code default} arm cannot tell a leaf from a
 * container the author forgot. This switch is exhaustive: an instruction added to the
 * vocabulary is a compile error here until it says whether it holds bodies.
 */
final class Containers {

    private Containers() {
    }

    /** The bodies nested directly inside an instruction; empty for a leaf. */
    static List<List<OutputNode>> bodies(final OutputNode node) {
        return switch (node) {
            case OutputNode.If value -> List.of(value.then());
            case OutputNode.Choose value -> choose(value);
            case OutputNode.Switch value -> switchBodies(value);
            case OutputNode.Variable value -> List.of(value.body());
            case OutputNode.Element value -> List.of(value.body());
            case OutputNode.Attribute value -> List.of(value.body());
            case OutputNode.ForEach value -> List.of(value.body());
            case OutputNode.ForEachGroup value -> List.of(value.body());
            case OutputNode.Abs ignored -> List.of();
            case OutputNode.Add ignored -> List.of();
            case OutputNode.Append ignored -> List.of();
            case OutputNode.ApplyTemplates ignored -> List.of();
            case OutputNode.Avg ignored -> List.of();
            case OutputNode.Call ignored -> List.of();
            case OutputNode.CallTemplate ignored -> List.of();
            case OutputNode.Ceiling ignored -> List.of();
            case OutputNode.Contains ignored -> List.of();
            case OutputNode.Count ignored -> List.of();
            case OutputNode.DistinctValues ignored -> List.of();
            case OutputNode.Divide ignored -> List.of();
            case OutputNode.EmitError ignored -> List.of();
            case OutputNode.EndsWith ignored -> List.of();
            case OutputNode.Floor ignored -> List.of();
            case OutputNode.FormatDate ignored -> List.of();
            case OutputNode.FormatNumber ignored -> List.of();
            case OutputNode.Key ignored -> List.of();
            case OutputNode.KeyGet ignored -> List.of();
            case OutputNode.LowerCase ignored -> List.of();
            case OutputNode.Max ignored -> List.of();
            case OutputNode.Min ignored -> List.of();
            case OutputNode.Mod ignored -> List.of();
            case OutputNode.Multiply ignored -> List.of();
            case OutputNode.Namespace ignored -> List.of();
            case OutputNode.NormalizeSpace ignored -> List.of();
            case OutputNode.Number ignored -> List.of();
            case OutputNode.ParseDate ignored -> List.of();
            case OutputNode.Replace ignored -> List.of();
            case OutputNode.Round ignored -> List.of();
            case OutputNode.Sequence ignored -> List.of();
            case OutputNode.StartsWith ignored -> List.of();
            case OutputNode.StringJoin ignored -> List.of();
            case OutputNode.StringLength ignored -> List.of();
            case OutputNode.Substring ignored -> List.of();
            case OutputNode.SubstringAfter ignored -> List.of();
            case OutputNode.SubstringBefore ignored -> List.of();
            case OutputNode.Subtract ignored -> List.of();
            case OutputNode.Sum ignored -> List.of();
            case OutputNode.Text ignored -> List.of();
            case OutputNode.Tokenize ignored -> List.of();
            case OutputNode.Translate ignored -> List.of();
            case OutputNode.Trim ignored -> List.of();
            case OutputNode.UpperCase ignored -> List.of();
            case OutputNode.ValueMap ignored -> List.of();
            case OutputNode.ValueOf ignored -> List.of();
        };
    }

    private static List<List<OutputNode>> choose(final OutputNode.Choose choose) {
        final List<List<OutputNode>> bodies = new ArrayList<>(choose.when().size() + 1);
        for (final OutputNode.WhenBranch branch : choose.when()) {
            bodies.add(branch.body());
        }
        bodies.add(choose.otherwise());
        return bodies;
    }

    private static List<List<OutputNode>> switchBodies(final OutputNode.Switch value) {
        final List<List<OutputNode>> bodies = new ArrayList<>(value.cases().size() + 1);
        for (final OutputNode.SwitchCase switchCase : value.cases()) {
            bodies.add(switchCase.body());
        }
        bodies.add(value.defaultBody());
        return bodies;
    }
}
