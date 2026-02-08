package com.libragraph.vault.core.event;

import com.libragraph.vault.util.BlobRef;

public record ObjectCreatedEvent(BlobRef ref, long blobId) {}
