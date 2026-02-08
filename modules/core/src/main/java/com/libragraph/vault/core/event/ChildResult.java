package com.libragraph.vault.core.event;

import com.libragraph.vault.util.BlobRef;

public record ChildResult(BlobRef ref, String internalPath, boolean isContainer) {}
