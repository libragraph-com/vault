package com.libragraph.vault.test;

import com.libragraph.vault.core.dao.BlobDao;
import com.libragraph.vault.core.dao.BlobRefDao;
import com.libragraph.vault.core.dao.BlobRefRecord;
import com.libragraph.vault.core.dao.ContainerDao;
import com.libragraph.vault.core.dao.ContainerRecord;
import com.libragraph.vault.core.dao.EntryDao;
import com.libragraph.vault.core.dao.EntryRecord;
import com.libragraph.vault.core.storage.ObjectStorage;
import com.libragraph.vault.core.storage.TenantStorageResolver;
import com.libragraph.vault.core.task.TaskRecord;
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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests nested container ingestion: ZIP-in-ZIP.
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class IngestNestedContainerTest {

    @Inject
    ObjectStorage storage;

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
    void ingestNestedZip_createsNestedContainerAndEntries() throws Exception {
        // Build inner ZIP
        TestZipBuilder inner = new TestZipBuilder()
                .addFile("nested-file.txt", "I am nested");

        // Build outer ZIP containing the inner ZIP
        BinaryData outerData = new TestZipBuilder()
                .addFile("readme.txt", "Top-level file")
                .addNestedZip("inner.zip", inner)
                .buildBinaryData();

        BlobRef outerRef = BlobRef.container(outerData.hash(), outerData.size());

        // Ingest — data passed directly, never stored as raw ZIP
        int taskId = taskRunner.ingest(outerData, "outer.zip", storageTenantId, dbTenantId);

        TaskRecord task = taskRunner.awaitTask(taskId, Duration.ofSeconds(30));
        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETE);

        // Verify outer container exists
        long outerBlobId = lookupContainerBlobId(outerRef);
        Optional<ContainerRecord> outerContainer = jdbi.withHandle(h ->
                h.attach(ContainerDao.class).findByBlobId(outerBlobId));
        assertThat(outerContainer).isPresent();
        assertThat(outerContainer.get().entryCount()).isEqualTo(2); // readme.txt + inner.zip

        // Verify outer entries
        List<EntryRecord> outerEntries = jdbi.withHandle(h ->
                h.attach(EntryDao.class).findByContainer(outerBlobId));
        assertThat(outerEntries).hasSize(2);

        List<String> outerPaths = outerEntries.stream()
                .map(EntryRecord::internalPath).sorted().toList();
        assertThat(outerPaths).containsExactly("inner.zip", "readme.txt");

        // Find the inner container entry — it should be marked as a container (isContainer=true in blob_ref)
        EntryRecord innerEntry = outerEntries.stream()
                .filter(e -> e.internalPath().equals("inner.zip"))
                .findFirst().orElseThrow();

        // Look up the blob_ref for the inner entry — it should be a container
        Optional<BlobRefRecord> innerBlobRef = jdbi.withHandle(h -> {
            long blobRefId = h.attach(BlobDao.class).findById(innerEntry.blobId())
                    .orElseThrow().blobRefId();
            return h.attach(BlobRefDao.class).findById(blobRefId);
        });
        assertThat(innerBlobRef).isPresent();
        assertThat(innerBlobRef.get().container())
                .as("inner.zip should be marked as a container")
                .isTrue();

        // Verify inner container has its own container row
        Optional<ContainerRecord> innerContainer = jdbi.withHandle(h ->
                h.attach(ContainerDao.class).findByBlobId(innerEntry.blobId()));
        assertThat(innerContainer).isPresent();
        assertThat(innerContainer.get().entryCount()).isEqualTo(1); // nested-file.txt

        // Verify inner container entries
        List<EntryRecord> innerEntries = jdbi.withHandle(h ->
                h.attach(EntryDao.class).findByContainer(innerEntry.blobId()));
        assertThat(innerEntries).hasSize(1);
        assertThat(innerEntries.getFirst().internalPath()).isEqualTo("nested-file.txt");

        // Verify both manifests stored in ObjectStorage
        Boolean outerManifestExists = storage.exists(storageTenantId, outerRef).await().indefinitely();
        assertThat(outerManifestExists).isTrue();

        // Inner container manifest should also be stored
        List<BlobRef> containers = storage.listContainers(storageTenantId)
                .collect().asList().await().indefinitely();
        assertThat(containers.size()).isGreaterThanOrEqualTo(2);
    }

    private long lookupContainerBlobId(BlobRef containerRef) {
        return jdbi.withHandle(h -> {
            BlobRefDao refDao = h.attach(BlobRefDao.class);
            long blobRefId = refDao.findByRef(
                    containerRef.hash().bytes(), containerRef.leafSize(), true)
                    .orElseThrow().id();
            BlobDao blobDao = h.attach(BlobDao.class);
            return blobDao.findByTenantAndRef(dbTenantId, blobRefId)
                    .orElseThrow().id();
        });
    }
}
