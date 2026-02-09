package com.libragraph.vault.test;

import com.libragraph.vault.core.storage.TenantStorageResolver;
import com.libragraph.vault.core.task.TaskStatus;
import com.libragraph.vault.core.test.VaultTestConfig;
import com.libragraph.vault.util.ContentHash;
import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.RamBuffer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the ingestion pipeline deduplicates blobs correctly.
 *
 * <p>Dedup is tested within a single ingestion: when two entries in a ZIP
 * have identical content, only one blob_ref row should be created.
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class IngestDedupTest {

    @Inject
    TestTaskRunner taskRunner;

    @Inject
    VaultTestConfig testConfig;

    @Inject
    TenantStorageResolver tenantResolver;

    @Inject
    Jdbi jdbi;

    int dbTenantId;
    String storageTenantId;

    @BeforeEach
    void setUp() {
        dbTenantId = testConfig.ensureTestTenant();
        storageTenantId = tenantResolver.resolve(dbTenantId);
    }

    @Test
    void ingestZipWithDuplicateFiles_deduplicatesCommonLeaves() throws Exception {
        // Build a ZIP where two entries have identical content
        String sharedContent = "this content appears twice in dedup test " + System.nanoTime();
        BinaryData zipData = new TestZipBuilder()
                .addFile("file-a.txt", sharedContent)
                .addFile("file-b.txt", sharedContent)
                .addFile("unique.txt", "only appears once " + System.nanoTime())
                .buildBinaryData();

        // Compute the expected hash of the shared content
        byte[] sharedBytes = sharedContent.getBytes(StandardCharsets.UTF_8);
        BinaryData sharedData = new RamBuffer(sharedBytes, null);
        ContentHash sharedHash = sharedData.hash();

        // Count blob_refs for this specific hash before ingest
        long countBefore = countBlobRefsForHash(sharedHash);

        int taskId = taskRunner.ingest(zipData, "archive.zip", storageTenantId, dbTenantId);
        assertThat(taskRunner.getTask(taskId).status()).isEqualTo(TaskStatus.COMPLETE);

        // Count blob_refs for the shared hash after ingest
        long countAfter = countBlobRefsForHash(sharedHash);

        // Should have exactly one new blob_ref (not two, despite two entries sharing it)
        assertThat(countAfter - countBefore)
                .as("Identical content should produce exactly one blob_ref, not one per entry")
                .isEqualTo(1);
    }

    @Test
    void ingestZipWithUniqueFiles_createsDistinctBlobRefs() throws Exception {
        String unique1 = "unique content one " + System.nanoTime();
        String unique2 = "unique content two " + System.nanoTime();
        BinaryData zipData = new TestZipBuilder()
                .addFile("one.txt", unique1)
                .addFile("two.txt", unique2)
                .buildBinaryData();

        // Compute expected hashes
        ContentHash hash1 = new RamBuffer(unique1.getBytes(StandardCharsets.UTF_8), null).hash();
        ContentHash hash2 = new RamBuffer(unique2.getBytes(StandardCharsets.UTF_8), null).hash();

        int taskId = taskRunner.ingest(zipData, "archive.zip", storageTenantId, dbTenantId);
        assertThat(taskRunner.getTask(taskId).status()).isEqualTo(TaskStatus.COMPLETE);

        // Each unique file should have its own blob_ref
        assertThat(countBlobRefsForHash(hash1)).isEqualTo(1);
        assertThat(countBlobRefsForHash(hash2)).isEqualTo(1);
    }

    private long countBlobRefsForHash(ContentHash hash) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM blob_ref WHERE content_hash = :hash")
                        .bind("hash", hash.bytes())
                        .mapTo(Long.class).one());
    }
}
