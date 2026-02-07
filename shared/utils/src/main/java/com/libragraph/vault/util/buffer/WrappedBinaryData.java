package com.libragraph.vault.util.buffer;

import com.libragraph.vault.util.ContentHash;
import org.apache.commons.codec.digest.Blake3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/**
 * BinaryData implementation that wraps an existing SeekableByteChannel.
 * Hash is computed lazily on first access.
 */
class WrappedBinaryData extends BinaryData {

    private final SeekableByteChannel channel;
    private ContentHash cachedHash;

    WrappedBinaryData(SeekableByteChannel channel) {
        this.channel = channel;
    }

    @Override
    public ContentHash hash() {
        if (cachedHash == null) {
            cachedHash = computeHash();
        }
        return cachedHash;
    }

    @Override
    public long size() {
        try {
            return channel.size();
        } catch (IOException e) {
            throw new RuntimeException("Failed to get size", e);
        }
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return channel.read(dst);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        // Writing invalidates hash
        cachedHash = null;
        return channel.write(src);
    }

    @Override
    public long position() throws IOException {
        return channel.position();
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        channel.position(newPosition);
        return this;
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        cachedHash = null;  // Invalidate hash
        channel.truncate(size);
        return this;
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private ContentHash computeHash() {
        try {
            long originalPos = channel.position();
            channel.position(0);

            Blake3 hasher = Blake3.initHash();
            ByteBuffer buffer = ByteBuffer.allocate(8192);

            while (channel.read(buffer) != -1) {
                buffer.flip();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                hasher.update(bytes);
                buffer.clear();
            }

            channel.position(originalPos);

            byte[] hashBytes = hasher.doFinalize(16);
            return new ContentHash(hashBytes);

        } catch (Exception e) {
            throw new RuntimeException("Failed to compute hash", e);
        }
    }
}
