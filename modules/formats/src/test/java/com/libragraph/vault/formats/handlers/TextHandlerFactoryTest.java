package com.libragraph.vault.formats.handlers;

import com.libragraph.vault.formats.api.FileContext;
import com.libragraph.vault.formats.api.Handler;
import com.libragraph.vault.util.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.*;

class TextHandlerFactoryTest {

    private final TextHandlerFactory factory = new TextHandlerFactory();

    @Test
    void shouldDetectTextFiles() {
        var criteria = factory.getDetectionCriteria();

        assertThat(criteria.matches("text/plain", "file.txt", new byte[0])).isTrue();
        assertThat(criteria.matches("text/markdown", "file.md", new byte[0])).isTrue();
        assertThat(criteria.matches(null, "notes.txt", new byte[0])).isTrue();
    }

    @Test
    void shouldExtractText() throws Exception {
        String content = "Line 1\nLine 2\nLine 3";

        Buffer buf = Buffer.allocate(content.length());
        buf.write(ByteBuffer.wrap(content.getBytes()));

        Handler handler = factory.createInstance(buf, FileContext.of("test.txt"));

        assertThat(handler.hasChildren()).isFalse();
        assertThat(handler.isCompressible()).isTrue();
        assertThat(handler.extractText()).isEqualTo(content);

        var metadata = handler.extractMetadata();
        assertThat(metadata.get("lines")).isEqualTo(3L);
    }

    @Test
    void shouldHavePriority100() {
        assertThat(factory.getDetectionCriteria().priority()).isEqualTo(100);
    }
}
