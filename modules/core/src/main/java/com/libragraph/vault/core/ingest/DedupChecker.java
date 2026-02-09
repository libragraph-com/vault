package com.libragraph.vault.core.ingest;

import com.libragraph.vault.core.dao.BlobDao;
import com.libragraph.vault.core.dao.BlobRecord;
import com.libragraph.vault.core.dao.BlobRefDao;
import com.libragraph.vault.core.dao.BlobRefRecord;
import com.libragraph.vault.util.BlobRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;

import java.util.Optional;

/**
 * Checks whether a blob already exists for a given tenant, avoiding redundant storage writes.
 */
@ApplicationScoped
public class DedupChecker {

    @Inject
    Jdbi jdbi;

    public record DedupResult(boolean exists, long blobRefId, Long blobId) {}

    /**
     * Checks if a BlobRef already exists globally (blob_ref) and for this tenant (blob).
     *
     * @return DedupResult where exists=true means the tenant already owns this blob
     */
    public DedupResult check(int tenantId, BlobRef ref) {
        return jdbi.withHandle(h -> {
            BlobRefDao blobRefDao = h.attach(BlobRefDao.class);
            Optional<BlobRefRecord> refRecord = blobRefDao.findByRef(
                    ref.hash().bytes(), ref.leafSize(), ref.isContainer());
            if (refRecord.isEmpty()) {
                return new DedupResult(false, 0, null);
            }

            long blobRefId = refRecord.get().id();
            BlobDao blobDao = h.attach(BlobDao.class);
            Optional<BlobRecord> blobRecord = blobDao.findByTenantAndRef(tenantId, blobRefId);
            if (blobRecord.isPresent()) {
                return new DedupResult(true, blobRefId, blobRecord.get().id());
            }

            return new DedupResult(false, blobRefId, null);
        });
    }
}
