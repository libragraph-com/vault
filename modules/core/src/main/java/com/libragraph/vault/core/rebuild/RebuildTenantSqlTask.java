package com.libragraph.vault.core.rebuild;

import com.libragraph.vault.core.dao.BlobDao;
import com.libragraph.vault.core.dao.BlobRefDao;
import com.libragraph.vault.core.dao.ContainerDao;
import com.libragraph.vault.core.dao.EntryDao;
import com.libragraph.vault.core.ingest.ManifestManager;
import com.libragraph.vault.core.storage.BlobService;
import com.libragraph.vault.core.storage.TenantStorageResolver;
import com.libragraph.vault.core.task.TaskContext;
import com.libragraph.vault.core.task.TaskIO;
import com.libragraph.vault.core.task.TaskOutcome;
import com.libragraph.vault.core.task.VaultTask;
import com.libragraph.vault.formats.proto.ManifestProto;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.ContentHash;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds SQL index rows from ObjectStorage for a single tenant.
 *
 * <p>Two-pass approach:
 * <ol>
 *   <li>Pass 1: Collect all BlobRefs from manifests and insert blob_ref + blob rows</li>
 *   <li>Pass 2: Insert container + entry relationships</li>
 * </ol>
 *
 * <p>Critical invariant: every blob is reachable from a container manifest.
 */
@ApplicationScoped
@TaskIO(input = Map.class, output = Map.class)
public class RebuildTenantSqlTask implements VaultTask {

    private static final Logger log = Logger.getLogger(RebuildTenantSqlTask.class);

    @Inject
    BlobService blobService;

    @Inject
    ManifestManager manifestManager;

    @Inject
    TenantStorageResolver tenantResolver;

    @Inject
    Jdbi jdbi;

    @Override
    public String taskType() {
        return "rebuild.tenant-sql";
    }

    @Override
    @SuppressWarnings("unchecked")
    public TaskOutcome onStart(Object input, TaskContext ctx) {
        Map<String, Object> inputMap = (Map<String, Object>) input;
        boolean truncateFirst = Boolean.TRUE.equals(inputMap.get("truncateFirst"));
        int tenantId = ctx.tenantId();
        String storageKey = tenantResolver.resolve(tenantId);

        if (truncateFirst) {
            log.infof("Deleting SQL rows for tenant %d before rebuild", tenantId);
            jdbi.useHandle(h -> {
                h.createUpdate("DELETE FROM entry WHERE container_blob_id IN " +
                        "(SELECT id FROM blob WHERE tenant_id = :tid)")
                        .bind("tid", tenantId).execute();
                h.createUpdate("DELETE FROM container WHERE blob_id IN " +
                        "(SELECT id FROM blob WHERE tenant_id = :tid)")
                        .bind("tid", tenantId).execute();
                h.createUpdate("DELETE FROM blob WHERE tenant_id = :tid")
                        .bind("tid", tenantId).execute();
                // blob_ref is shared cross-tenant — don't delete
            });
        }

        int totalContainers = 0;
        int totalEntries = 0;

        // Cache manifests for pass 2
        List<ManifestWithContext> manifestCache = new ArrayList<>();

        // Pass 1: Insert all blob_ref + blob rows for this tenant
        log.infof("Pass 1: Inserting blob_ref and blob rows for tenant %d (%s)", tenantId, storageKey);
        List<BlobRef> containers = blobService.listContainers(storageKey)
                .collect().asList().await().indefinitely();

        for (BlobRef containerRef : containers) {
            ManifestProto.Manifest manifest = manifestManager.load(storageKey, containerRef);

            // Insert the container's own blob_ref + blob
            jdbi.useHandle(h -> {
                BlobRefDao refDao = h.attach(BlobRefDao.class);
                long blobRefId = refDao.findOrInsert(containerRef, "application/x-protobuf");
                BlobDao blobDao = h.attach(BlobDao.class);
                blobDao.findOrInsert(tenantId, blobRefId);
            });

            // Insert each child's blob_ref + blob
            for (ManifestProto.ManifestEntry entry : manifest.getEntriesList()) {
                ContentHash childHash = new ContentHash(entry.getContentHash().toByteArray());
                BlobRef childRef = entry.getIsContainer()
                        ? BlobRef.container(childHash, entry.getLeafSize())
                        : BlobRef.leaf(childHash, entry.getLeafSize());

                jdbi.useHandle(h -> {
                    BlobRefDao refDao = h.attach(BlobRefDao.class);
                    long blobRefId = refDao.findOrInsert(childRef, null);
                    BlobDao blobDao = h.attach(BlobDao.class);
                    blobDao.findOrInsert(tenantId, blobRefId);
                });
            }

            manifestCache.add(new ManifestWithContext(containerRef, manifest));
            totalContainers++;
        }

        // Pass 2: Insert container + entry relationships
        log.infof("Pass 2: Inserting container and entry rows for tenant %d", tenantId);
        for (ManifestWithContext mc : manifestCache) {
            int entryCount = mc.manifest().getEntriesCount();

            // Look up container blob_id
            long containerBlobId = jdbi.withHandle(h -> {
                BlobRefDao refDao = h.attach(BlobRefDao.class);
                long blobRefId = refDao.findOrInsert(mc.containerRef(), null);
                BlobDao blobDao = h.attach(BlobDao.class);
                return blobDao.findOrInsert(tenantId, blobRefId);
            });

            // Insert container row
            jdbi.useHandle(h -> {
                ContainerDao containerDao = h.attach(ContainerDao.class);
                containerDao.insert(containerBlobId, entryCount);
            });

            // Insert entry rows
            List<EntryDao.EntryRow> entryRows = new ArrayList<>();
            for (ManifestProto.ManifestEntry entry : mc.manifest().getEntriesList()) {
                ContentHash childHash = new ContentHash(entry.getContentHash().toByteArray());
                BlobRef childRef = entry.getIsContainer()
                        ? BlobRef.container(childHash, entry.getLeafSize())
                        : BlobRef.leaf(childHash, entry.getLeafSize());

                long childBlobId = jdbi.withHandle(h -> {
                    BlobRefDao refDao = h.attach(BlobRefDao.class);
                    long blobRefId = refDao.findOrInsert(childRef, null);
                    BlobDao blobDao = h.attach(BlobDao.class);
                    return blobDao.findOrInsert(tenantId, blobRefId);
                });

                entryRows.add(new EntryDao.EntryRow(
                        childBlobId, containerBlobId,
                        (short) entry.getEntryType(),
                        entry.getPath(), null));
                totalEntries++;
            }

            if (!entryRows.isEmpty()) {
                jdbi.useHandle(h -> {
                    EntryDao entryDao = h.attach(EntryDao.class);
                    entryDao.batchInsert(h, entryRows);
                });
            }
        }

        log.infof("Rebuild complete for tenant %d: %d containers, %d entries",
                tenantId, totalContainers, totalEntries);
        return TaskOutcome.complete(Map.of(
                "containers", totalContainers,
                "entries", totalEntries
        ));
    }

    private record ManifestWithContext(BlobRef containerRef, ManifestProto.Manifest manifest) {}
}
