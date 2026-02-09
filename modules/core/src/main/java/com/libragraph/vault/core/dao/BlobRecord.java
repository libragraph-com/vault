package com.libragraph.vault.core.dao;

import org.jdbi.v3.core.mapper.reflect.ColumnName;

public record BlobRecord(
        @ColumnName("id") long id,
        @ColumnName("tenant_id") int tenantId,
        @ColumnName("blob_ref_id") long blobRefId
) {}
