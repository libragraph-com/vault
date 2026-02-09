package com.libragraph.vault.core.event;

import com.libragraph.vault.formats.api.EntryMetadata;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;

import java.util.Map;

public record ChildResult(
        BlobRef ref,
        String internalPath,
        boolean isContainer,
        short entryType,
        BinaryData formatMetadata,
        EntryMetadata entryMetadata,
        Map<String, Object> docMetadata
) {}
