package com.libragraph.vault.formats.api;

import com.libragraph.vault.util.buffer.BinaryData;

import java.util.Map;

/**
 * Interface for codec plugins that handle transport-level transformations.
 * Examples: gzip, bzip2, xz, encryption.
 *
 * Codecs are chainable and bidirectional.
 * Implementations should be {@code @ApplicationScoped} CDI beans.
 */
public interface Codec {
    /**
     * Checks if this codec can handle the given file.
     *
     * @param header   First N bytes of the file (typically 8-16 bytes)
     * @param filename Original filename (may contain hints like .gz extension)
     * @return true if this codec should handle the file
     */
    boolean matches(byte[] header, String filename);

    /**
     * Decodes (decompresses/decrypts) the input buffer.
     *
     * @param input Encoded input buffer
     * @return Decoded output buffer
     */
    BinaryData decode(BinaryData input);

    /**
     * Encodes (compresses/encrypts) the input buffer.
     *
     * @param input      Plain input buffer
     * @param parameters Encoding parameters (compression level, encryption key, etc.)
     * @return Encoded output buffer
     */
    BinaryData encode(BinaryData input, Map<String, Object> parameters);

    /**
     * Returns the default encoding parameters for this codec.
     * Used during container reconstruction.
     */
    Map<String, Object> getEncodingParameters();
}
