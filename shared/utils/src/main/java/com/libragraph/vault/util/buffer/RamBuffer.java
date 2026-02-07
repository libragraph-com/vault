package com.libragraph.vault.util.buffer;

import com.libragraph.vault.util.ContentHash;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;

/**
 * Buffer implementation using in-memory byte array.
 * Suitable for small files (< 4MB).
 *
 * Supports simultaneous read/write operations with automatic growth.
 */
public class RamBuffer extends Buffer {
    private byte[] data;
    private long position;
    private long size;  // Logical size (may be less than data.length)

    /**
     * Creates empty buffer with initial capacity.
     */
    public RamBuffer(int initialCapacity) {
        this.data = new byte[initialCapacity];
        this.position = 0;
        this.size = 0;
    }

    /**
     * Creates buffer from existing data (for wrapping).
     */
    public RamBuffer(byte[] data, ContentHash hash) {
        this.data = data;
        this.position = 0;
        this.size = data.length;
        // Note: hash will be validated lazily when hash() is called
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (position >= size) {
            return -1;  // EOF
        }

        int remaining = (int) (size - position);
        int toRead = Math.min(remaining, dst.remaining());
        dst.put(data, (int) position, toRead);
        position += toRead;
        return toRead;
    }

    @Override
    protected int doWrite(ByteBuffer src) throws IOException {
        int toWrite = src.remaining();
        long endPosition = position + toWrite;

        // Grow array if needed
        if (endPosition > data.length) {
            grow(endPosition);
        }

        src.get(data, (int) position, toWrite);
        position += toWrite;

        // Update logical size if we wrote past end
        if (position > size) {
            size = position;
        }

        return toWrite;
    }

    @Override
    protected SeekableByteChannel doTruncate(long newSize) throws IOException {
        if (newSize < size) {
            size = newSize;
            if (position > size) {
                position = size;
            }
        }
        return this;
    }

    @Override
    public long position() throws IOException {
        return position;
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        if (newPosition < 0) {
            throw new IllegalArgumentException("Negative position: " + newPosition);
        }
        this.position = newPosition;
        return this;
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public void close() throws IOException {
        // No resources to close for RAM buffer
    }

    /**
     * Grows the internal array to accommodate the requested size.
     * Doubles capacity each time to amortize growth cost.
     */
    private void grow(long minCapacity) {
        long newCapacity = Math.max(
            data.length * 2L,
            minCapacity
        );

        // Cap at Integer.MAX_VALUE (array size limit)
        if (newCapacity > Integer.MAX_VALUE) {
            newCapacity = Integer.MAX_VALUE;
        }

        data = Arrays.copyOf(data, (int) newCapacity);
    }
}
