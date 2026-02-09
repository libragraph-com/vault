package com.libragraph.vault.test;

import com.libragraph.vault.core.dao.BlobRefDao;
import com.libragraph.vault.core.dao.BlobRefRecord;
import com.libragraph.vault.core.dao.EntryDao;
import com.libragraph.vault.core.dao.EntryRecord;
import com.libragraph.vault.core.reconstruct.ReconstructionService;
import com.libragraph.vault.core.storage.ObjectStorage;
import com.libragraph.vault.core.storage.TenantStorageResolver;
import com.libragraph.vault.core.task.TaskStatus;
import com.libragraph.vault.core.test.VaultTestConfig;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for TIER_2 stored container handling.
 *
 * <p>Uses {@link Tier2TestHandlerFactory} which matches {@code .tier2} extension
 * and reports TIER_2 capabilities while delegating extraction to ZIP.
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
@Timeout(60)
class IngestTier2Test {

    @Inject
    ObjectStorage storage;

    @Inject
    TestTaskRunner taskRunner;

    @Inject
    VaultTestConfig testConfig;

    @Inject
    TenantStorageResolver tenantResolver;

    @Inject
    ReconstructionService reconstructionService;

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
    void tier2_storesOriginalAsLeaf() {
        // Build ZIP, name it "archive.tier2"
        BinaryData data = new TestZipBuilder()
                .addFile("inner.txt", "hello")
                .buildBinaryData();
        BlobRef leafRef = BlobRef.leaf(data.hash(), data.size());
        BlobRef containerRef = BlobRef.container(data.hash(), data.size());

        int taskId = taskRunner.ingest(data, "archive.tier2", storageTenantId, dbTenantId);
        assertThat(taskRunner.awaitTask(taskId, Duration.ofSeconds(30)).status())
                .isEqualTo(TaskStatus.COMPLETE);

        // Leaf (original) exists in storage
        assertThat(storage.exists(storageTenantId, leafRef).await().indefinitely()).isTrue();
        // Container manifest (bonus) also exists
        assertThat(storage.exists(storageTenantId, containerRef).await().indefinitely()).isTrue();
    }

    @Test
    void tier2_parentManifestReferencesLeaf() {
        // Nest a .tier2 inside a regular ZIP
        byte[] innerBytes = new TestZipBuilder()
                .addFile("deep.txt", "deep content")
                .buildBytes();

        BinaryData outerData = new TestZipBuilder()
                .addFile("archive.tier2", innerBytes)
                .addFile("plain.txt", "plain content")
                .buildBinaryData();

        BlobRef outerContainerRef = BlobRef.container(outerData.hash(), outerData.size());

        int taskId = taskRunner.ingest(outerData, "outer.zip", storageTenantId, dbTenantId);
        assertThat(taskRunner.awaitTask(taskId, Duration.ofSeconds(30)).status())
                .isEqualTo(TaskStatus.COMPLETE);

        // The outer container's entry for "archive.tier2" should reference a LEAF (not container)
        long outerBlobId = lookupContainerBlobId(outerContainerRef);
        List<EntryRecord> entries = jdbi.withHandle(h -> {
            EntryDao dao = h.attach(EntryDao.class);
            return dao.findByContainer(outerBlobId);
        });

        EntryRecord tier2Entry = entries.stream()
                .filter(e -> e.internalPath().equals("archive.tier2"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected entry 'archive.tier2'"));

        // Verify the blob_ref for this entry is a LEAF (is_container=false)
        Optional<BlobRefRecord> tier2BlobRef = jdbi.withHandle(h -> {
            com.libragraph.vault.core.dao.BlobDao blobDao = h.attach(com.libragraph.vault.core.dao.BlobDao.class);
            var blob = blobDao.findById(tier2Entry.blobId()).orElseThrow();
            BlobRefDao refDao = h.attach(BlobRefDao.class);
            return refDao.findById(blob.blobRefId());
        });
        assertThat(tier2BlobRef).isPresent();
        assertThat(tier2BlobRef.get().container())
                .as("TIER_2 entry in parent manifest should be a leaf, not container")
                .isFalse();
    }

    @Test
    void tier2_reconstructionReadsOriginal() throws IOException {
        // Build a .tier2 file (ZIP internally), ingest it as root
        BinaryData data = new TestZipBuilder()
                .addFile("readme.txt", "reconstruction test")
                .buildBinaryData();

        BlobRef containerRef = BlobRef.container(data.hash(), data.size());

        int taskId = taskRunner.ingest(data, "archive.tier2", storageTenantId, dbTenantId);
        assertThat(taskRunner.awaitTask(taskId, Duration.ofSeconds(30)).status())
                .isEqualTo(TaskStatus.COMPLETE);

        // Wait briefly for bonus decomposition to finish storing the container manifest
        awaitStorageExists(storageTenantId, containerRef, Duration.ofSeconds(10));

        // Reconstruct the container — should return the original file bytes
        BinaryData reconstructed = reconstructionService.reconstruct(storageTenantId, containerRef);
        byte[] originalBytes = readAll(data);
        byte[] reconstructedBytes = readAll(reconstructed);
        assertThat(reconstructedBytes).isEqualTo(originalBytes);
    }

    private void awaitStorageExists(String tenantId, BlobRef ref, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(storage.exists(tenantId, ref).await().indefinitely())) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("BlobRef " + ref + " did not appear in storage within " + timeout);
    }

    private long lookupContainerBlobId(BlobRef containerRef) {
        return jdbi.withHandle(h -> {
            BlobRefDao refDao = h.attach(BlobRefDao.class);
            long blobRefId = refDao.findByRef(
                    containerRef.hash().bytes(), containerRef.leafSize(), true)
                    .orElseThrow().id();
            com.libragraph.vault.core.dao.BlobDao blobDao = h.attach(com.libragraph.vault.core.dao.BlobDao.class);
            return blobDao.findByTenantAndRef(dbTenantId, blobRefId)
                    .orElseThrow().id();
        });
    }

    private byte[] readAll(BinaryData data) throws IOException {
        data.position(0);
        ByteBuffer buf = ByteBuffer.allocate((int) data.size());
        while (buf.hasRemaining()) {
            if (data.read(buf) == -1) break;
        }
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }
}
