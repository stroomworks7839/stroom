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

package stroom.shapeshifter.pipeline.pack;

import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.ProjectJson;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pack is read back the way a Stroom import reads it — the properties parsed as properties,
 * the meta as a {@link ShapeshifterDoc}, the project as a {@link Project} — because everything
 * in it is written by hand and a pack that only looks right is worth nothing.
 */
class ContentPackBuilderTest {

    @Test
    void everyDocumentInThePackReadsBackAsOne(@TempDir final Path dir) throws Exception {
        final Path zip = dir.resolve("pack.zip");
        ContentPackBuilder.main(new String[]{zip.toString()});

        final Map<String, byte[]> entries = new HashMap<>();
        try (final ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }

        int documents = 0;
        int withSamples = 0;
        for (final Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".node")) {
                continue;
            }
            final Properties node = new Properties();
            node.load(new java.io.ByteArrayInputStream(entry.getValue()));
            assertThat(node.getProperty("version")).isEqualTo("V2");
            assertThat(node.getProperty("uuid")).isNotBlank();
            assertThat(node.getProperty("name")).isNotBlank();
            if (!"Shapeshifter".equals(node.getProperty("type"))) {
                continue;
            }
            documents++;

            // The two files the document is made of must sit beside its node file.
            final String prefix = entry.getKey().substring(0, entry.getKey().length() - ".node".length());
            final byte[] meta = entries.get(prefix + ".meta");
            final byte[] project = entries.get(prefix + ".json");
            assertThat(meta).as(prefix + ": meta").isNotNull();
            assertThat(project).as(prefix + ": project").isNotNull();

            final ShapeshifterDoc doc = JsonUtil.readValue(
                    new String(meta, StandardCharsets.UTF_8), ShapeshifterDoc.class);
            assertThat(doc.getUuid()).isEqualTo(node.getProperty("uuid"));
            assertThat(doc.getName()).isEqualTo(node.getProperty("name"));
            assertThat(doc.getType()).isEqualTo("Shapeshifter");
            // A document without one is written to the store with a null version.
            assertThat(doc.getVersion()).as(prefix + ": version").isNotBlank();
            // The stream reference is never packed; the sample text is the whole point of it.
            assertThat(doc.getSample()).isNull();
            if (doc.getSampleText() != null) {
                withSamples++;
                assertThat(doc.getSampleText()).isNotEmpty();
            }

            final Project read = ProjectJson.readProject(
                    JsonText.parse(new String(project, StandardCharsets.UTF_8)));
            assertThat(read.templates()).as(prefix + ": templates").isNotEmpty();
        }

        // Every fixture that has one, and the binary ones honestly without.
        assertThat(documents).isGreaterThan(60);
        assertThat(withSamples).isGreaterThan(50);
    }

    @Test
    void everyDirectoryIsNamedForTheNodeFileThatDescribesIt(@TempDir final Path dir) throws Exception {
        // The invariant the import actually enforces, and the one the first pack broke: a folder's
        // directory is named for its file prefix, and reading maps a directory back to its folder
        // by stripping ".node" from a sibling node file's name. A directory without that sibling
        // fails the import with "Node file for folder '<name>' was not found".
        final Path zip = dir.resolve("pack.zip");
        ContentPackBuilder.main(new String[]{zip.toString()});
        final java.util.Set<String> entries = names(zip);

        for (final String entry : entries) {
            final String[] segments = entry.split("/");
            final StringBuilder parent = new StringBuilder();
            for (int i = 0; i < segments.length - 1; i++) {
                assertThat(entries)
                        .as("directory '" + segments[i] + "' in '" + entry + "' needs its node file")
                        .contains(parent + segments[i] + ".node");
                parent.append(segments[i]).append("/");
            }
        }
    }

    @Test
    void theSameFixturesGiveTheSameIdentifiers(@TempDir final Path dir) throws Exception {
        // A second import must update the projects rather than duplicate them, which it only
        // does if the pack names them the same way every time it is built.
        final Path first = dir.resolve("first.zip");
        final Path second = dir.resolve("second.zip");
        ContentPackBuilder.main(new String[]{first.toString()});
        ContentPackBuilder.main(new String[]{second.toString()});
        assertThat(names(first)).isEqualTo(names(second));
    }

    private static java.util.Set<String> names(final Path zip) throws Exception {
        final java.util.Set<String> names = new java.util.TreeSet<>();
        try (final ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
