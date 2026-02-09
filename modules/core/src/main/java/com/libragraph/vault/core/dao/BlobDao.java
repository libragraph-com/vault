package com.libragraph.vault.core.dao;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;

@RegisterConstructorMapper(BlobRecord.class)
public interface BlobDao {

    @SqlQuery("SELECT * FROM blob WHERE tenant_id = :tenantId AND blob_ref_id = :blobRefId")
    Optional<BlobRecord> findByTenantAndRef(@Bind("tenantId") int tenantId,
                                             @Bind("blobRefId") long blobRefId);

    @SqlUpdate("INSERT INTO blob (tenant_id, blob_ref_id) VALUES (:tenantId, :blobRefId)")
    @GetGeneratedKeys("id")
    long insert(@Bind("tenantId") int tenantId, @Bind("blobRefId") long blobRefId);

    /**
     * Atomically finds or inserts a tenant-scoped blob. Returns the blob ID.
     */
    @SqlQuery("INSERT INTO blob (tenant_id, blob_ref_id) VALUES (:tenantId, :blobRefId) " +
            "ON CONFLICT (tenant_id, blob_ref_id) DO UPDATE SET tenant_id = EXCLUDED.tenant_id " +
            "RETURNING id")
    long findOrInsert(@Bind("tenantId") int tenantId, @Bind("blobRefId") long blobRefId);

    @SqlQuery("SELECT * FROM blob WHERE id = :id")
    Optional<BlobRecord> findById(@Bind("id") long id);
}
