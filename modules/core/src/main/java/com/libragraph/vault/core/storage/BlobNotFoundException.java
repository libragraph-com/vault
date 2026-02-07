package com.libragraph.vault.core.storage;

import com.libragraph.vault.util.BlobRef;

import java.util.UUID;

/**
 * Thrown when a read or delete targets a blob that does not exist.
 */
public class BlobNotFoundException extends RuntimeException {

    private final UUID tenantId;
    private final BlobRef ref;

    public BlobNotFoundException(UUID tenantId, BlobRef ref) {
        super("Blob not found: tenant=" + tenantId + " ref=" + ref);
        this.tenantId = tenantId;
        this.ref = ref;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public BlobRef ref() {
        return ref;
    }
}
