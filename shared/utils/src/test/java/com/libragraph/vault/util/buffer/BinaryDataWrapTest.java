package com.libragraph.vault.util.buffer;

import com.libragraph.vault.util.ContentHash;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class BinaryDataWrapTest {

    @Test
    void shouldWrapByteChannel() throws Exception {
        // Write temp file
        Path tmp = Files.createTempFile("test-", ".txt");
        Files.writeString(tmp, "Hello, BinaryData!");
        try {
            SeekableByteChannel channel = Files.newByteChannel(tmp);
            BinaryData bd = BinaryData.wrap(channel);

            assertThat(bd.size()).isEqualTo(18);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void shouldComputeHashLazily() throws Exception {
        Path tmp = Files.createTempFile("test-", ".txt");
        Files.writeString(tmp, "Hash me!");
        try {
            SeekableByteChannel channel = Files.newByteChannel(tmp);
            BinaryData bd = BinaryData.wrap(channel);

            ContentHash hash = bd.hash();
            assertThat(hash).isNotNull();
            assertThat(hash.toHex()).hasSize(32);

            // Same hash on second call
            assertThat(bd.hash()).isEqualTo(hash);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void shouldReadHeader() throws Exception {
        Path tmp = Files.createTempFile("test-", ".txt");
        Files.writeString(tmp, "ABCDEFGHIJ");
        try {
            SeekableByteChannel channel = Files.newByteChannel(tmp);
            BinaryData bd = BinaryData.wrap(channel);

            byte[] header = bd.readHeader(4);
            assertThat(new String(header)).isEqualTo("ABCD");

            // Position should be restored
            assertThat(bd.position()).isEqualTo(0);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void shouldProvideInputStream() throws Exception {
        Path tmp = Files.createTempFile("test-", ".txt");
        Files.writeString(tmp, "Stream content");
        try {
            SeekableByteChannel channel = Files.newByteChannel(tmp);
            BinaryData bd = BinaryData.wrap(channel);

            InputStream is = bd.inputStream(0);
            byte[] bytes = is.readAllBytes();
            assertThat(new String(bytes)).isEqualTo("Stream content");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
