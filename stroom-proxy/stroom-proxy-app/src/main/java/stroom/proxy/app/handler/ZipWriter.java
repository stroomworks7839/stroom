package stroom.proxy.app.handler;

import stroom.meta.api.AttributeMap;
import stroom.meta.api.AttributeMapUtil;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ZipWriter implements AutoCloseable {

    private final ZipArchiveOutputStream zipArchiveOutputStream;
    private final byte[] buffer;

    public ZipWriter(final Path path, final byte[] buffer) throws IOException {
        this(new ZipArchiveOutputStream(new BufferedOutputStream(Files.newOutputStream(path))), buffer);
    }

    public ZipWriter(final OutputStream outputStream, final byte[] buffer) {
        this(new ZipArchiveOutputStream(new BufferedOutputStream(outputStream)), buffer);
    }

    public ZipWriter(final ZipArchiveOutputStream zipArchiveOutputStream,
                     final byte[] buffer) {
        this.zipArchiveOutputStream = zipArchiveOutputStream;
        this.buffer = buffer;
    }

    public void writeDir(final String name) throws IOException {
        zipArchiveOutputStream.putArchiveEntry(new ZipArchiveEntry(name));
        zipArchiveOutputStream.closeArchiveEntry();
    }

    public void writeAttributeMap(final String name,
                                  final AttributeMap attributeMap) throws IOException {
        zipArchiveOutputStream.putArchiveEntry(new ZipArchiveEntry(name));
        try {
            AttributeMapUtil.write(attributeMap, zipArchiveOutputStream);
        } finally {
            zipArchiveOutputStream.closeArchiveEntry();
        }
    }

    public long writeString(final String name,
                            final String string) throws IOException {
        return writeStream(
                new ZipArchiveEntry(name),
                new ByteArrayInputStream(string.getBytes(StandardCharsets.UTF_8)));
    }

    public long writeStream(final String name,
                            final InputStream inputStream) throws IOException {
        return writeStream(new ZipArchiveEntry(name), inputStream);
    }

    private long writeStream(final ZipArchiveEntry zipArchiveEntry,
                             final InputStream inputStream) throws IOException {
        zipArchiveOutputStream.putArchiveEntry(zipArchiveEntry);
        try {
            return TransferUtil.transfer(inputStream, zipArchiveOutputStream, buffer);
        } finally {
            zipArchiveOutputStream.closeArchiveEntry();
        }
    }

    @Override
    public void close() throws IOException {
        zipArchiveOutputStream.close();
    }
}
