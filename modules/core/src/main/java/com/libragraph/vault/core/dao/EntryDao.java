package com.libragraph.vault.core.dao;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;

@RegisterConstructorMapper(EntryRecord.class)
public interface EntryDao {

    @SqlUpdate("INSERT INTO entry (blob_id, container_id, entry_type_id, internal_path, mtime, metadata) " +
            "VALUES (:blobId, :containerId, :entryTypeId, :internalPath, :mtime, CAST(:metadataJson AS jsonb)) " +
            "ON CONFLICT (container_id, internal_path) DO NOTHING")
    @GetGeneratedKeys("id")
    long insert(@Bind("blobId") long blobId,
                @Bind("containerId") long containerId,
                @Bind("entryTypeId") short entryTypeId,
                @Bind("internalPath") String internalPath,
                @Bind("mtime") Instant mtime,
                @Bind("metadataJson") String metadataJson);

    @SqlQuery("SELECT * FROM entry WHERE container_id = :containerId")
    List<EntryRecord> findByContainer(@Bind("containerId") long containerId);

    /**
     * Batch inserts entries for a container using a PreparedBatch.
     */
    default void batchInsert(Handle handle, List<EntryRow> rows) {
        var batch = handle.prepareBatch(
                "INSERT INTO entry (blob_id, container_id, entry_type_id, internal_path, mtime, metadata) " +
                "VALUES (:blobId, :containerId, :entryTypeId, :internalPath, :mtime, CAST(:metadataJson AS jsonb)) " +
                "ON CONFLICT (container_id, internal_path) DO NOTHING");
        for (EntryRow row : rows) {
            batch.bind("blobId", row.blobId())
                    .bind("containerId", row.containerId())
                    .bind("entryTypeId", row.entryTypeId())
                    .bind("internalPath", row.internalPath())
                    .bind("mtime", row.mtime())
                    .bind("metadataJson", row.metadataJson())
                    .add();
        }
        batch.execute();
    }

    record EntryRow(long blobId, long containerId, short entryTypeId,
                    String internalPath, Instant mtime, String metadataJson) {}
}
