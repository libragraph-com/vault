package com.libragraph.vault.formats.api;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Interface for format handler instances.
 * Handlers extract content and metadata from specific file formats.
 */
public interface Handler extends AutoCloseable {
    /**
     * Does this file contain child files (is it a container)?
     */
    boolean hasChildren();

    /**
     * Is this file compressible? (Used to decide whether to compress for storage)
     * Examples: text/plain = true, JPEG = false (already compressed)
     */
    boolean isCompressible();

    /**
     * Returns container capabilities (only relevant if hasChildren() = true).
     * Default implementation returns null (not a container).
     */
    default ContainerCapabilities getCapabilities() {
        return null;
    }

    /**
     * Extracts child files from container.
     * Only called if hasChildren() = true.
     */
    default Stream<ContainerChild> extractChildren() {
        return Stream.empty();
    }

    /**
     * Reconstructs the container from extracted children.
     * Only called if hasChildren() = true and getCapabilities().reconstructionTier() is TIER_1_RECONSTRUCTABLE.
     *
     * The handler is responsible for bit-identical reconstruction, applying all format-specific
     * metadata (compression methods, timestamps, extra fields, etc.).
     *
     * @param children The extracted children (with their buffers and metadata)
     * @param output The output stream to write the reconstructed container to
     * @throws IOException if reconstruction fails
     */
    default void reconstruct(List<ContainerChild> children, OutputStream output) throws IOException {
        throw new UnsupportedOperationException("This format does not support reconstruction");
    }

    /**
     * Extracts format-specific metadata.
     */
    Map<String, Object> extractMetadata();

    /**
     * Extracts full text for search indexing.
     * Returns null if file has no extractable text.
     */
    default String extractText() {
        return null;
    }
}
