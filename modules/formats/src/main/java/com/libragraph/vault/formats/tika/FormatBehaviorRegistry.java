package com.libragraph.vault.formats.tika;

import org.apache.tika.mime.MediaType;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of hardcoded format behaviors.
 * Defines how different MIME types should be handled.
 */
public class FormatBehaviorRegistry {
    private final Map<String, FormatBehavior> exactMatches = new HashMap<>();
    private final Map<String, FormatBehavior> wildcardMatches = new HashMap<>();
    private final FormatBehavior defaultBehavior;

    public FormatBehaviorRegistry() {
        this.defaultBehavior = FormatBehavior.leaf(false);
        registerBehaviors();
    }

    private void registerBehaviors() {
        // Text formats - compressible
        register("text/*", FormatBehavior.leaf(true));
        register("text/plain", FormatBehavior.leaf(true));
        register("text/html", FormatBehavior.leaf(true));
        register("text/xml", FormatBehavior.leaf(true));
        register("text/csv", FormatBehavior.leaf(true));
        register("application/json", FormatBehavior.leaf(true));
        register("application/xml", FormatBehavior.leaf(true));
        register("application/javascript", FormatBehavior.leaf(true));

        // Images - not compressible (already compressed)
        register("image/*", FormatBehavior.leaf(false));
        register("image/jpeg", FormatBehavior.leaf(false));
        register("image/png", FormatBehavior.leaf(false));
        register("image/gif", FormatBehavior.leaf(false));
        register("image/webp", FormatBehavior.leaf(false));
        register("image/svg+xml", FormatBehavior.leaf(true)); // SVG is text

        // Video - not compressible
        register("video/*", FormatBehavior.leaf(false));
        register("video/mp4", FormatBehavior.leaf(false));
        register("video/mpeg", FormatBehavior.leaf(false));
        register("video/webm", FormatBehavior.leaf(false));

        // Audio - not compressible
        register("audio/*", FormatBehavior.leaf(false));
        register("audio/mpeg", FormatBehavior.leaf(false));
        register("audio/mp4", FormatBehavior.leaf(false));
        register("audio/ogg", FormatBehavior.leaf(false));
        register("audio/wav", FormatBehavior.leaf(true)); // WAV is uncompressed

        // Office formats (ZIP-based) - Default: LEAF
        register("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                FormatBehavior.leaf(false));
        register("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                FormatBehavior.leaf(false));
        register("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                FormatBehavior.leaf(false));
        register("application/vnd.oasis.opendocument.text",
                FormatBehavior.leaf(false));
        register("application/vnd.oasis.opendocument.spreadsheet",
                FormatBehavior.leaf(false));
        register("application/vnd.oasis.opendocument.presentation",
                FormatBehavior.leaf(false));

        // PDF - not compressible (uses internal compression), LEAF
        register("application/pdf", FormatBehavior.leaf(false));

        // Pure containers - REQUIRE dedicated handlers
        register("application/zip", FormatBehavior.container());
        register("application/x-tar", FormatBehavior.container());
        register("application/x-7z-compressed", FormatBehavior.container());
        register("application/x-rar-compressed", FormatBehavior.container());

        // Codecs (not containers)
        register("application/gzip", FormatBehavior.leaf(false));
        register("application/x-bzip2", FormatBehavior.leaf(false));

        // Other ZIP-based formats - Default: LEAF
        register("application/epub+zip", FormatBehavior.leaf(false));
        register("application/java-archive", FormatBehavior.leaf(false));
        register("application/vnd.android.package-archive", FormatBehavior.leaf(false));

        // Email formats
        register("message/rfc822", FormatBehavior.leaf(true));
        register("application/vnd.ms-outlook", FormatBehavior.container());
    }

    private void register(String mimeType, FormatBehavior behavior) {
        if (mimeType.endsWith("/*")) {
            wildcardMatches.put(mimeType, behavior);
        } else {
            exactMatches.put(mimeType, behavior);
        }
    }

    /**
     * Gets the behavior for a given MIME type.
     * Tries exact match first, then wildcard, then default.
     */
    public FormatBehavior getBehavior(MediaType mediaType) {
        String type = mediaType.toString();

        if (exactMatches.containsKey(type)) {
            return exactMatches.get(type);
        }

        String baseType = mediaType.getType() + "/*";
        if (wildcardMatches.containsKey(baseType)) {
            return wildcardMatches.get(baseType);
        }

        return defaultBehavior;
    }

    /**
     * Gets the behavior for a MIME type string.
     */
    public FormatBehavior getBehavior(String mimeType) {
        return getBehavior(MediaType.parse(mimeType));
    }
}
