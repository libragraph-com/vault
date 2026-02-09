package com.libragraph.vault.core.storage;

import com.libragraph.vault.util.BlobRef;

/**
 * Thrown when a read or delete targets a blob that does not exist.
 */
public class BlobNotFoundException extends RuntimeException {

    private final String tenantId;
    private final BlobRef ref;

    public BlobNotFoundException(String tenantId, BlobRef ref) {
        super("Blob not found: tenant=" + tenantId + " ref=" + ref);
        this.tenantId = tenantId;
        this.ref = ref;
    }

    public String tenantId() {
        return tenantId;
    }

    public BlobRef ref() {
        return ref;
    }
}
