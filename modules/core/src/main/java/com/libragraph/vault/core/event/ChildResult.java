package com.libragraph.vault.core.event;

import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;

import java.time.Instant;

public record ChildResult(
        BlobRef ref,
        String internalPath,
        boolean isContainer,
        short entryType,
        BinaryData formatMetadata,
        Instant mtime
) {}
