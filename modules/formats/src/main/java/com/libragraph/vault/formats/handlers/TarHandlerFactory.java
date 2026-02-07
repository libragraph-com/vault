package com.libragraph.vault.formats.handlers;

import com.libragraph.vault.formats.api.*;
import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Format handler for TAR archives.
 * Priority 200 (higher than Tika's 0) to override default behavior.
 * Uses magicOffset=257 for TAR magic detection ("ustar").
 */
@ApplicationScoped
public class TarHandlerFactory implements FormatHandlerFactory {

    private static final byte[] TAR_MAGIC = new byte[]{'u', 's', 't', 'a', 'r'};

    @Override
    public DetectionCriteria getDetectionCriteria() {
        return new DetectionCriteria(
            Set.of("application/x-tar"),
            Set.of("tar"),
            TAR_MAGIC,
            257,  // TAR magic is at offset 257
            200
        );
    }

    @Override
    public Handler createInstance(BinaryData buffer, FileContext context) {
        return new TarHandler(buffer, context);
    }

    private static class TarHandler implements Handler {
        private final BinaryData buffer;
        private final FileContext context;

        TarHandler(BinaryData buffer, FileContext context) {
            this.buffer = buffer;
            this.context = context;
        }

        @Override
        public boolean hasChildren() {
            return true;
        }

        @Override
        public boolean isCompressible() {
            return true; // TAR itself is uncompressed
        }

        @Override
        public ContainerCapabilities getCapabilities() {
            return new ContainerCapabilities(
                ContainerCapabilities.ReconstructionTier.TIER_1_RECONSTRUCTABLE,
                true,  // preservesTimestamps
                true,  // preservesPermissions (TAR stores full Unix permissions)
                true   // preservesOrder
            );
        }

        @Override
        public Stream<ContainerChild> extractChildren() {
            List<ContainerChild> children = new ArrayList<>();

            try (InputStream inputStream = buffer.inputStream(0);
                 TarArchiveInputStream tar = new TarArchiveInputStream(inputStream)) {

                TarArchiveEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    if (entry.isDirectory() || !entry.isFile()) {
                        continue;
                    }

                    long size = entry.getSize();
                    Buffer childBuffer = Buffer.allocate(size);

                    try (var output = childBuffer.outputStream(0)) {
                        tar.transferTo(output);
                    }

                    Map<String, Object> metadata = extractEntryMetadata(entry);

                    children.add(new ContainerChild(
                        entry.getName(),
                        childBuffer,
                        metadata
                    ));
                }

            } catch (IOException e) {
                throw new RuntimeException("Failed to extract children from TAR", e);
            }

            return children.stream();
        }

        @Override
        public Map<String, Object> extractMetadata() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("format", "TAR");
            metadata.put("size", buffer.size());

            int entryCount = 0;
            long totalSize = 0;

            try (InputStream inputStream = buffer.inputStream(0);
                 TarArchiveInputStream tar = new TarArchiveInputStream(inputStream)) {

                TarArchiveEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    if (entry.isFile()) {
                        entryCount++;
                        totalSize += entry.getSize();
                    }
                }

            } catch (IOException e) {
                // Ignore
            }

            metadata.put("entryCount", entryCount);
            metadata.put("totalUncompressedSize", totalSize);
            return metadata;
        }

        @Override
        public String extractText() {
            return null;
        }

        @Override
        public void close() {
            // Buffer is managed externally
        }

        private Map<String, Object> extractEntryMetadata(TarArchiveEntry entry) {
            Map<String, Object> metadata = new HashMap<>();

            metadata.put("size", entry.getSize());
            metadata.put("mode", entry.getMode());
            metadata.put("uid", entry.getLongUserId());
            metadata.put("gid", entry.getLongGroupId());
            metadata.put("userName", entry.getUserName());
            metadata.put("groupName", entry.getGroupName());
            metadata.put("modified", Instant.ofEpochMilli(entry.getModTime().getTime()).toString());

            if (entry.isSymbolicLink()) {
                metadata.put("linkTarget", entry.getLinkName());
                metadata.put("type", "symlink");
            } else if (entry.isLink()) {
                metadata.put("linkTarget", entry.getLinkName());
                metadata.put("type", "hardlink");
            } else {
                metadata.put("type", "file");
            }

            return metadata;
        }
    }
}
