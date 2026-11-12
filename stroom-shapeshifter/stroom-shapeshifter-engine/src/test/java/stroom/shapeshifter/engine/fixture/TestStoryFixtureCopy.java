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

package stroom.shapeshifter.engine.fixture;

import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.ProjectJson;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tripwire on `strict_kv`, which is copied outside this module.
 *
 * <p>`ShapeshifterEditorStories` — the workbench stories that drive Stroom's real Shapeshifter
 * editor — holds this fixture's project as a Java constant, because a story runs in a browser and
 * cannot read another module's test resource. Design 46 §3.5 accepted the copy on the condition
 * that drift be caught by a test rather than trusted not to happen, and §3 then built the
 * walkthrough's keystone assertion on it: the story drives the UI, captures what the editor saved,
 * and compares it with that constant. A constant that no longer matches this fixture is a
 * keystone that pins the wrong configuration.
 *
 * <p>The guard lives here, in the module that owns the fixture, rather than beside the stories:
 * the workbench's own JUnit tests are deliberately excluded from the build
 * (`enabled = project.hasProperty('workbenchUnitTests')`), so a test written there would never
 * run. Here it runs on every build, which is the whole point of it.
 *
 * <p>It is a hash rather than a second copy of the configuration, so that this file does not
 * become the third place one project is written down. The hash is taken over the **parsed and
 * re-printed model**, not the file's bytes, so reformatting the fixture or reordering its keys
 * does not trip it and a change of substance does. When it trips, the failure prints the
 * configuration to carry across.
 *
 * <p><b>What it does not catch:</b> an edit to the stories' constant alone. The two sides of the
 * copy are in modules that cannot see one another, so this guards the side that owns the
 * configuration. The other side is guarded by the stories themselves, which assert the editor's
 * screen and the walkthrough's saved project against this configuration and fail when the
 * constant stops describing it.
 */
class TestStoryFixtureCopy {

    private static final String FIXTURE = "projects/strict_kv/project.json";

    /// The canonical model of `strict_kv` as the stories were written against it.
    private static final String EXPECTED_DIGEST = "65b0923f47e4c543";

    @Test
    void strictKvHasNotDriftedFromTheCopyTheWorkbenchStoriesHold() {
        final Project project = ProjectJson.readProject(
                JsonText.parse(FixtureLedger.text(FIXTURE)));
        final String canonical = JsonText.print(ProjectJson.writeProject(project));

        assertThat(digest(canonical))
                .as("""
                        %s has changed. The workbench stories hold a copy of it in \
                        ShapeshifterEditorStories.PROJECT, and design 46's rung 1 walkthrough \
                        compares what the editor saved against that copy, so the two must say \
                        the same thing.

                        Update the constant to this configuration and this digest to the one \
                        reported above:

                        %s""", FIXTURE, canonical)
                .isEqualTo(EXPECTED_DIGEST);
    }

    /// The first eight bytes of the SHA-256, as hex: short enough to read in a diff, and this is
    /// a tripwire rather than a security boundary.
    private static String digest(final String text) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
