package com.libragraph.vault.test;

import com.libragraph.vault.core.dao.BlobDao;
import com.libragraph.vault.core.dao.BlobRecord;
import com.libragraph.vault.core.dao.BlobRefDao;
import com.libragraph.vault.core.dao.BlobRefRecord;
import com.libragraph.vault.core.dao.ContainerDao;
import com.libragraph.vault.core.dao.ContainerRecord;
import com.libragraph.vault.core.dao.EntryDao;
import com.libragraph.vault.core.dao.EntryRecord;
import com.libragraph.vault.core.event.ChildResult;
import com.libragraph.vault.core.ingest.ManifestManager;
import com.libragraph.vault.core.rebuild.RebuildSqlTask;
import com.libragraph.vault.core.storage.ObjectStorage;
import com.libragraph.vault.core.storage.TenantStorageResolver;
import com.libragraph.vault.core.task.TaskContext;
import com.libragraph.vault.core.task.TaskError;
import com.libragraph.vault.core.task.TaskOutcome;
import com.libragraph.vault.core.task.VaultTask;
import com.libragraph.vault.core.test.VaultTestConfig;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.Buffer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves ObjectStorage is truth: set up storage directly → truncate SQL → rebuild → verify rows.
 * No ingestion pipeline or BlobInserter involved.
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class RebuildSqlTest {

    @Inject
    ObjectStorage storage;

    @Inject
    ManifestManager manifestManager;

    @Inject
    RebuildSqlTask rebuildSqlTask;

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
    void rebuildSql_recreatesAllRows() throws Exception {
        // Unique content per run — avoids write-once conflicts on persistent dev profile
        String unique = String.valueOf(System.nanoTime());

        // Create leaf blobs directly
        BinaryData dataA = toBuffer(("rebuild-a " + unique).getBytes(StandardCharsets.UTF_8));
        BinaryData dataB = toBuffer(("rebuild-b " + unique).getBytes(StandardCharsets.UTF_8));
        BlobRef refA = BlobRef.leaf(dataA.hash(), dataA.size());
        BlobRef refB = BlobRef.leaf(dataB.hash(), dataB.size());

        // Store leaves in ObjectStorage — no BlobInserter, no DB rows
        storage.create(storageTenantId, refA, dataA, "text/plain").await().indefinitely();
        storage.create(storageTenantId, refB, dataB, "text/plain").await().indefinitely();

        // Build a container ref from unique content (never stored as raw data)
        BinaryData containerContent = toBuffer(("container-" + unique).getBytes(StandardCharsets.UTF_8));
        BlobRef containerRef = BlobRef.container(containerContent.hash(), containerContent.size());

        // Build and store manifest directly — no ingestion pipeline
        List<ChildResult> children = List.of(
                new ChildResult(refA, "rebuild-a.txt", false, (short) 0, null, null),
                new ChildResult(refB, "rebuild-b.txt", false, (short) 0, null, null));
        BinaryData manifestData = manifestManager.build(containerRef, "zip", null, children);
        manifestManager.store(storageTenantId, containerRef, manifestData);

        // Truncate index tables (not tenant/org)
        jdbi.useHandle(h -> h.createUpdate(
                "TRUNCATE entry, container, blob_content, blob, blob_ref CASCADE").execute());

        assertThat(countTable("blob_ref")).isZero();
        assertThat(countTable("blob")).isZero();
        assertThat(countTable("container")).isZero();
        assertThat(countTable("entry")).isZero();

        // Run rebuild
        tenantResolver.clearCache();
        @SuppressWarnings("unchecked")
        TaskOutcome outcome = rebuildSqlTask.onStart(
                Map.of("truncateFirst", false), new StubTaskContext(dbTenantId));

        assertThat(outcome).isInstanceOf(TaskOutcome.Complete.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) ((TaskOutcome.Complete) outcome).output();
        assertThat((int) result.get("containers")).isGreaterThanOrEqualTo(1);
        assertThat((int) result.get("entries")).isGreaterThanOrEqualTo(2);

        // Verify container blob_ref rebuilt
        Optional<BlobRefRecord> rebuiltRef = jdbi.withHandle(h ->
                h.attach(BlobRefDao.class).findByRef(
                        containerRef.hash().bytes(), containerRef.leafSize(), true));
        assertThat(rebuiltRef)
                .as("Container blob_ref should be rebuilt")
                .isPresent();

        long rebuiltBlobRefId = rebuiltRef.get().id();
        long rebuiltBlobId = jdbi.withHandle(h ->
                h.attach(BlobDao.class).findByTenantAndRef(dbTenantId, rebuiltBlobRefId)
                        .orElseThrow().id());

        Optional<ContainerRecord> rebuiltContainer = jdbi.withHandle(h ->
                h.attach(ContainerDao.class).findByBlobId(rebuiltBlobId));
        assertThat(rebuiltContainer)
                .as("Container row should be rebuilt")
                .isPresent();
        assertThat(rebuiltContainer.get().entryCount()).isEqualTo(2);

        List<EntryRecord> rebuiltEntries = jdbi.withHandle(h ->
                h.attach(EntryDao.class).findByContainer(rebuiltBlobId));
        assertThat(rebuiltEntries).hasSize(2);
        List<String> paths = rebuiltEntries.stream()
                .map(EntryRecord::internalPath).sorted().toList();
        assertThat(paths).containsExactly("rebuild-a.txt", "rebuild-b.txt");

        // Verify leaf blob_ref rows rebuilt
        for (EntryRecord entry : rebuiltEntries) {
            Optional<BlobRecord> leafBlob = jdbi.withHandle(h ->
                    h.attach(BlobDao.class).findById(entry.blobId()));
            assertThat(leafBlob)
                    .as("Leaf blob should be rebuilt for: " + entry.internalPath())
                    .isPresent();

            Optional<BlobRefRecord> leafRef = jdbi.withHandle(h ->
                    h.attach(BlobRefDao.class).findById(leafBlob.get().blobRefId()));
            assertThat(leafRef)
                    .as("Leaf blob_ref should be rebuilt for: " + entry.internalPath())
                    .isPresent();
            assertThat(leafRef.get().container()).isFalse();
        }
    }

    private BinaryData toBuffer(byte[] content) throws IOException {
        Buffer buf = Buffer.allocate(content.length);
        buf.write(ByteBuffer.wrap(content));
        buf.position(0);
        return buf;
    }

    private long countTable(String table) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM " + table)
                        .mapTo(Long.class).one());
    }

    /**
     * Minimal TaskContext stub for directly invoking VaultTask.onStart() in tests.
     */
    private record StubTaskContext(int tenantId) implements TaskContext {
        @Override public int taskId() { return -1; }
        @Override public String taskType() { return "rebuild.sql"; }
        @Override public int createSubtask(Class<? extends VaultTask> c, Object i) { throw new UnsupportedOperationException(); }
        @Override public int createSubtask(Class<? extends VaultTask> c, Object i, int p) { throw new UnsupportedOperationException(); }
        @Override public <O> O getSubtaskResult(int id, Class<O> t) { throw new UnsupportedOperationException(); }
        @Override public Optional<TaskError> getSubtaskError(int id) { throw new UnsupportedOperationException(); }
        @Override public <O> List<O> getCompletedSubtasks(Class<O> t) { throw new UnsupportedOperationException(); }
    }
}
