package com.libragraph.vault.formats.handlers;

import com.libragraph.vault.formats.api.*;
import com.libragraph.vault.util.buffer.BinaryData;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Format handler for plain text files.
 * Priority 100 (higher than Tika's 0, lower than container handlers).
 */
@ApplicationScoped
public class TextHandlerFactory implements FormatHandlerFactory {

    @Override
    public DetectionCriteria getDetectionCriteria() {
        return new DetectionCriteria(
                Set.of("text/plain", "text/*"),
                Set.of("txt", "md", "log", "text"),
                null,
                0,
                100
        );
    }

    @Override
    public Handler createInstance(BinaryData buffer, FileContext context) {
        return new TextHandler(buffer, context);
    }

    private static class TextHandler implements Handler {
        private final BinaryData buffer;
        private final FileContext context;

        TextHandler(BinaryData buffer, FileContext context) {
            this.buffer = buffer;
            this.context = context;
        }

        @Override
        public boolean hasChildren() {
            return false;
        }

        @Override
        public boolean isCompressible() {
            return true;
        }

        @Override
        public Map<String, Object> extractMetadata() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("format", "text/plain");
            metadata.put("size", buffer.size());

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(buffer.inputStream(0), StandardCharsets.UTF_8))) {
                long lineCount = reader.lines().count();
                metadata.put("lines", lineCount);
            } catch (Exception e) {
                // Ignore - not critical
            }

            return metadata;
        }

        @Override
        public String extractText() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(buffer.inputStream(0), StandardCharsets.UTF_8))) {
                return reader.lines()
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void close() {
            // No resources to close
        }
    }
}
