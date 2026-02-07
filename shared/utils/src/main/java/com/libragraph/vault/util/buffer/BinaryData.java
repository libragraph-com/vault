package com.libragraph.vault.util.buffer;

import com.libragraph.vault.util.ContentHash;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;

/**
 * Read-only binary data backed by RAM, memory-mapped file, or disk file.
 *
 * Implements SeekableByteChannel for direct channel-based access.
 * Provides convenience methods for stream-based access and format detection.
 *
 * Design principles:
 * - Hash and size are always available
 * - Stream access uses standard JDK wrappers (Channels.newInputStream)
 * - No unsafe operations (no readAllBytes)
 */
public abstract class BinaryData implements SeekableByteChannel {

    /**
     * Wraps an existing SeekableByteChannel as BinaryData.
     * Hash will be computed lazily on first access.
     */
    public static BinaryData wrap(SeekableByteChannel channel) {
        return new WrappedBinaryData(channel);
    }

    /**
     * Content hash (BLAKE3-128) of this binary data.
     * May be computed lazily on first call.
     */
    public abstract ContentHash hash();

    /**
     * Total size in bytes.
     */
    public abstract long size();

    /**
     * Opens an InputStream positioned at the given offset.
     * Multiple streams can be opened concurrently.
     *
     * Uses standard JDK Channels.newInputStream() wrapper.
     *
     * @param pos starting position (0-based)
     * @return InputStream positioned at offset
     */
    public InputStream inputStream(long pos) {
        try {
            position(pos);
            return Channels.newInputStream(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create input stream at position " + pos, e);
        }
    }

    /**
     * Reads the first N bytes as a header (for format detection).
     * Does not advance the buffer position.
     *
     * Hard limit: min(maxBytes, 64KB) to prevent unbounded reads.
     *
     * @param maxBytes maximum bytes to read
     * @return header bytes (may be shorter than maxBytes if file is smaller)
     */
    public byte[] readHeader(int maxBytes) {
        int limit = Math.min(maxBytes, 64 * 1024);  // Hard 64KB limit
        int toRead = (int) Math.min(limit, size());

        try {
            long originalPos = position();
            position(0);

            ByteBuffer buffer = ByteBuffer.allocate(toRead);
            int bytesRead = read(buffer);

            position(originalPos);  // Restore position

            if (bytesRead == -1) return new byte[0];

            byte[] header = new byte[bytesRead];
            buffer.flip();
            buffer.get(header);
            return header;

        } catch (Exception e) {
            throw new RuntimeException("Failed to read header", e);
        }
    }
}
