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

package stroom.shapeshifter.ai.doc;

import stroom.docstore.impl.Serialiser2FactoryImpl;
import stroom.docstore.shared.DocDataType;
import stroom.importexport.api.ByteArrayImportExportAsset;
import stroom.importexport.api.ImportExportDocument;
import stroom.shapeshifter.shared.LearningPlan;
import stroom.shapeshifter.shared.PlanExample;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.Template;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestShapeshifterAiSerialiser {

    private final ShapeshifterAiSerialiser serialiser = new ShapeshifterAiSerialiser(new Serialiser2FactoryImpl());

    @Test
    void aDocumentSavedWhenThePlanWasTheDialogueReadsAsItWasWritten() throws IOException {
        final LearningPlan plan = LearningPlan.of(PlanExample.TARGET_FIRST)
                .withTemplates(Map.of(Template.CHAIN, "Pick: ${elements}\n${sample}"));
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder().uuid("d").name("door").plan(plan).build();
        final ImportExportDocument written = serialiser.write(doc);
        final String json = new String(written.getExtAssetData("meta"), StandardCharsets.UTF_8);
        assertThat(json).contains("\"plan\"");

        written.removeExtAsset("meta");
        written.addExtAsset(new ByteArrayImportExportAsset("meta", DocDataType.JSON,
                json.replace("\"plan\"", "\"dialogue\"").getBytes(StandardCharsets.UTF_8)));

        final ShapeshifterAiDoc read = serialiser.read(written);
        assertThat(read.getPlan()).isEqualTo(plan);
        assertThat(serialiser.read(serialiser.write(doc)).getPlan()).isEqualTo(plan);
    }

    @Test
    void aDocumentSavedWithAPresetReadsAsThatExamplesSteps() throws IOException {
        // Before A34 the section carried a preset in place of steps.
        final ImportExportDocument written = serialiser.write(
                ShapeshifterAiDoc.builder().uuid("d").name("door").build());
        final ObjectNode json = (ObjectNode) JsonUtil.getMapper().readTree(written.getExtAssetData("meta"));
        json.remove("plan");
        final ObjectNode legacy = json.putObject("dialogue");
        legacy.put("preset", "TARGET_FIRST");
        legacy.putObject("templates").put("CHAIN", "Pick");
        written.removeExtAsset("meta");
        written.addExtAsset(new ByteArrayImportExportAsset("meta", DocDataType.JSON,
                JsonUtil.getMapper().writeValueAsBytes(json)));

        final LearningPlan read = serialiser.read(written).getPlan();
        assertThat(read.getSteps()).isEqualTo(PlanExample.TARGET_FIRST.steps());
        assertThat(read.getTemplates()).containsEntry(Template.CHAIN, "Pick");
    }
}
