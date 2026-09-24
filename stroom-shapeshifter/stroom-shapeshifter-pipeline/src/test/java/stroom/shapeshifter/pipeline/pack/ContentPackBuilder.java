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
import stroom.shapeshifter.engine.ds3.Ds3Migration;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.util.json.JsonUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A Stroom content pack of Shapeshifter projects, built from the engine's fixtures (design 44
 * §5r). Import it and every project is there to open, run and take apart — which is what the
 * fixtures are for, and what they were not reachable for from the editor.
 *
 * <p>Each project's own sample input is carried in the document (§5q), so an imported project
 * runs without the importer finding data for it. A fixture whose input is binary cannot: the
 * field is text, and pretending otherwise would ship a corrupted sample. Those arrive with the
 * project and no sample, and say so in their description.
 *
 * <p>The layout is what {@code ImportExportSerializerImplV2} writes, because import reads what
 * export writes: per document a {@code .node} of properties, a {@code .meta} of the document's
 * own JSON, and the {@code .json} extension asset that is the project. A folder is a
 * {@code .node} beside the directory it names. Names are cleaned by the same rule the export
 * uses — anything but a letter or digit becomes an underscore.
 *
 * <p>Identifiers are derived from the fixture's name rather than drawn fresh, so importing a
 * second time updates the projects instead of duplicating them.
 */
public final class ContentPackBuilder {

    private static final Path FIXTURES = Paths.get(
            "..", "stroom-shapeshifter-engine", "src", "test", "resources", "fixtures");
    private static final String ROOT = "Shapeshifter demos";
    private static final String TYPE = "Shapeshifter";
    private static final String FOLDER = "Folder";

    private final List<String> notes = new ArrayList<>();
    private final ZipOutputStream zip;

    private ContentPackBuilder(final ZipOutputStream zip) {
        this.zip = zip;
    }

    public static void main(final String[] args) throws Exception {
        final Path out = Paths.get(args.length > 0
                ? args[0]
                : "build/content-pack/shapeshifter-demos.zip");
        Files.createDirectories(out.getParent());
        try (final ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            final ContentPackBuilder builder = new ContentPackBuilder(zip);
            builder.build();
        }
        System.out.println("Wrote " + out.toAbsolutePath());
    }

    private void build() throws IOException {
        final String root = folder("", "", ROOT);
        final int projects = projects(root);
        final int natives = natives(root);
        final int legacy = legacy(root);
        System.out.println(projects + " projects, " + natives + " native, " + legacy + " migrated from DS3");
        notes.forEach(System.out::println);
    }

    /** {@code fixtures/projects/<name>/project.json}, with {@code input.txt} or {@code input.xml}. */
    private int projects(final String root) throws IOException {
        final String dirOf = folder(root, ROOT, "projects");
        int count = 0;
        for (final Path dir : sorted(FIXTURES.resolve("projects"))) {
            final Path config = dir.resolve("project.json");
            if (!Files.exists(config)) {
                continue;
            }
            final String name = dir.getFileName().toString();
            count += document(dirOf, ROOT + "/projects", name, Files.readString(config),
                    sampleOf(dir.resolve("input.txt"), dir.resolve("input.xml"), dir.resolve("input.bin")),
                    "The engine's " + name + " fixture.");
        }
        return count;
    }

    /** {@code fixtures/native/<name>/project.json}, whose input is the legacy fixture of the same name. */
    private int natives(final String root) throws IOException {
        final String dirOf = folder(root, ROOT, "native");
        int count = 0;
        for (final Path dir : sorted(FIXTURES.resolve("native"))) {
            final Path config = dir.resolve("project.json");
            if (!Files.exists(config)) {
                continue;
            }
            final String name = dir.getFileName().toString();
            count += document(dirOf, ROOT + "/native", name, Files.readString(config),
                    sampleOf(FIXTURES.resolve("legacy").resolve(name + ".in")),
                    "The " + name + " fixture written natively, over the legacy input.");
        }
        return count;
    }

    /** {@code fixtures/legacy/<name>.ds3.xml}, migrated, with the same fixture's input. */
    private int legacy(final String root) throws IOException {
        final String dirOf = folder(root, ROOT, "ds3");
        int count = 0;
        for (final Path config : sorted(FIXTURES.resolve("legacy"))) {
            final String file = config.getFileName().toString();
            if (!file.endsWith(".ds3.xml")) {
                continue;
            }
            final String name = file.substring(0, file.length() - ".ds3.xml".length());
            final Project project;
            try {
                project = Ds3Migration.importXml(Files.readString(config));
            } catch (final RuntimeException e) {
                // One fixture exists to be refused, and the migration refuses it. That is the
                // fixture passing, not the pack failing.
                notes.add("  skipped " + name + ": " + e.getMessage());
                continue;
            }
            count += document(dirOf, ROOT + "/ds3", name, JsonText.printPretty(ProjectJson.writeProject(project)),
                    sampleOf(FIXTURES.resolve("legacy").resolve(name + ".in")),
                    "Migrated from the DS3 configuration of the " + name + " fixture.");
        }
        return count;
    }

    /** The first of these that exists and is text; a binary input is no sample at all. */
    private String sampleOf(final Path... candidates) throws IOException {
        for (final Path candidate : candidates) {
            if (!Files.exists(candidate)) {
                continue;
            }
            // .bin is binary by name, and sniffing it is worse than useless: a length-prefixed
            // record with small fields can hold no NUL and decode as UTF-8 while being nothing of
            // the kind, and the sample would then not be the fixture's bytes. Three of the seven
            // binary fixtures passed the sniff before the name was trusted over the content.
            final String file = candidate.getFileName().toString();
            final byte[] bytes = Files.readAllBytes(candidate);
            if (!file.endsWith(".bin") && isText(bytes)) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            notes.add("  " + candidate.getParent().getFileName() + ": input is binary, so no sample");
            return null;
        }
        return null;
    }

    /** Text enough to paste: no NUL, and decodable as UTF-8 without loss. */
    private static boolean isText(final byte[] bytes) {
        for (final byte b : bytes) {
            if (b == 0) {
                return false;
            }
        }
        final String decoded = new String(bytes, StandardCharsets.UTF_8);
        return java.util.Arrays.equals(bytes, decoded.getBytes(StandardCharsets.UTF_8));
    }

    private int document(final String dir, final String path, final String name, final String project,
                         final String sample, final String description) throws IOException {
        // Parsed before it is packed. A fixture the reader cannot read is one the editor cannot
        // open — parquet_cities names a match kind design 38 retired, and the ledger has it
        // SKIPPED for exactly that reason — and shipping it would be shipping a document that
        // fails the moment it is selected.
        try {
            ProjectJson.readProject(JsonText.parse(project));
        } catch (final RuntimeException e) {
            notes.add("  skipped " + name + ": its configuration does not read (" + e.getMessage() + ")");
            return 0;
        }
        final String uuid = idOf(TYPE + ":" + path + "/" + name);
        final ShapeshifterDoc doc = ShapeshifterDoc.builder()
                .uuid(uuid)
                .name(name)
                // Every document has one, and a real export carries it: StoreImpl.createDocument
                // stamps a version, and the import writes what the meta holds rather than making
                // one up. Derived from the identifier so a rebuilt pack is the same pack.
                .version(idOf("version:" + uuid))
                .description(sample == null
                        ? description + " Its input is binary, so it carries no sample: choose one to run it."
                        : description)
                .sampleText(sample)
                .build();
        final String prefix = dir + filePrefix(name, TYPE, uuid);
        write(prefix + ".node", nodeProperties(uuid, TYPE, name, path + "/" + name));
        // The document's own JSON without its data, exactly as ShapeshifterSerialiser writes it:
        // the project travels as the json extension asset beside it.
        write(prefix + ".meta", JsonUtil.writeValueAsString(doc));
        write(prefix + ".json", project);
        return 1;
    }

    /**
     * A folder: its node file, and the directory its children go in. The directory is named for
     * the <b>file prefix</b>, not for the folder — {@code ImportExportSerializerImplV2} resolves a
     * folder's directory with {@code createFilePrefix} and, reading, maps a directory back to its
     * folder by stripping {@code .node} from the node file's name. A directory named for the
     * folder is the version 1 convention and the import refuses it: <i>"Node file for folder
     * 'Shapeshifter demos' was not found"</i>.
     *
     * @return the directory every child of this folder is written into, with its separator
     */
    private String folder(final String dir, final String path, final String name) throws IOException {
        final String uuid = idOf(FOLDER + ":" + path + "/" + name);
        final String prefix = filePrefix(name, FOLDER, uuid);
        write(dir + prefix + ".node", nodeProperties(uuid, FOLDER, name, path + "/" + name));
        return dir + prefix + "/";
    }

    /** {@code ImportExportFileNameUtil.createFilePrefix}: a safe name, the type, the uuid. */
    private static String filePrefix(final String name, final String type, final String uuid) {
        return name.replaceAll("[^A-Za-z0-9]", "_") + "." + type + "." + uuid;
    }

    private static String nodeProperties(final String uuid, final String type, final String name,
                                         final String path) throws IOException {
        final Properties props = new Properties();
        props.setProperty("uuid", uuid);
        props.setProperty("type", type);
        props.setProperty("name", name);
        props.setProperty("version", "V2");
        // As the export writes it: a delimiter before every node on the way down, this one
        // included, so the root's own path and its children's are spelt the same way.
        props.setProperty("path", path.startsWith("/")
                ? path
                : "/" + path);
        final java.io.StringWriter writer = new java.io.StringWriter();
        props.store(writer, null);
        // Properties.store stamps a date comment, which would make every build a different file.
        return writer.toString().lines()
                .filter(line -> !line.startsWith("#"))
                .sorted()
                .reduce("", (a, b) -> a + b + "\n");
    }

    /** The same fixture always gets the same id, so a second import updates rather than duplicates. */
    private static String idOf(final String key) {
        return UUID.nameUUIDFromBytes(("shapeshifter-content-pack:" + key)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void write(final String entry, final String content) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        final OutputStream out = zip;
        out.write(content.getBytes(StandardCharsets.UTF_8));
        if (!content.endsWith("\n")) {
            out.write('\n');
        }
        zip.closeEntry();
    }

    private static List<Path> sorted(final Path dir) throws IOException {
        try (final var paths = Files.list(dir)) {
            return paths.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
    }
}
