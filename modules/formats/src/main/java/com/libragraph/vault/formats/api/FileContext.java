package com.libragraph.vault.formats.api;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Context information about a file being processed.
 */
public record FileContext(
        String filename,
        Optional<Path> sourcePath,
        Optional<String> detectedMimeType
) {
    public static FileContext of(String filename) {
        return new FileContext(filename, Optional.empty(), Optional.empty());
    }

    public static FileContext of(Path path) {
        return new FileContext(
                path.getFileName().toString(),
                Optional.of(path),
                Optional.empty()
        );
    }

    public FileContext withMimeType(String mimeType) {
        return new FileContext(filename, sourcePath, Optional.of(mimeType));
    }

    public String getFilename() {
        return filename;
    }
}
