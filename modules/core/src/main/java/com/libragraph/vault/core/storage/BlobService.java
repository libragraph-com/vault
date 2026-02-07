package com.libragraph.vault.core.storage;

import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

/**
 * Thin facade over ObjectStorage.
 *
 * <p>Future hook point for metrics, caching, and validation.
 */
@ApplicationScoped
public class BlobService {

    @Inject
    ObjectStorage storage;

    public Uni<BinaryData> retrieve(UUID tenantId, BlobRef ref) {
        return storage.read(tenantId, ref);
    }

    public Uni<Void> store(UUID tenantId, BlobRef ref, BinaryData data, String mimeType) {
        return storage.write(tenantId, ref, data, mimeType);
    }

    public Uni<Boolean> exists(UUID tenantId, BlobRef ref) {
        return storage.exists(tenantId, ref);
    }

    public Uni<Void> delete(UUID tenantId, BlobRef ref) {
        return storage.delete(tenantId, ref);
    }

    public Uni<Void> deleteTenant(UUID tenantId) {
        return storage.deleteTenant(tenantId);
    }

    public Multi<UUID> listTenants() {
        return storage.listTenants();
    }

    public Multi<BlobRef> listContainers(UUID tenantId) {
        return storage.listContainers(tenantId);
    }
}
