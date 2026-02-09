package com.libragraph.vault.core.storage;

import com.libragraph.vault.util.BlobRef;

public class BlobAlreadyExistsException extends StorageException {

    public BlobAlreadyExistsException(String tenantId, BlobRef ref) {
        super("Blob already exists: tenant=" + tenantId + " ref=" + ref);
    }
}
