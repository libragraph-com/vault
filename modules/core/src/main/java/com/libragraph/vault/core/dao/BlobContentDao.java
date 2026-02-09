package com.libragraph.vault.core.dao;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface BlobContentDao {

    @SqlUpdate("INSERT INTO blob_content (blob_id, metadata) VALUES (:blobId, CAST(:metadata AS jsonb)) " +
               "ON CONFLICT (blob_id) DO UPDATE SET metadata = COALESCE(blob_content.metadata, '{}')::jsonb || CAST(:metadata AS jsonb)")
    void upsertMetadata(@Bind("blobId") long blobId, @Bind("metadata") String metadata);
}
