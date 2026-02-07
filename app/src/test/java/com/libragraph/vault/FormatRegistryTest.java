package com.libragraph.vault;

import com.libragraph.vault.formats.api.Codec;
import com.libragraph.vault.formats.api.FileContext;
import com.libragraph.vault.formats.api.FormatHandlerFactory;
import com.libragraph.vault.formats.api.Handler;
import com.libragraph.vault.formats.registry.FormatRegistry;
import com.libragraph.vault.util.buffer.Buffer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.*;

/**
 * CDI integration test verifying that FormatRegistry discovers all
 * FormatHandlerFactory and Codec beans via Quarkus/ArC.
 */
@QuarkusTest
class FormatRegistryTest {

    @Inject
    FormatRegistry registry;

    @Inject
    Instance<FormatHandlerFactory> factories;

    @Inject
    Instance<Codec> codecs;

    @Test
    void shouldDiscoverAllFactories() {
        long count = StreamSupport.stream(factories.spliterator(), false).count();

        // ZIP, TAR, Text, Tika = 4 factories
        assertThat(count).isGreaterThanOrEqualTo(4);
    }

    @Test
    void shouldDiscoverAllCodecs() {
        long count = StreamSupport.stream(codecs.spliterator(), false).count();

        // Gzip, Bzip2 = 2 codecs
        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldFindZipHandler() throws Exception {
        // Create buffer with ZIP magic bytes
        byte[] zipData = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00};
        Buffer buf = Buffer.allocate(zipData.length);
        buf.write(ByteBuffer.wrap(zipData));

        var handler = registry.findHandler(buf, FileContext.of("test.zip"));

        assertThat(handler).isPresent();
        assertThat(handler.get().hasChildren()).isTrue();
    }

    @Test
    void shouldFindGzipCodec() {
        byte[] gzipHeader = {0x1f, (byte) 0x8b, 0x08, 0x00};

        var codec = registry.findCodec(gzipHeader, "file.gz");

        assertThat(codec).isPresent();
    }

    @Test
    void shouldFindBzip2Codec() {
        byte[] bz2Header = {'B', 'Z', 'h', '9'};

        var codec = registry.findCodec(bz2Header, "file.bz2");

        assertThat(codec).isPresent();
    }

    @Test
    void shouldFallbackToTikaForUnknownFormat() throws Exception {
        Buffer buf = Buffer.allocate(8);
        buf.write(ByteBuffer.wrap("plain txt".getBytes()));

        var handler = registry.findHandler(buf, FileContext.of("unknown.xyz"));

        // Tika catch-all should handle it
        assertThat(handler).isPresent();
    }
}
