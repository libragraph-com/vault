package com.libragraph.vault.formats.api;

import com.libragraph.vault.util.buffer.BinaryData;

/**
 * Factory for creating format handler instances.
 * Implementations should be {@code @ApplicationScoped} CDI beans.
 */
public interface FormatHandlerFactory {
    /**
     * Returns criteria for detecting when this handler should be used.
     */
    DetectionCriteria getDetectionCriteria();

    /**
     * Creates a handler instance for the given buffer.
     *
     * @param buffer  The file buffer
     * @param context File context (filename, path, etc.)
     */
    Handler createInstance(BinaryData buffer, FileContext context);
}
