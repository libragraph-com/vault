package com.libragraph.vault.util;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Represents a BLAKE3-128 content hash (16 bytes).
 * Immutable value object that can be used as a map key.
 *
 * Uses 128-bit BLAKE3 for:
 * - 50% memory savings vs SHA-256 (16 bytes vs 32)
 * - 4-5x faster computation
 * - Collision-resistant at scale with size as secondary key
 */
public record ContentHash(byte[] bytes) {
    private static final int HASH_LENGTH = 16; // 128 bits
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    public ContentHash {
        Objects.requireNonNull(bytes, "Content hash bytes cannot be null");
        if (bytes.length != HASH_LENGTH) {
            throw new IllegalArgumentException(
                "Content hash must be 16 bytes (BLAKE3-128), got: " + bytes.length
            );
        }
        // Defensive copy to ensure immutability
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    /**
     * Creates ContentHash from hex string (32 characters).
     */
    public static ContentHash fromHex(String hex) {
        Objects.requireNonNull(hex, "hex string cannot be null");
        if (hex.length() != 32) {
            throw new IllegalArgumentException(
                "BLAKE3-128 hex string must be 32 characters, got: " + hex.length()
            );
        }
        try {
            return new ContentHash(HEX_FORMAT.parseHex(hex));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid hex string: " + hex, e);
        }
    }

    /**
     * Returns lowercase hex representation (32 characters).
     */
    public String toHex() {
        return HEX_FORMAT.formatHex(bytes);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ContentHash other)) return false;
        return Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return toHex();
    }
}
