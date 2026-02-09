package com.libragraph.vault.test;

import com.libragraph.vault.core.dao.BlobDao;
import com.libragraph.vault.core.dao.BlobRecord;
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
 * End-to-end ingestion test: ZIP → ingest → verify DB rows + manifest in storage.
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class IngestContainerTest {

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
    void ingestSimpleZip_createsAllDbRowsAndManifest() throws Exception {
        // Build a simple ZIP with 2 files
        BinaryData zipData = new TestZipBuilder()
                .addFile("hello.txt", "Hello, World!")
                .addFile("data.csv", "a,b,c\n1,2,3")
                .buildBinaryData();

        BlobRef rootRef = BlobRef.container(zipData.hash(), zipData.size());

        // Run ingestion — data passed directly, never stored as raw ZIP
        int taskId = taskRunner.ingest(zipData, "archive.zip", storageTenantId, dbTenantId);

        // Verify task completed
        TaskRecord task = taskRunner.awaitTask(taskId, Duration.ofSeconds(30));
        assertThat(task.status())
                .as("Task should be COMPLETE after ingestion")
                .isEqualTo(TaskStatus.COMPLETE);

        // Verify container blob_ref exists
        Optional<BlobRefRecord> containerRefRecord = jdbi.withHandle(h -> {
            BlobRefDao dao = h.attach(BlobRefDao.class);
            return dao.findByRef(rootRef.hash().bytes(), rootRef.leafSize(), true);
        });
        assertThat(containerRefRecord).isPresent();
        assertThat(containerRefRecord.get().container()).isTrue();

        // Verify blob (tenant ownership) exists for container
        long containerBlobRefId = containerRefRecord.get().id();
        Optional<BlobRecord> containerBlob = jdbi.withHandle(h -> {
            BlobDao dao = h.attach(BlobDao.class);
            return dao.findByTenantAndRef(dbTenantId, containerBlobRefId);
        });
        assertThat(containerBlob).isPresent();

        // Verify container row exists
        long containerBlobId = containerBlob.get().id();
        Optional<ContainerRecord> container = jdbi.withHandle(h -> {
            ContainerDao dao = h.attach(ContainerDao.class);
            return dao.findByBlobId(containerBlobId);
        });
        assertThat(container).isPresent();
        assertThat(container.get().entryCount()).isEqualTo(2);

        // Verify entry rows exist
        List<EntryRecord> entries = jdbi.withHandle(h -> {
            EntryDao dao = h.attach(EntryDao.class);
            return dao.findByContainer(containerBlobId);
        });
        assertThat(entries).hasSize(2);
        List<String> paths = entries.stream().map(EntryRecord::internalPath).sorted().toList();
        assertThat(paths).containsExactly("data.csv", "hello.txt");

        // Verify each leaf has blob_ref + blob rows
        for (EntryRecord entry : entries) {
            Optional<BlobRecord> leafBlob = jdbi.withHandle(h -> {
                BlobDao dao = h.attach(BlobDao.class);
                return dao.findById(entry.blobId());
            });
            assertThat(leafBlob)
                    .as("Leaf blob should exist for entry: " + entry.internalPath())
                    .isPresent();
            assertThat(leafBlob.get().tenantId()).isEqualTo(dbTenantId);

            Optional<BlobRefRecord> leafRefRecord = jdbi.withHandle(h -> {
                BlobRefDao dao = h.attach(BlobRefDao.class);
                return dao.findById(leafBlob.get().blobRefId());
            });
            assertThat(leafRefRecord)
                    .as("Leaf blob_ref should exist for entry: " + entry.internalPath())
                    .isPresent();
            assertThat(leafRefRecord.get().container()).isFalse();
        }

        // Verify manifest stored in ObjectStorage at container BlobRef key
        // The container ref we used to store the original ZIP is different from
        // the container ref that BuildManifestHandler creates (which is based on
        // the original ZIP hash). The manifest is stored at the container BlobRef key.
        Boolean manifestExists = storage.exists(storageTenantId, rootRef).await().indefinitely();
        assertThat(manifestExists)
                .as("Manifest should be stored in ObjectStorage at container BlobRef key")
                .isTrue();
    }

    @Test
    void ingestZipWithDirectories_createsDirectoryEntries() throws Exception {
        BinaryData zipData = new TestZipBuilder()
                .addDirectory("docs/")
                .addFile("docs/readme.txt", "Read me")
                .addFile("src/main.java", "class Main {}")
                .buildBinaryData();

        BlobRef rootRef = BlobRef.container(zipData.hash(), zipData.size());

        int taskId = taskRunner.ingest(zipData, "archive.zip", storageTenantId, dbTenantId);

        TaskRecord task = taskRunner.awaitTask(taskId, Duration.ofSeconds(30));
        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETE);

        // Find the container blob_id for querying entries
        long containerBlobId = lookupContainerBlobId(rootRef);

        List<EntryRecord> entries = jdbi.withHandle(h -> {
            EntryDao dao = h.attach(EntryDao.class);
            return dao.findByContainer(containerBlobId);
        });

        // Should have 3 entries: docs/, docs/readme.txt, src/main.java
        assertThat(entries).hasSize(3);

        // Verify directory entry
        List<EntryRecord> dirEntries = entries.stream()
                .filter(e -> e.entryTypeId() == 1).toList();
        assertThat(dirEntries).hasSize(1);
        assertThat(dirEntries.getFirst().internalPath()).isEqualTo("docs/");
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
