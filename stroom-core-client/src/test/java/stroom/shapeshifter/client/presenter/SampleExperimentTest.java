/*
 * Copyright 2016 Crown Copyright
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

import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.MatchLimits;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The sample's experiment is the subject's match alone, at the root, under the document's source settings. */
class SampleExperimentTest {

    @Test
    void theExperimentKeepsTheMatchAndDropsEverythingThatWouldRunOrHold() {
        final Template rich = Templates.withBody(Templates.withLimits(Templates.withCaptures(
                Templates.withDeclarations(
                        Templates.withMatch(Templates.create("kv", "fields", false),
                                new MatchExpression.Regex("(?<k>\\w+)=(?<v>\\S+)", null, 0)),
                        List.of(new Declaration("k", Declaration.Type.SCALAR, null))),
                List.of(new CaptureBinding("k", new CaptureBinding.CaptureSource.Group(1), null))),
                new MatchLimits(1, 3, null)),
                List.of(new OutputNode.Text("x")));
        final Project project = new Project("p", 3, null, List.of(rich));

        final Project experiment = Templates.experiment(project, rich);

        assertThat(experiment.source()).isEqualTo(project.source());
        assertThat(experiment.templates()).hasSize(1);
        final Template bare = experiment.templates().get(0);
        assertThat(bare.id()).isEqualTo(rich.id());
        assertThat(bare.match()).isEqualTo(rich.match());
        assertThat(bare.consume()).isFalse();
        assertThat(bare.mode()).as("at the root, whatever mode it had").isNull();
        assertThat(bare.guard()).isNull();
        assertThat(bare.matchLimits()).as("no limits: every match").isEqualTo(new MatchLimits(0, -1, null));
        assertThat(bare.declarations()).isEmpty();
        assertThat(bare.captures()).isEmpty();
        assertThat(bare.body()).isEmpty();
        // And it is a project the reader accepts, since that is how it travels.
        assertThat(ProjectText.parse(ProjectText.print(experiment)).templates().get(0).match()).isEqualTo(rich.match());
    }
}
