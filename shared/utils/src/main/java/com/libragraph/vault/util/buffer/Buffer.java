package com.libragraph.vault.util.buffer;

import com.libragraph.vault.util.ContentHash;
import org.apache.commons.codec.digest.Blake3;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;

/**
 * Writable buffer that extends BinaryData with write capabilities.
 *
 * Supports incremental hash computation during sequential writes:
 * - Tailing writes (appending) update hash incrementally
 * - Overwrites invalidate hash and trigger recomputation
 * - Gaps in writes fall back to full hash computation
 *
 * Factory method allocates appropriate backend (RAM, mmap, or file)
 * based on size thresholds.
 */
public abstract class Buffer extends BinaryData {

    private Blake3 incrementalHash = Blake3.initHash();
    private long hashedUpTo = 0;
    private ContentHash cachedHash = null;

    /** Threshold above which allocate() uses a temp file instead of RAM. */
    private static final long FILE_THRESHOLD = 4L * 1024 * 1024; // 4 MB

    /**
     * Allocates a buffer of the given size.
     * Chooses backend automatically based on size:
     * <ul>
     *   <li>&lt; 4 MB — {@link RamBuffer} (heap byte array)</li>
     *   <li>&gt;= 4 MB — {@link FileBuffer} (temp-file backed {@link java.nio.channels.FileChannel})</li>
     * </ul>
     */
    public static Buffer allocate(long size) {
        if (size < FILE_THRESHOLD) {
            return new RamBuffer((int) size);
        }
        try {
            return new FileBuffer();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to allocate file-backed buffer", e);
        }
    }

    /**
     * Opens an OutputStream positioned at the given offset.
     *
     * Uses standard JDK Channels.newOutputStream() wrapper.
     * All writes go through write(ByteBuffer) for hash tracking.
     *
     * @param pos starting position (0-based)
     * @return OutputStream positioned at offset
     */
    public OutputStream outputStream(long pos) {
        try {
            position(pos);
            return Channels.newOutputStream(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create output stream at position " + pos, e);
        }
    }

    @Override
    public int write(ByteBuffer src) throws java.io.IOException {
        long writePos = position();

        if (cachedHash != null) {
            // Any write invalidates cached hash
            cachedHash = null;
        }

        if (writePos < hashedUpTo) {
            // Overwrite detected - invalidate incremental hash
            incrementalHash = Blake3.initHash();
            hashedUpTo = 0;
        } else if (writePos == hashedUpTo) {
            // Tailing write - update incremental hash
            ByteBuffer copy = src.duplicate();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            incrementalHash.update(bytes);
            hashedUpTo += bytes.length;
        }
        // else: gap (writePos > hashedUpTo) - can't update incrementally

        return doWrite(src);
    }

    @Override
    public SeekableByteChannel truncate(long newSize) throws java.io.IOException {
        long currentSize = size();

        if (newSize < currentSize) {
            // Shrinking - invalidate hash
            cachedHash = null;

            if (newSize < hashedUpTo) {
                // Truncating hashed data - reset incremental hash
                incrementalHash = Blake3.initHash();
                hashedUpTo = 0;
            }
        }

        return doTruncate(newSize);
    }

    @Override
    public ContentHash hash() {
        if (cachedHash != null) {
            return cachedHash;
        }

        if (hashedUpTo == size() && hashedUpTo > 0) {
            // Fully hashed incrementally - finalize it
            byte[] hashBytes = incrementalHash.doFinalize(16);
            cachedHash = new ContentHash(hashBytes);
            return cachedHash;
        }

        // Gaps, incomplete, or empty - compute full hash
        cachedHash = computeFullHash();
        return cachedHash;
    }

    /**
     * Subclasses implement actual write operation.
     */
    protected abstract int doWrite(ByteBuffer src) throws java.io.IOException;

    /**
     * Subclasses implement actual truncate operation.
     */
    protected abstract SeekableByteChannel doTruncate(long newSize) throws java.io.IOException;

    /**
     * Computes hash by reading entire buffer.
     * Called when incremental hash is not available.
     */
    private ContentHash computeFullHash() {
        try {
            long originalPos = position();
            position(0);

            Blake3 hasher = Blake3.initHash();
            ByteBuffer buffer = ByteBuffer.allocate(8192);

            while (read(buffer) != -1) {
                buffer.flip();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                hasher.update(bytes);
                buffer.clear();
            }

            position(originalPos);

            byte[] hashBytes = hasher.doFinalize(16);
            return new ContentHash(hashBytes);

        } catch (Exception e) {
            throw new RuntimeException("Failed to compute hash", e);
        }
    }
}
