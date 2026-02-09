package com.libragraph.vault.formats.api;

import com.libragraph.vault.util.buffer.BinaryData;

import java.util.Map;

/**
 * Represents a child extracted from a container.
 */
public record ContainerChild(
        String path,
        BinaryData buffer,
        Map<String, Object> metadata,
        EntryMetadata entryMetadata
) {
    /** Backward-compat constructor (entryMetadata = null). */
    public ContainerChild(String path, BinaryData buffer, Map<String, Object> metadata) {
        this(path, buffer, metadata, null);
    }
}
