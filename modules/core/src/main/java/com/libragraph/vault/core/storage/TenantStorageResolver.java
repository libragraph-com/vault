package com.libragraph.vault.core.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps DB tenant IDs (int FK) to ObjectStorage tenant keys (String).
 *
 * <p>Resolution: if the tenant has a global_id (UUID), uses that; otherwise
 * falls back to the string representation of the DB serial ID.
 */
@ApplicationScoped
public class TenantStorageResolver {

    @Inject
    Jdbi jdbi;

    private final ConcurrentHashMap<Integer, String> cache = new ConcurrentHashMap<>();

    /**
     * Resolves a DB tenant ID to the storage key used as ObjectStorage tenantId.
     */
    public String resolve(int tenantId) {
        return cache.computeIfAbsent(tenantId, id ->
                jdbi.withHandle(h -> {
                    UUID globalId = h.createQuery(
                                    "SELECT global_id FROM tenant WHERE id = :id")
                            .bind("id", id)
                            .mapTo(UUID.class)
                            .findFirst()
                            .orElse(null);
                    return globalId != null ? globalId.toString() : String.valueOf(id);
                })
        );
    }

    /**
     * Clears the cache (for testing).
     */
    public void clearCache() {
        cache.clear();
    }
}
