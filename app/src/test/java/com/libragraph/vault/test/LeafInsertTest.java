package com.libragraph.vault.test;

import com.libragraph.vault.core.db.DatabaseService;
import com.libragraph.vault.core.test.VaultTestConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema smoke test — validates that the V1 migration runs correctly
 * by inserting into the new content-addressed storage tables.
 */
@QuarkusTest
class LeafInsertTest {

    @Inject
    DatabaseService databaseService;

    @Inject
    VaultTestConfig testConfig;

    @Test
    void insertBlobRefAndVerify() {
        var jdbi = databaseService.jdbi();
        int tenantId = testConfig.ensureTestTenant();

        // Generate a random 16-byte BLAKE3-128 hash
        byte[] hash = new byte[16];
        new SecureRandom().nextBytes(hash);
        long leafSize = 42L;

        jdbi.useHandle(h -> {
            // Insert into blob_ref
            long blobRefId = h.createUpdate(
                    "INSERT INTO blob_ref (content_hash, leaf_size, container, mime_type) " +
                    "VALUES (:hash, :size, false, :mime)")
                    .bind("hash", hash)
                    .bind("size", leafSize)
                    .bind("mime", "text/plain")
                    .executeAndReturnGeneratedKeys("id")
                    .mapTo(Long.class)
                    .one();

            // Insert into blob (tenant-scoped)
            long blobId = h.createUpdate(
                    "INSERT INTO blob (tenant_id, blob_ref_id) VALUES (:tenantId, :blobRefId)")
                    .bind("tenantId", tenantId)
                    .bind("blobRefId", blobRefId)
                    .executeAndReturnGeneratedKeys("id")
                    .mapTo(Long.class)
                    .one();

            assertThat(blobId).isPositive();

            // Read back
            byte[] found = h.createQuery(
                    "SELECT br.content_hash FROM blob b " +
                    "JOIN blob_ref br ON br.id = b.blob_ref_id " +
                    "WHERE b.id = :blobId")
                    .bind("blobId", blobId)
                    .mapTo(byte[].class)
                    .one();

            assertThat(found).isEqualTo(hash);
        });
    }

    @Test
    void schemaTablesExist() {
        var jdbi = databaseService.jdbi();

        // Verify key tables exist by querying them
        String[] tables = {
            "organization", "tenant", "blob_ref", "blob", "blob_content",
            "container", "entry", "node", "task_status", "task",
            "task_task_dep", "task_resource", "task_resource_dep",
            "format_handler", "entry_type"
        };

        for (String table : tables) {
            long count = jdbi.withHandle(h ->
                    h.createQuery("SELECT COUNT(*) FROM " + table)
                            .mapTo(Long.class).one());
            assertThat(count).as("Table '%s' should be queryable", table)
                    .isGreaterThanOrEqualTo(0);
        }
    }
}
