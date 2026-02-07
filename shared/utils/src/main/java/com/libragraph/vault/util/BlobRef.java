package com.libragraph.vault.util;

import java.util.Objects;

/**
 * Content-addressable reference to a stored blob.
 *
 * Contains exactly the information needed to locate and retrieve a blob
 * with zero probing: hash, uncompressed size, and container flag.
 *
 * <p>String format: {@code {hash}-{leafSize}} for data blobs,
 * {@code {hash}-{leafSize}_} for containers (manifests).
 */
public record BlobRef(ContentHash hash, long leafSize, boolean isContainer) {

    public BlobRef {
        Objects.requireNonNull(hash, "hash cannot be null");
        if (leafSize <= 0) {
            throw new IllegalArgumentException("leafSize must be > 0, got: " + leafSize);
        }
    }

    /**
     * Creates a leaf (data blob) reference.
     */
    public static BlobRef leaf(ContentHash hash, long size) {
        return new BlobRef(hash, size, false);
    }

    /**
     * Creates a container (manifest) reference.
     */
    public static BlobRef container(ContentHash hash, long size) {
        return new BlobRef(hash, size, true);
    }

    /**
     * Parses a storage key back into a BlobRef.
     *
     * @param key format: {@code {hex32}-{size}} or {@code {hex32}-{size}_}
     * @throws IllegalArgumentException if the format is invalid
     */
    public static BlobRef parse(String key) {
        Objects.requireNonNull(key, "key cannot be null");

        boolean container = key.endsWith("_");
        String body = container ? key.substring(0, key.length() - 1) : key;

        int dashIndex = body.indexOf('-');
        if (dashIndex < 0) {
            throw new IllegalArgumentException("Invalid BlobRef key (no '-' separator): " + key);
        }

        String hexPart = body.substring(0, dashIndex);
        String sizePart = body.substring(dashIndex + 1);

        ContentHash hash = ContentHash.fromHex(hexPart);

        long size;
        try {
            size = Long.parseLong(sizePart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid BlobRef key (bad size): " + key, e);
        }

        if (size <= 0) {
            throw new IllegalArgumentException("Invalid BlobRef key (size must be > 0): " + key);
        }

        return new BlobRef(hash, size, container);
    }

    /**
     * Returns the storage key representation.
     *
     * @return {@code {hash}-{leafSize}} or {@code {hash}-{leafSize}_}
     */
    @Override
    public String toString() {
        return isContainer
                ? hash.toHex() + "-" + leafSize + "_"
                : hash.toHex() + "-" + leafSize;
    }
}
