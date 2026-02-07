package com.libragraph.vault.formats.registry;

import com.libragraph.vault.formats.api.*;
import com.libragraph.vault.util.buffer.BinaryData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Central registry that matches files to format handlers and codecs.
 * All {@link FormatHandlerFactory} and {@link Codec} beans are discovered via CDI.
 */
@ApplicationScoped
public class FormatRegistry {

    /** Header size to read for detection (covers TAR magic at offset 257). */
    private static final int HEADER_SIZE = 512;

    @Inject
    Instance<FormatHandlerFactory> factories;

    @Inject
    Instance<Codec> codecs;

    /**
     * Finds the best handler for the given buffer and context.
     * Reads a header from the buffer, matches against all registered factories,
     * and returns the highest-priority match.
     */
    public Optional<Handler> findHandler(BinaryData buffer, FileContext context) {
        byte[] header = buffer.readHeader(HEADER_SIZE);
        String mimeType = context.detectedMimeType().orElse(null);
        String filename = context.filename();

        return StreamSupport.stream(factories.spliterator(), false)
                .filter(f -> f.getDetectionCriteria().matches(mimeType, filename, header))
                .max(Comparator.comparingInt(f -> f.getDetectionCriteria().priority()))
                .map(f -> f.createInstance(buffer, context));
    }

    /**
     * Finds a codec matching the given header and filename.
     */
    public Optional<Codec> findCodec(byte[] header, String filename) {
        return StreamSupport.stream(codecs.spliterator(), false)
                .filter(c -> c.matches(header, filename))
                .findFirst();
    }
}
