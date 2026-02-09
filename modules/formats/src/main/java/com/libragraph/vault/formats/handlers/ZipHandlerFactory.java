package com.libragraph.vault.formats.handlers;

import com.libragraph.vault.formats.api.*;
import com.libragraph.vault.formats.proto.ZipMetadataProto;
import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.Buffer;
import com.google.protobuf.ByteString;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.compress.archivers.zip.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Format handler for ZIP archives.
 * Priority 200 (higher than Tika's 0) to override default behavior.
 */
@ApplicationScoped
public class ZipHandlerFactory implements FormatHandlerFactory {

    private static final byte[] ZIP_MAGIC = new byte[]{0x50, 0x4B, 0x03, 0x04}; // "PK\u0003\u0004"

    @Override
    public DetectionCriteria getDetectionCriteria() {
        return new DetectionCriteria(
            Set.of("application/zip"),
            Set.of("zip"),
            ZIP_MAGIC,
            0,
            200
        );
    }

    @Override
    public Handler createInstance(BinaryData buffer, FileContext context) {
        return new ZipHandler(buffer, context);
    }

    /**
     * Handler instance for a specific ZIP file.
     */
    private static class ZipHandler implements Handler {
        private final BinaryData buffer;
        private final FileContext context;

        ZipHandler(BinaryData buffer, FileContext context) {
            this.buffer = buffer;
            this.context = context;
        }

        @Override
        public boolean hasChildren() {
            return true;
        }

        @Override
        public boolean isCompressible() {
            return false; // ZIP entries are already compressed
        }

        @Override
        public ContainerCapabilities getCapabilities() {
            return new ContainerCapabilities(
                ContainerCapabilities.ReconstructionTier.TIER_1_RECONSTRUCTABLE,
                true,  // preservesTimestamps
                false, // preservesPermissions (ZIP doesn't store Unix permissions reliably)
                true   // preservesOrder
            );
        }

        @Override
        public Stream<ContainerChild> extractChildren() {
            List<ContainerChild> children = new ArrayList<>();

            // ZipFile requires a file path, so we need to materialize the buffer to a temp file
            Path tempZipFile = null;
            try {
                tempZipFile = Files.createTempFile("vault-zip-", ".zip");

                buffer.position(0);
                try (InputStream in = buffer.inputStream(0)) {
                    Files.copy(in, tempZipFile, StandardCopyOption.REPLACE_EXISTING);
                }

                try (ZipFile zipFile = ZipFile.builder().setFile(tempZipFile.toFile()).get()) {
                    var entries = zipFile.getEntries();
                    while (entries.hasMoreElements()) {
                        ZipArchiveEntry entry = entries.nextElement();

                        Buffer childBuffer;

                        if (entry.isDirectory()) {
                            childBuffer = Buffer.allocate(0);
                        } else {
                            long size = entry.getSize();
                            childBuffer = Buffer.allocate(size);

                            try (InputStream entryStream = zipFile.getInputStream(entry);
                                 OutputStream output = childBuffer.outputStream(0)) {
                                entryStream.transferTo(output);
                            }
                        }

                        Map<String, Object> metadata = extractEntryMetadata(entry);
                        EntryMetadata entryMeta = buildEntryMetadata(entry);

                        children.add(new ContainerChild(
                            entry.getName(),
                            childBuffer,
                            metadata,
                            entryMeta
                        ));
                    }
                }

            } catch (IOException e) {
                throw new RuntimeException("Failed to extract children from ZIP", e);
            } finally {
                if (tempZipFile != null) {
                    try {
                        Files.deleteIfExists(tempZipFile);
                    } catch (IOException e) {
                        // Ignore cleanup errors
                    }
                }
            }

            return children.stream();
        }

        @Override
        public Map<String, Object> extractMetadata() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("format", "ZIP");
            metadata.put("size", buffer.size());

            int entryCount = 0;
            try (InputStream inputStream = buffer.inputStream(0);
                 ZipArchiveInputStream zip = new ZipArchiveInputStream(inputStream)) {

                while (zip.getNextEntry() != null) {
                    entryCount++;
                }

            } catch (IOException e) {
                // Ignore, just won't have entry count
            }

            metadata.put("entryCount", entryCount);
            return metadata;
        }

        @Override
        public String extractText() {
            return null;
        }

        @Override
        public void reconstruct(List<ContainerChild> children, OutputStream output) throws IOException {
            try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(output)) {
                zos.setEncoding("UTF-8");

                for (ContainerChild child : children) {
                    boolean isDirectory = child.path().endsWith("/");

                    ZipArchiveEntry entry = new ZipArchiveEntry(child.path());

                    Map<String, Object> metadata = child.metadata();
                    if (metadata != null && metadata.containsKey("__proto_bytes__")) {
                        try {
                            byte[] protoBytes = (byte[]) metadata.get("__proto_bytes__");
                            ZipMetadataProto.ZipEntryMetadata protoMetadata =
                                ZipMetadataProto.ZipEntryMetadata.parseFrom(protoBytes);

                            int method = protoMetadata.getMethod();
                            if (method >= 0) {
                                entry.setMethod(method);
                            }

                            if (protoMetadata.getGeneralPurposeBit() != 0) {
                                int stored = protoMetadata.getGeneralPurposeBit();
                                byte[] gpbBytes = new byte[] {
                                    (byte)(stored & 0xFF),
                                    (byte)((stored >> 8) & 0xFF)
                                };
                                GeneralPurposeBit gpb = GeneralPurposeBit.parse(gpbBytes, 0);
                                entry.setGeneralPurposeBit(gpb);
                            }

                            entry.setCrc(protoMetadata.getCrc32());
                            entry.setSize(protoMetadata.getUncompressedSize());
                            entry.setCompressedSize(protoMetadata.getCompressedSize());

                            if (protoMetadata.getExternalFileAttributes() != 0) {
                                entry.setExternalAttributes(protoMetadata.getExternalFileAttributes());
                            }

                            if (protoMetadata.getInternalFileAttributes() != 0) {
                                entry.setInternalAttributes(protoMetadata.getInternalFileAttributes());
                            }

                            boolean hasExtraField = protoMetadata.getExtraField() != null &&
                                                    !protoMetadata.getExtraField().isEmpty();
                            if (hasExtraField) {
                                entry.setExtra(protoMetadata.getExtraField().toByteArray());
                            }

                            if (protoMetadata.getLastModifiedTime() > 0) {
                                if (hasExtraField) {
                                    entry.setLastModifiedTime(FileTime.fromMillis(protoMetadata.getLastModifiedTime()));
                                    if (protoMetadata.getCreationTime() > 0) {
                                        entry.setCreationTime(FileTime.fromMillis(protoMetadata.getCreationTime()));
                                    }
                                    if (protoMetadata.getLastAccessTime() > 0) {
                                        entry.setLastAccessTime(FileTime.fromMillis(protoMetadata.getLastAccessTime()));
                                    }
                                } else {
                                    entry.setTime(protoMetadata.getLastModifiedTime());
                                }
                            }

                            if (protoMetadata.getComment() != null && !protoMetadata.getComment().isEmpty()) {
                                entry.setComment(protoMetadata.getComment());
                            }

                        } catch (Exception e) {
                            throw new IOException("Failed to parse ZIP metadata for entry: " + child.path(), e);
                        }
                    }

                    zos.putArchiveEntry(entry);

                    if (!isDirectory && child.buffer().size() > 0) {
                        child.buffer().position(0);
                        try (InputStream in = child.buffer().inputStream(0)) {
                            in.transferTo(zos);
                        }
                    }

                    zos.closeArchiveEntry();
                }
            }
        }

        @Override
        public void close() {
            // Buffer is managed externally
        }

        private static EntryMetadata buildEntryMetadata(ZipArchiveEntry entry) {
            Instant mtime = entry.getLastModifiedTime() != null
                    ? Instant.ofEpochMilli(entry.getLastModifiedTime().toMillis()) : null;
            Instant ctime = entry.getCreationTime() != null
                    ? Instant.ofEpochMilli(entry.getCreationTime().toMillis()) : null;
            Instant atime = entry.getLastAccessTime() != null
                    ? Instant.ofEpochMilli(entry.getLastAccessTime().toMillis()) : null;
            Integer posixMode = extractPosixMode(entry);

            return new EntryMetadata(mtime, ctime, atime, posixMode,
                    null, null, null, null, null);
        }

        private static Integer extractPosixMode(ZipArchiveEntry entry) {
            long extAttr = entry.getExternalAttributes();
            if (extAttr == 0) return null;
            int unixMode = (int) (extAttr >> 16) & 0xFFFF;
            return unixMode != 0 ? unixMode : null;
        }

        private Map<String, Object> extractEntryMetadata(ZipArchiveEntry entry) {
            ZipMetadataProto.ZipEntryMetadata.Builder builder =
                ZipMetadataProto.ZipEntryMetadata.newBuilder();

            builder.setMethod(entry.getMethod());
            builder.setCrc32(entry.getCrc());
            builder.setUncompressedSize(entry.getSize());
            builder.setCompressedSize(entry.getCompressedSize());

            if (entry.getLastModifiedTime() != null) {
                builder.setLastModifiedTime(entry.getLastModifiedTime().toMillis());
            }
            if (entry.getCreationTime() != null) {
                builder.setCreationTime(entry.getCreationTime().toMillis());
            }
            if (entry.getLastAccessTime() != null) {
                builder.setLastAccessTime(entry.getLastAccessTime().toMillis());
            }

            if (entry.getComment() != null && !entry.getComment().isEmpty()) {
                builder.setComment(entry.getComment());
            }

            byte[] extraField = entry.getExtra();
            if (extraField != null && extraField.length > 0) {
                builder.setExtraField(ByteString.copyFrom(extraField));
            }

            long externalAttributes = entry.getExternalAttributes();
            if (externalAttributes != 0) {
                builder.setExternalFileAttributes(externalAttributes);
            }

            int internalAttributes = entry.getInternalAttributes();
            if (internalAttributes != 0) {
                builder.setInternalFileAttributes(internalAttributes);
            }

            byte[] gpbBytes = entry.getGeneralPurposeBit().encode();
            int generalPurposeBit = ((gpbBytes[1] & 0xFF) << 8) | (gpbBytes[0] & 0xFF);
            if (generalPurposeBit != 0) {
                builder.setGeneralPurposeBit(generalPurposeBit);
            }

            ZipMetadataProto.ZipEntryMetadata protoMetadata = builder.build();
            byte[] serialized = protoMetadata.toByteArray();

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("__proto_bytes__", serialized);
            metadata.put("__proto_type__", "ZipEntryMetadata");

            metadata.put("method", entry.getMethod());
            metadata.put("crc32", entry.getCrc());
            metadata.put("size", entry.getSize());

            return metadata;
        }
    }
}
