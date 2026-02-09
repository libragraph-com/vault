package com.libragraph.vault.test;

import com.libragraph.vault.core.storage.TenantStorageResolver;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests TenantStorageResolver mapping from DB tenant ID to storage key.
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class TenantStorageResolverTest {

    @Inject
    TenantStorageResolver resolver;

    @Inject
    Jdbi jdbi;

    @AfterEach
    void tearDown() {
        resolver.clearCache();
    }

    @Test
    void resolveWithGlobalId_returnsUuidString() {
        UUID globalId = UUID.randomUUID();

        int tenantId = jdbi.withHandle(h -> {
            // Create org
            h.createUpdate("INSERT INTO organization (name) VALUES (:name) ON CONFLICT (name) DO NOTHING")
                    .bind("name", "resolver-test-org-1")
                    .execute();
            int orgId = h.createQuery("SELECT id FROM organization WHERE name = 'resolver-test-org-1'")
                    .mapTo(Integer.class).one();

            // Create tenant with global_id
            h.createUpdate("INSERT INTO tenant (org_id, name, global_id) VALUES (:orgId, :name, :globalId) " +
                            "ON CONFLICT (org_id, name) DO UPDATE SET global_id = :globalId")
                    .bind("orgId", orgId)
                    .bind("name", "resolver-tenant-uuid")
                    .bind("globalId", globalId)
                    .execute();

            return h.createQuery("SELECT id FROM tenant WHERE name = 'resolver-tenant-uuid'")
                    .mapTo(Integer.class).one();
        });

        String storageKey = resolver.resolve(tenantId);
        assertThat(storageKey).isEqualTo(globalId.toString());
    }

    @Test
    void resolveWithoutGlobalId_returnsIdString() {
        int tenantId = jdbi.withHandle(h -> {
            // Create org
            h.createUpdate("INSERT INTO organization (name) VALUES (:name) ON CONFLICT (name) DO NOTHING")
                    .bind("name", "resolver-test-org-2")
                    .execute();
            int orgId = h.createQuery("SELECT id FROM organization WHERE name = 'resolver-test-org-2'")
                    .mapTo(Integer.class).one();

            // Create tenant without global_id
            h.createUpdate("INSERT INTO tenant (org_id, name) VALUES (:orgId, :name) " +
                            "ON CONFLICT (org_id, name) DO NOTHING")
                    .bind("orgId", orgId)
                    .bind("name", "resolver-tenant-no-uuid")
                    .execute();

            return h.createQuery("SELECT id FROM tenant WHERE name = 'resolver-tenant-no-uuid'")
                    .mapTo(Integer.class).one();
        });

        String storageKey = resolver.resolve(tenantId);
        assertThat(storageKey).isEqualTo(String.valueOf(tenantId));
    }

    @Test
    void resolve_cachesResult() {
        int tenantId = jdbi.withHandle(h -> {
            h.createUpdate("INSERT INTO organization (name) VALUES (:name) ON CONFLICT (name) DO NOTHING")
                    .bind("name", "resolver-test-org-3")
                    .execute();
            int orgId = h.createQuery("SELECT id FROM organization WHERE name = 'resolver-test-org-3'")
                    .mapTo(Integer.class).one();

            h.createUpdate("INSERT INTO tenant (org_id, name) VALUES (:orgId, :name) " +
                            "ON CONFLICT (org_id, name) DO NOTHING")
                    .bind("orgId", orgId)
                    .bind("name", "resolver-cache-test")
                    .execute();

            return h.createQuery("SELECT id FROM tenant WHERE name = 'resolver-cache-test'")
                    .mapTo(Integer.class).one();
        });

        // First call
        String first = resolver.resolve(tenantId);

        // Update the global_id in DB — cached result should not change
        UUID newGlobalId = UUID.randomUUID();
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE tenant SET global_id = :gid WHERE id = :id")
                        .bind("gid", newGlobalId)
                        .bind("id", tenantId)
                        .execute());

        String second = resolver.resolve(tenantId);
        assertThat(second)
                .as("Cached value should be returned")
                .isEqualTo(first);

        // After clearing cache, should get updated value
        resolver.clearCache();
        String third = resolver.resolve(tenantId);
        assertThat(third).isEqualTo(newGlobalId.toString());
    }
}
