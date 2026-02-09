package com.libragraph.vault.core.rebuild;

import com.libragraph.vault.core.dao.BlobDao;
import com.libragraph.vault.core.dao.BlobRefDao;
import com.libragraph.vault.core.dao.ContainerDao;
import com.libragraph.vault.core.dao.EntryDao;
import com.libragraph.vault.core.dao.TenantDao;
import com.libragraph.vault.core.dao.TenantRecord;
import com.libragraph.vault.core.ingest.ManifestManager;
import com.libragraph.vault.core.storage.BlobService;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rebuilds all SQL index rows from ObjectStorage alone.
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
public class RebuildSqlTask implements VaultTask {

    private static final Logger log = Logger.getLogger(RebuildSqlTask.class);

    @Inject
    BlobService blobService;

    @Inject
    ManifestManager manifestManager;

    @Inject
    Jdbi jdbi;

    @Override
    public String taskType() {
        return "rebuild.sql";
    }

    @Override
    @SuppressWarnings("unchecked")
    public TaskOutcome onStart(Object input, TaskContext ctx) {
        Map<String, Object> inputMap = (Map<String, Object>) input;
        boolean truncateFirst = Boolean.TRUE.equals(inputMap.get("truncateFirst"));

        if (truncateFirst) {
            log.info("Truncating SQL tables before rebuild");
            jdbi.useHandle(h -> h.createUpdate(
                    "TRUNCATE entry, container, blob_content, blob, blob_ref CASCADE").execute());
        }

        // Get all tenants from DB (we need DB tenant IDs)
        List<TenantRecord> tenants = jdbi.withExtension(TenantDao.class, TenantDao::findAll);

        int totalContainers = 0;
        int totalEntries = 0;

        // Build tenant ID → storage key map
        Map<Integer, String> tenantStorageKeys = new HashMap<>();
        for (TenantRecord tenant : tenants) {
            String storageKey = tenant.globalId() != null
                    ? tenant.globalId().toString()
                    : String.valueOf(tenant.id());
            tenantStorageKeys.put(tenant.id(), storageKey);
        }

        // Cache manifests for pass 2
        Map<String, List<ManifestWithContext>> manifestCache = new HashMap<>();

        // Pass 1: Insert all blob_ref + blob rows
        log.info("Pass 1: Inserting blob_ref and blob rows");
        for (TenantRecord tenant : tenants) {
            String storageKey = tenantStorageKeys.get(tenant.id());
            List<BlobRef> containers = blobService.listContainers(storageKey)
                    .collect().asList().await().indefinitely();

            List<ManifestWithContext> tenantManifests = new ArrayList<>();
            for (BlobRef containerRef : containers) {
                ManifestProto.Manifest manifest = manifestManager.load(storageKey, containerRef);

                // Insert the container's own blob_ref + blob
                jdbi.useHandle(h -> {
                    BlobRefDao refDao = h.attach(BlobRefDao.class);
                    long blobRefId = refDao.findOrInsert(containerRef, "application/x-protobuf");
                    BlobDao blobDao = h.attach(BlobDao.class);
                    blobDao.findOrInsert(tenant.id(), blobRefId);
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
                        blobDao.findOrInsert(tenant.id(), blobRefId);
                    });
                }

                tenantManifests.add(new ManifestWithContext(containerRef, manifest));
                totalContainers++;
            }
            manifestCache.put(storageKey, tenantManifests);
        }

        // Pass 2: Insert container + entry relationships
        log.info("Pass 2: Inserting container and entry rows");
        for (TenantRecord tenant : tenants) {
            String storageKey = tenantStorageKeys.get(tenant.id());
            List<ManifestWithContext> manifests = manifestCache.getOrDefault(
                    storageKey, List.of());

            for (ManifestWithContext mc : manifests) {
                int entryCount = mc.manifest().getEntriesCount();

                // Look up container blob_id
                long containerBlobId = jdbi.withHandle(h -> {
                    BlobRefDao refDao = h.attach(BlobRefDao.class);
                    long blobRefId = refDao.findOrInsert(mc.containerRef(), null);
                    BlobDao blobDao = h.attach(BlobDao.class);
                    return blobDao.findOrInsert(tenant.id(), blobRefId);
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
                        return blobDao.findOrInsert(tenant.id(), blobRefId);
                    });

                    Instant mtime = entry.getMtime() > 0
                            ? Instant.ofEpochMilli(entry.getMtime())
                            : null;

                    entryRows.add(new EntryDao.EntryRow(
                            childBlobId, containerBlobId,
                            (short) entry.getEntryType(),
                            entry.getPath(), mtime, null));
                    totalEntries++;
                }

                if (!entryRows.isEmpty()) {
                    jdbi.useHandle(h -> {
                        EntryDao entryDao = h.attach(EntryDao.class);
                        entryDao.batchInsert(h, entryRows);
                    });
                }
            }
        }

        log.infof("Rebuild complete: %d containers, %d entries", totalContainers, totalEntries);
        return TaskOutcome.complete(Map.of(
                "containers", totalContainers,
                "entries", totalEntries
        ));
    }

    private record ManifestWithContext(BlobRef containerRef, ManifestProto.Manifest manifest) {}
}
