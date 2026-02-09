package com.libragraph.vault.test;

import com.libragraph.vault.core.dao.BlobDao;
import com.libragraph.vault.core.dao.BlobRefDao;
import com.libragraph.vault.core.dao.BlobRefRecord;
import com.libragraph.vault.core.dao.ContainerDao;
import com.libragraph.vault.core.dao.ContainerRecord;
import com.libragraph.vault.core.dao.EntryDao;
import com.libragraph.vault.core.dao.EntryRecord;
import com.libragraph.vault.core.rebuild.RebuildSqlTask;
import com.libragraph.vault.core.storage.TenantStorageResolver;
import com.libragraph.vault.core.task.TaskContext;
import com.libragraph.vault.core.task.TaskOutcome;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves ObjectStorage is truth: ingest → truncate SQL → rebuild → verify rows recreated.
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class RebuildSqlTest {

    @Inject
    TestTaskRunner taskRunner;

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
        // Ingest a ZIP
        BinaryData zipData = new TestZipBuilder()
                .addFile("rebuild-a.txt", "File A for rebuild test")
                .addFile("rebuild-b.txt", "File B for rebuild test")
                .buildBinaryData();

        BlobRef rootRef = BlobRef.container(zipData.hash(), zipData.size());

        int taskId = taskRunner.ingest(zipData, "archive.zip", storageTenantId, dbTenantId);
        assertThat(taskRunner.getTask(taskId).status()).isEqualTo(TaskStatus.COMPLETE);

        // Truncate index tables (not tenant/org)
        jdbi.useHandle(h -> h.createUpdate(
                "TRUNCATE entry, container, blob_content, blob, blob_ref CASCADE").execute());

        // Verify tables are empty
        assertThat(countTable("blob_ref")).isZero();
        assertThat(countTable("blob")).isZero();
        assertThat(countTable("container")).isZero();
        assertThat(countTable("entry")).isZero();

        // Clear TenantStorageResolver cache so it re-resolves
        tenantResolver.clearCache();

        // Run rebuild
        @SuppressWarnings("unchecked")
        TaskOutcome outcome = rebuildSqlTask.onStart(
                Map.of("truncateFirst", false), new StubTaskContext(dbTenantId));

        assertThat(outcome).isInstanceOf(TaskOutcome.Complete.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) ((TaskOutcome.Complete) outcome).output();
        assertThat((int) result.get("containers")).isGreaterThanOrEqualTo(1);
        assertThat((int) result.get("entries")).isGreaterThanOrEqualTo(2);

        // Verify our specific container was rebuilt
        Optional<BlobRefRecord> rebuiltRef = jdbi.withHandle(h ->
                h.attach(BlobRefDao.class).findByRef(
                        rootRef.hash().bytes(), rootRef.leafSize(), true));
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

        // Verify leaf blob_ref rows were also rebuilt
        for (EntryRecord entry : rebuiltEntries) {
            Optional<com.libragraph.vault.core.dao.BlobRecord> leafBlob = jdbi.withHandle(h ->
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
        @Override public int createSubtask(Class<? extends com.libragraph.vault.core.task.VaultTask> c, Object i) { throw new UnsupportedOperationException(); }
        @Override public int createSubtask(Class<? extends com.libragraph.vault.core.task.VaultTask> c, Object i, int p) { throw new UnsupportedOperationException(); }
        @Override public <O> O getSubtaskResult(int id, Class<O> t) { throw new UnsupportedOperationException(); }
        @Override public Optional<com.libragraph.vault.core.task.TaskError> getSubtaskError(int id) { throw new UnsupportedOperationException(); }
        @Override public <O> List<O> getCompletedSubtasks(Class<O> t) { throw new UnsupportedOperationException(); }
    }
}
