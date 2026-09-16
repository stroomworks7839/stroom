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

package stroom.shapeshifter.engine.fixture;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.file.SeekableByteArrayInput;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Random;

/**
 * The {@code avro_users} container amplified by the Avro Java library, and its expected output
 * read back by the same library — an independent writer and reader for the engine's
 * library-free parse (design 38 §4), as Saxon is for the XSLT catalogue.
 *
 * <p>Deterministic: the same seed writes the same bytes, so the benchmark row and the parity
 * test see one file. Records are written in blocks of a fixed count, which is what makes the
 * container exercise the block template more than the fixture's one block of three does; a
 * block's size in bytes varies with its names, so the length-prefixed take varies too.
 */
public final class AvroContainers {

    /** The fixture's schema, as the container embeds it. */
    private static final Schema USER = new Schema.Parser().parse("""
            {"type":"record","name":"User","fields":[
              {"name":"name","type":"string"},
              {"name":"age","type":"long"},
              {"name":"score","type":"double"},
              {"name":"active","type":"boolean"}]}""");

    private static final String[] NAMES = {
            "Alice", "Bob", "Carol", "Dave", "Eve", "Frank", "Grace", "Heidi", "Ivan", "Judy",
            "Mallory", "Niaj", "Olivia", "Peggy", "Rupert", "Sybil", "Trent", "Victor", "Walter", "Xavier"};

    private AvroContainers() {
    }

    /**
     * @param bytes    the container
     * @param expected what the fixture's configuration writes for it, one {@code user} element
     *                 per record as the library read them back
     */
    public record Amplified(byte[] bytes, String expected) {

    }

    /**
     * A container of {@code users} records in blocks of {@code perBlock}, from a fixed seed.
     */
    public static Amplified amplify(final int users, final int perBlock, final long seed) {
        final byte[] bytes = write(users, perBlock, seed);
        return new Amplified(bytes, readBack(bytes));
    }

    private static byte[] write(final int users, final int perBlock, final long seed) {
        final Random random = new Random(seed);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataFileWriter<GenericRecord> writer = new DataFileWriter<>(new GenericDatumWriter<>(USER))) {
            writer.create(USER, out);
            for (int i = 0; i < users; i++) {
                if (i > 0 && i % perBlock == 0) {
                    writer.sync();
                }
                final GenericRecord user = new GenericData.Record(USER);
                user.put("name", NAMES[random.nextInt(NAMES.length)] + (i % 1000 == 0 ? "" : i));
                user.put("age", 18L + random.nextInt(70));
                user.put("score", random.nextInt(1000) / 100.0);
                user.put("active", random.nextBoolean());
                writer.append(user);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * A container just past {@code targetBytes}, as the benchmark's repeated inputs are just
     * past theirs: one op is the same volume on every row. Sized in two passes because a
     * record's bytes grow with its ordinal — the names carry it — so a small sample under-reads
     * the rate; the second pass corrects with the rate at full size and lands within a block.
     */
    public static Amplified sized(final int targetBytes, final int perBlock, final long seed) {
        final int sample = 1000;
        final int users = targetBytes / (write(sample, perBlock, seed).length / sample);
        final int corrected = (int) ((long) users * targetBytes / write(users, perBlock, seed).length) + perBlock;
        return amplify(corrected, perBlock, seed);
    }

    /** What the fixture's body writes, from the records as the library reads them. */
    private static String readBack(final byte[] bytes) {
        // The fixture's format-number picture, in the root locale as the engine applies it.
        final DecimalFormat score = new DecimalFormat("0.0##", DecimalFormatSymbols.getInstance(Locale.ROOT));
        final StringBuilder expected = new StringBuilder();
        try (DataFileReader<GenericRecord> reader = new DataFileReader<>(
                new SeekableByteArrayInput(bytes), new GenericDatumReader<>(USER))) {
            for (final GenericRecord user : reader) {
                expected.append("<user name=\"").append(user.get("name"))
                        .append("\" age=\"").append(user.get("age"))
                        .append("\" score=\"").append(score.format((Double) user.get("score")))
                        .append("\" active=\"").append(user.get("active"))
                        .append("\"/>\n");
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return expected.toString();
    }
}
