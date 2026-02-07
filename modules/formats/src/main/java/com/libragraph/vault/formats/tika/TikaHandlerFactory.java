package com.libragraph.vault.formats.tika;

import com.libragraph.vault.formats.api.*;
import com.libragraph.vault.util.buffer.BinaryData;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;

import java.io.InputStream;
import java.util.*;

/**
 * Universal format handler that uses Apache Tika.
 * Handles ~1000 LEAF file formats with config-driven behavior.
 *
 * IMPORTANT: This handler is ONLY for formats without children (leaves).
 * Container formats (ZIP, DOCX, XLSX, EPUB, JAR, etc.) need dedicated handlers.
 *
 * Priority: 0 (lowest) - custom handlers always take precedence.
 */
@ApplicationScoped
public class TikaHandlerFactory implements FormatHandlerFactory {
    private static final FormatBehaviorRegistry BEHAVIOR_REGISTRY = new FormatBehaviorRegistry();
    private static final Detector DETECTOR = new DefaultDetector();

    @Override
    public DetectionCriteria getDetectionCriteria() {
        return DetectionCriteria.catchAll(0);
    }

    @Override
    public Handler createInstance(BinaryData buffer, FileContext context) {
        MediaType mediaType = detectMediaType(buffer, context);
        FormatBehavior behavior = BEHAVIOR_REGISTRY.getBehavior(mediaType);

        // Refuse to handle pure containers — they require dedicated handlers
        if (behavior.hasChildren()) {
            return null;
        }

        return new TikaHandler(buffer, context, mediaType, behavior);
    }

    private static MediaType detectMediaType(BinaryData buffer, FileContext context) {
        try (InputStream stream = buffer.inputStream(0)) {
            Metadata metadata = new Metadata();

            context.sourcePath().ifPresent(path ->
                    metadata.set("resourceName", path.getFileName().toString())
            );

            context.detectedMimeType().ifPresent(mimeType ->
                    metadata.set(Metadata.CONTENT_TYPE, mimeType)
            );

            return DETECTOR.detect(stream, metadata);
        } catch (Exception e) {
            return MediaType.OCTET_STREAM;
        }
    }

    private static class TikaHandler implements Handler {
        private final BinaryData buffer;
        private final FileContext context;
        private final MediaType mediaType;
        private final FormatBehavior behavior;
        private final Parser parser;

        TikaHandler(BinaryData buffer, FileContext context,
                    MediaType mediaType, FormatBehavior behavior) {
            this.buffer = buffer;
            this.context = context;
            this.mediaType = mediaType;
            this.behavior = behavior;
            this.parser = new AutoDetectParser();
        }

        @Override
        public boolean hasChildren() {
            return behavior.hasChildren();
        }

        @Override
        public boolean isCompressible() {
            return behavior.isCompressible();
        }

        @Override
        public Map<String, Object> extractMetadata() {
            Map<String, Object> result = new HashMap<>();
            result.put("format", mediaType.toString());
            result.put("size", buffer.size());

            try (InputStream stream = buffer.inputStream(0)) {
                Metadata metadata = new Metadata();
                metadata.set(Metadata.CONTENT_TYPE, mediaType.toString());

                parser.parse(stream, new BodyContentHandler(-1), metadata, new ParseContext());

                for (String name : metadata.names()) {
                    String[] values = metadata.getValues(name);
                    if (values.length == 1) {
                        result.put(name, values[0]);
                    } else if (values.length > 1) {
                        result.put(name, Arrays.asList(values));
                    }
                }
            } catch (Exception e) {
                result.put("metadata_error", e.getMessage());
            }

            return result;
        }

        @Override
        public String extractText() {
            if (!isTextBasedFormat(mediaType)) {
                return null;
            }

            try (InputStream stream = buffer.inputStream(0)) {
                BodyContentHandler handler = new BodyContentHandler(-1);
                Metadata metadata = new Metadata();
                metadata.set(Metadata.CONTENT_TYPE, mediaType.toString());

                parser.parse(stream, handler, metadata, new ParseContext());

                String text = handler.toString().trim();
                return text.isEmpty() ? null : text;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void close() {
            // No resources to clean up
        }

        private boolean isTextBasedFormat(MediaType mediaType) {
            String type = mediaType.getType();
            String subtype = mediaType.getSubtype();

            if (type.equals("text")) {
                return true;
            }

            if (type.equals("application")) {
                return subtype.contains("pdf")
                        || subtype.contains("word")
                        || subtype.contains("document")
                        || subtype.contains("spreadsheet")
                        || subtype.contains("presentation")
                        || subtype.contains("opendocument")
                        || subtype.contains("msword")
                        || subtype.contains("ms-excel")
                        || subtype.contains("ms-powerpoint")
                        || subtype.equals("rtf")
                        || subtype.equals("json")
                        || subtype.equals("xml");
            }

            return false;
        }
    }
}
