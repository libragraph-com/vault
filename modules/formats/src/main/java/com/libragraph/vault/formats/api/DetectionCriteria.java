package com.libragraph.vault.formats.api;

import java.util.Arrays;
import java.util.Set;

/**
 * Criteria for detecting when a handler should be used.
 *
 * @param mimeTypes    MIME types to match (e.g., "application/zip", "text/*")
 * @param extensions   File extensions without dot (e.g., "zip", "tar")
 * @param magicBytes   Magic bytes to match, or null if not applicable
 * @param magicOffset  Offset in header where magic bytes start (0 for most formats, 257 for TAR)
 * @param priority     Higher priority wins on conflict (e.g., ZIP=200 beats Tika=0)
 */
public record DetectionCriteria(
        Set<String> mimeTypes,
        Set<String> extensions,
        byte[] magicBytes,
        int magicOffset,
        int priority
) {
    public DetectionCriteria {
        // Defensive copies
        mimeTypes = Set.copyOf(mimeTypes);
        extensions = Set.copyOf(extensions);
        if (magicBytes != null) {
            magicBytes = Arrays.copyOf(magicBytes, magicBytes.length);
        }
    }

    /**
     * Creates criteria that match all files (catch-all handler).
     * Used for Tika fallback.
     *
     * @param priority priority for this catch-all (typically 0)
     */
    public static DetectionCriteria catchAll(int priority) {
        return new DetectionCriteria(
                Set.of("*/*"),
                Set.of("*"),
                null,
                0,
                priority
        );
    }

    /**
     * Checks if this criteria matches the given file properties.
     */
    public boolean matches(String mimeType, String filename, byte[] header) {
        // Check magic bytes first (most reliable)
        if (magicBytes != null && header != null) {
            int endOffset = magicOffset + magicBytes.length;
            if (header.length >= endOffset) {
                boolean magicMatch = true;
                for (int i = 0; i < magicBytes.length; i++) {
                    if (header[magicOffset + i] != magicBytes[i]) {
                        magicMatch = false;
                        break;
                    }
                }
                if (magicMatch) {
                    return true;
                }
            }
        }

        // Check MIME type
        if (mimeTypes.contains("*/*")) {
            return true;
        }
        if (mimeType != null && mimeTypes.contains(mimeType)) {
            return true;
        }
        // Check MIME type wildcards (e.g., "text/*")
        if (mimeType != null) {
            String baseType = mimeType.split("/")[0];
            if (mimeTypes.contains(baseType + "/*")) {
                return true;
            }
        }

        // Check file extension
        if (extensions.contains("*")) {
            return true;
        }
        if (filename != null) {
            int dotIndex = filename.lastIndexOf('.');
            if (dotIndex > 0) {
                String ext = filename.substring(dotIndex + 1).toLowerCase();
                if (extensions.contains(ext)) {
                    return true;
                }
            }
        }

        return false;
    }
}
