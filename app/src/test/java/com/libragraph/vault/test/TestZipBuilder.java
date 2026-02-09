package com.libragraph.vault.test;

import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.Buffer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Helper to create in-memory ZIP files for testing.
 *
 * <p>Uses STORED (no compression) to ensure deterministic output.
 */
public class TestZipBuilder {

    private final List<Entry> entries = new ArrayList<>();

    public record Entry(String path, byte[] data) {}

    public TestZipBuilder addFile(String path, String content) {
        entries.add(new Entry(path, content.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    public TestZipBuilder addFile(String path, byte[] data) {
        entries.add(new Entry(path, data));
        return this;
    }

    public TestZipBuilder addDirectory(String path) {
        String dirPath = path.endsWith("/") ? path : path + "/";
        entries.add(new Entry(dirPath, new byte[0]));
        return this;
    }

    /**
     * Adds a nested ZIP file as an entry in this ZIP.
     */
    public TestZipBuilder addNestedZip(String path, TestZipBuilder inner) {
        entries.add(new Entry(path, inner.buildBytes()));
        return this;
    }

    /**
     * Builds the ZIP as a raw byte array.
     */
    public byte[] buildBytes() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                // Use STORED to ensure deterministic, reproducible output
                zos.setMethod(ZipOutputStream.STORED);
                zos.setLevel(0);

                for (Entry entry : entries) {
                    ZipEntry ze = new ZipEntry(entry.path());
                    ze.setMethod(ZipEntry.STORED);
                    ze.setSize(entry.data().length);
                    ze.setCompressedSize(entry.data().length);

                    CRC32 crc = new CRC32();
                    crc.update(entry.data());
                    ze.setCrc(crc.getValue());

                    // Set deterministic timestamps
                    ze.setTime(1704067200000L); // 2024-01-01 00:00:00 UTC

                    zos.putNextEntry(ze);
                    zos.write(entry.data());
                    zos.closeEntry();
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build test ZIP", e);
        }
    }

    /**
     * Builds the ZIP as BinaryData suitable for ObjectStorage.
     */
    public BinaryData buildBinaryData() {
        byte[] bytes = buildBytes();
        try {
            Buffer buf = Buffer.allocate(bytes.length);
            buf.write(ByteBuffer.wrap(bytes));
            buf.position(0);
            return buf;
        } catch (IOException e) {
            throw new RuntimeException("Failed to wrap ZIP as BinaryData", e);
        }
    }
}
