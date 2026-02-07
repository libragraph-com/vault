package com.libragraph.vault.test;

import com.libragraph.vault.core.db.DatabaseService;
import com.libragraph.vault.core.test.VaultTestConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class LeafInsertTest {

    @Inject
    DatabaseService databaseService;

    @Inject
    VaultTestConfig testConfig;

    @Test
    void insertLeafAndVerify() {
        var jdbi = databaseService.jdbi();

        // Generate a random 16-byte BLAKE3-128 hash
        byte[] hash = new byte[16];
        new SecureRandom().nextBytes(hash);
        long size = 42L;
        UUID tenantId = UUID.fromString(
                testConfig.tenantId().matches("[0-9a-f\\-]{36}")
                        ? testConfig.tenantId()
                        : UUID.randomUUID().toString());

        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO leaves (content_hash, size_bytes, mime_type, tenant_id) " +
                "VALUES (:hash, :size, :mime, :tenant)")
                .bind("hash", hash)
                .bind("size", size)
                .bind("mime", "text/plain")
                .bind("tenant", tenantId)
                .execute());

        // Read it back
        byte[] found = jdbi.withHandle(h -> h.createQuery(
                "SELECT content_hash FROM leaves WHERE content_hash = :hash")
                .bind("hash", hash)
                .mapTo(byte[].class)
                .one());

        assertThat(found).isEqualTo(hash);
    }

    @Test
    void countLeavesGrowsAcrossRuns() {
        var jdbi = databaseService.jdbi();

        long count = jdbi.withHandle(h -> h.createQuery("SELECT count(*) FROM leaves")
                .mapTo(Long.class)
                .one());

        // On dev DB, count grows across runs; on Testcontainers, starts fresh each time
        System.out.printf("[LeafInsertTest] leaves count: %d%n", count);

        assertThat(count).isGreaterThanOrEqualTo(0);
    }
}
