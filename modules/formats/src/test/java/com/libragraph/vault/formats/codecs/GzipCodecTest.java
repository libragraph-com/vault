package com.libragraph.vault.formats.codecs;

import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

class GzipCodecTest {
    private GzipCodec codec;

    @BeforeEach
    void setUp() {
        codec = new GzipCodec();
    }

    @Test
    void shouldMatchGzipMagicBytes() {
        byte[] gzipHeader = new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00};
        assertThat(codec.matches(gzipHeader, "unknown")).isTrue();
    }

    @Test
    void shouldMatchGzipExtension() {
        byte[] unknownHeader = new byte[]{0x00, 0x00};
        assertThat(codec.matches(unknownHeader, "file.gz")).isTrue();
        assertThat(codec.matches(unknownHeader, "file.gzip")).isTrue();
        assertThat(codec.matches(unknownHeader, "archive.tar.gz")).isTrue();
    }

    @Test
    void shouldNotMatchNonGzipFile() {
        byte[] textHeader = "Hello".getBytes();
        assertThat(codec.matches(textHeader, "file.txt")).isFalse();
    }

    @Test
    void shouldCompressAndDecompress() throws Exception {
        String originalContent = "Hello, World! ".repeat(100) + "This is test content that should compress well.";

        Buffer input = Buffer.allocate(originalContent.length());
        input.write(ByteBuffer.wrap(originalContent.getBytes()));

        BinaryData compressed = codec.encode(input, new HashMap<>());
        assertThat(compressed.size()).isLessThan(originalContent.length());

        byte[] header = compressed.readHeader(2);
        assertThat(header).containsExactly(0x1f, (byte) 0x8b);

        BinaryData decompressed = codec.decode(compressed);

        byte[] resultBytes = decompressed.inputStream(0).readAllBytes();
        String result = new String(resultBytes);

        assertThat(result).isEqualTo(originalContent);
    }

    @Test
    void shouldDecompressRealGzipFile() throws Exception {
        var resource = getClass().getResourceAsStream("/fixtures/codecs/file.gz");
        assertThat(resource).isNotNull();

        byte[] gzipData = resource.readAllBytes();
        Buffer gzipBuffer = Buffer.allocate(gzipData.length);
        gzipBuffer.write(ByteBuffer.wrap(gzipData));

        byte[] header = gzipBuffer.readHeader(2);
        assertThat(codec.matches(header, "file.gz")).isTrue();

        BinaryData decompressed = codec.decode(gzipBuffer);
        byte[] contentBytes = decompressed.inputStream(0).readAllBytes();
        String content = new String(contentBytes);

        assertThat(content).contains("Test content to be gzipped");
    }

    @Test
    void shouldRoundTripWithDifferentContent() throws Exception {
        String[] testCases = {
                "Short",
                "Medium length content that should compress reasonably well",
                "A".repeat(1000),
                "Random: asdkjfh3948y5hsdkjfh3849yt",
        };

        for (String original : testCases) {
            Buffer inputBuffer = Buffer.allocate(original.length());
            inputBuffer.write(ByteBuffer.wrap(original.getBytes()));

            BinaryData compressed = codec.encode(inputBuffer, new HashMap<>());
            BinaryData decompressed = codec.decode(compressed);
            byte[] resultBytes = decompressed.inputStream(0).readAllBytes();
            String result = new String(resultBytes);

            assertThat(result).isEqualTo(original);
        }
    }
}
