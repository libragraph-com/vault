package com.libragraph.vault.core.ingest;

import com.libragraph.vault.core.dao.BlobDao;
import com.libragraph.vault.core.dao.BlobRefDao;
import com.libragraph.vault.core.storage.BlobService;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.jboss.logging.Logger;

/**
 * Stores blobs to ObjectStorage and creates DB rows atomically,
 * respecting dedup at both blob_ref (global) and blob (per-tenant) levels.
 */
@ApplicationScoped
public class BlobInserter {

    private static final Logger log = Logger.getLogger(BlobInserter.class);

    @Inject
    DedupChecker dedupChecker;

    @Inject
    BlobService blobService;

    @Inject
    Jdbi jdbi;

    public record InsertResult(long blobRefId, long blobId, boolean deduplicated) {}

    /**
     * Inserts a leaf blob: dedup checks, stores to ObjectStorage if new, creates DB rows.
     */
    public InsertResult insertLeaf(String tenantId, int dbTenantId,
                                    BinaryData buffer, BlobRef ref,
                                    String mimeType) {
        DedupChecker.DedupResult dedup = dedupChecker.check(dbTenantId, ref);

        if (dedup.exists()) {
            log.debugf("Dedup hit (full): ref=%s tenant=%d", ref, dbTenantId);
            return new InsertResult(dedup.blobRefId(), dedup.blobId(), true);
        }

        if (dedup.blobRefId() == 0) {
            // New blob_ref — store to ObjectStorage and insert both rows
            blobService.create(tenantId, ref, buffer, mimeType).await().indefinitely();
            long[] ids = jdbi.withHandle(h -> {
                BlobRefDao refDao = h.attach(BlobRefDao.class);
                long blobRefId = refDao.findOrInsert(ref, mimeType);
                BlobDao blobDao = h.attach(BlobDao.class);
                long blobId = blobDao.findOrInsert(dbTenantId, blobRefId);
                return new long[]{blobRefId, blobId};
            });
            log.debugf("New blob stored: ref=%s blobRefId=%d blobId=%d", ref, ids[0], ids[1]);
            return new InsertResult(ids[0], ids[1], false);
        }

        // blob_ref exists but tenant doesn't own it — just create blob row
        long blobId = jdbi.withHandle(h -> {
            BlobDao blobDao = h.attach(BlobDao.class);
            return blobDao.findOrInsert(dbTenantId, dedup.blobRefId());
        });
        log.debugf("Dedup hit (ref-level): ref=%s blobRefId=%d new blobId=%d",
                ref, dedup.blobRefId(), blobId);
        return new InsertResult(dedup.blobRefId(), blobId, true);
    }

    /**
     * Inserts a container (manifest): stores manifest data at containerRef key,
     * creates blob_ref (container=true) and blob rows.
     */
    public InsertResult insertContainer(String tenantId, int dbTenantId,
                                         BinaryData manifestData, BlobRef containerRef) {
        DedupChecker.DedupResult dedup = dedupChecker.check(dbTenantId, containerRef);

        if (dedup.exists()) {
            return new InsertResult(dedup.blobRefId(), dedup.blobId(), true);
        }

        if (dedup.blobRefId() == 0) {
            // New — store manifest and insert both rows
            blobService.create(tenantId, containerRef, manifestData, "application/x-protobuf")
                    .await().indefinitely();
            long[] ids = jdbi.withHandle(h -> {
                BlobRefDao refDao = h.attach(BlobRefDao.class);
                long blobRefId = refDao.findOrInsert(containerRef, "application/x-protobuf");
                BlobDao blobDao = h.attach(BlobDao.class);
                long blobId = blobDao.findOrInsert(dbTenantId, blobRefId);
                return new long[]{blobRefId, blobId};
            });
            return new InsertResult(ids[0], ids[1], false);
        }

        // blob_ref exists, add tenant ownership
        long blobId = jdbi.withHandle(h -> {
            BlobDao blobDao = h.attach(BlobDao.class);
            return blobDao.findOrInsert(dbTenantId, dedup.blobRefId());
        });
        return new InsertResult(dedup.blobRefId(), blobId, true);
    }
}
