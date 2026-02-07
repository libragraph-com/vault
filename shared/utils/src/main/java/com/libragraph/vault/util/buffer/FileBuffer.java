package com.libragraph.vault.util.buffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Buffer implementation backed by a temporary file.
 * Suitable for data that exceeds comfortable RAM thresholds (>= 4 MB).
 *
 * <p>The temp file is created on construction and deleted on {@link #close()}.
 * Reads and writes go through a {@link FileChannel} for efficient I/O.
 */
public class FileBuffer extends Buffer {

    private final Path path;
    private final FileChannel channel;
    private boolean open = true;

    /**
     * Creates a new file-backed buffer.
     * A temp file is created immediately; it is deleted on {@link #close()}.
     */
    public FileBuffer() throws IOException {
        this.path = Files.createTempFile("vault-buf-", ".tmp");
        this.channel = FileChannel.open(path,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.DELETE_ON_CLOSE);
    }

    @Override
    public long size() {
        try {
            return channel.size();
        } catch (IOException e) {
            throw new RuntimeException("Failed to get file size", e);
        }
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return channel.read(dst);
    }

    @Override
    protected int doWrite(ByteBuffer src) throws IOException {
        return channel.write(src);
    }

    @Override
    protected SeekableByteChannel doTruncate(long newSize) throws IOException {
        channel.truncate(newSize);
        if (channel.position() > newSize) {
            channel.position(newSize);
        }
        return this;
    }

    @Override
    public long position() throws IOException {
        return channel.position();
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        if (newPosition < 0) {
            throw new IllegalArgumentException("Negative position: " + newPosition);
        }
        channel.position(newPosition);
        return this;
    }

    @Override
    public boolean isOpen() {
        return open && channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        if (open) {
            open = false;
            channel.close();
            // DELETE_ON_CLOSE handles file removal, but belt-and-suspenders:
            Files.deleteIfExists(path);
        }
    }
}
