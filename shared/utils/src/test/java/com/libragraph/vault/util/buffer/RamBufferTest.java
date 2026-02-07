package com.libragraph.vault.util.buffer;

import com.libragraph.vault.util.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.*;

class RamBufferTest {

    @Test
    void shouldReadAndWrite() throws Exception {
        Buffer buf = Buffer.allocate(64);
        buf.write(ByteBuffer.wrap("Hello".getBytes()));

        assertThat(buf.size()).isEqualTo(5);

        buf.position(0);
        ByteBuffer dst = ByteBuffer.allocate(5);
        buf.read(dst);
        dst.flip();
        assertThat(new String(dst.array(), 0, dst.remaining())).isEqualTo("Hello");
    }

    @Test
    void shouldComputeIncrementalHash() throws Exception {
        Buffer buf = Buffer.allocate(64);
        buf.write(ByteBuffer.wrap("Hello, World!".getBytes()));

        ContentHash hash = buf.hash();
        assertThat(hash).isNotNull();
        assertThat(hash.toHex()).hasSize(32);

        // Calling again returns cached value
        assertThat(buf.hash()).isEqualTo(hash);
    }

    @Test
    void shouldInvalidateHashOnOverwrite() throws Exception {
        Buffer buf = Buffer.allocate(64);
        buf.write(ByteBuffer.wrap("Hello".getBytes()));
        ContentHash hash1 = buf.hash();

        // Overwrite at position 0
        buf.position(0);
        buf.write(ByteBuffer.wrap("World".getBytes()));
        ContentHash hash2 = buf.hash();

        assertThat(hash2).isNotEqualTo(hash1);
    }

    @Test
    void shouldTruncate() throws Exception {
        Buffer buf = Buffer.allocate(64);
        buf.write(ByteBuffer.wrap("Hello, World!".getBytes()));

        assertThat(buf.size()).isEqualTo(13);

        buf.truncate(5);
        assertThat(buf.size()).isEqualTo(5);

        buf.position(0);
        ByteBuffer dst = ByteBuffer.allocate(5);
        buf.read(dst);
        dst.flip();
        assertThat(new String(dst.array(), 0, dst.remaining())).isEqualTo("Hello");
    }

    @Test
    void shouldGrowAutomatically() throws Exception {
        Buffer buf = Buffer.allocate(4);
        byte[] data = "Hello, this is longer than 4 bytes".getBytes();
        buf.write(ByteBuffer.wrap(data));

        assertThat(buf.size()).isEqualTo(data.length);

        buf.position(0);
        ByteBuffer dst = ByteBuffer.allocate(data.length);
        buf.read(dst);
        dst.flip();
        byte[] result = new byte[dst.remaining()];
        dst.get(result);
        assertThat(new String(result)).isEqualTo("Hello, this is longer than 4 bytes");
    }

    @Test
    void shouldReturnEofWhenExhausted() throws Exception {
        Buffer buf = Buffer.allocate(8);
        buf.write(ByteBuffer.wrap("AB".getBytes()));

        buf.position(0);
        ByteBuffer dst = ByteBuffer.allocate(2);
        assertThat(buf.read(dst)).isEqualTo(2);

        dst.clear();
        assertThat(buf.read(dst)).isEqualTo(-1);
    }
}
