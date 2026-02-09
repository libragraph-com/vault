package com.libragraph.vault.core.storage;

import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Blob storage abstraction for tenant-isolated, content-addressed persistence.
 *
 * <p>Implementations decide compression strategy transparently —
 * callers always provide and receive uncompressed data.
 *
 * <p>Tenant IDs are opaque strings (typically UUID.toString() or DB ID strings).
 */
public interface ObjectStorage {

    /**
     * Reads a blob, returning uncompressed data.
     *
     * @throws BlobNotFoundException if the blob does not exist
     * @throws StorageException on I/O errors
     */
    Uni<BinaryData> read(String tenantId, BlobRef ref);

    /**
     * Creates a blob. Write-once — callers must not overwrite existing keys.
     * Driver decides compression based on mimeType hint.
     *
     * @param mimeType optional hint for compression decision (may be null)
     * @throws StorageException on I/O errors
     */
    Uni<Void> create(String tenantId, BlobRef ref, BinaryData data, String mimeType);

    /**
     * Checks whether a blob exists.
     */
    Uni<Boolean> exists(String tenantId, BlobRef ref);

    /**
     * Deletes a blob.
     *
     * @throws BlobNotFoundException if the blob does not exist
     * @throws StorageException on I/O errors
     */
    Uni<Void> delete(String tenantId, BlobRef ref);

    /**
     * Deletes a tenant's entire storage (all blobs and the container/bucket).
     *
     * @throws StorageException on I/O errors
     */
    Uni<Void> deleteTenant(String tenantId);

    /**
     * Lists all tenant IDs that have stored blobs.
     */
    Multi<String> listTenants();

    /**
     * Lists container (manifest) BlobRefs for a tenant.
     * Only returns blobs where {@code ref.isContainer() == true}.
     */
    Multi<BlobRef> listContainers(String tenantId);
}
