package com.libragraph.vault.core.dao;

import com.libragraph.vault.util.BlobRef;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;

@RegisterConstructorMapper(BlobRefRecord.class)
public interface BlobRefDao {

    @SqlQuery("SELECT * FROM blob_ref WHERE content_hash = :hash AND leaf_size = :leafSize AND container = :container")
    Optional<BlobRefRecord> findByRef(@Bind("hash") byte[] hash,
                                       @Bind("leafSize") long leafSize,
                                       @Bind("container") boolean container);

    @SqlUpdate("INSERT INTO blob_ref (content_hash, leaf_size, container, mime_type, handler) " +
            "VALUES (:hash, :leafSize, :container, :mimeType, :handlerId)")
    @GetGeneratedKeys("id")
    long insert(@Bind("hash") byte[] hash,
                @Bind("leafSize") long leafSize,
                @Bind("container") boolean container,
                @Bind("mimeType") String mimeType,
                @Bind("handlerId") Short handlerId);

    /**
     * Atomically finds or inserts a blob_ref. Returns the ID.
     * On conflict, updates mime_type only if the new value is non-null (fills in missing metadata).
     */
    @SqlQuery("INSERT INTO blob_ref (content_hash, leaf_size, container, mime_type, handler) " +
            "VALUES (:hash, :leafSize, :container, :mimeType, :handlerId) " +
            "ON CONFLICT (content_hash, leaf_size, container) " +
            "DO UPDATE SET mime_type = COALESCE(EXCLUDED.mime_type, blob_ref.mime_type) " +
            "RETURNING id")
    long upsert(@Bind("hash") byte[] hash,
                @Bind("leafSize") long leafSize,
                @Bind("container") boolean container,
                @Bind("mimeType") String mimeType,
                @Bind("handlerId") Short handlerId);

    default long findOrInsert(BlobRef ref, String mimeType) {
        return upsert(ref.hash().bytes(), ref.leafSize(), ref.isContainer(), mimeType, null);
    }

    @SqlQuery("SELECT * FROM blob_ref WHERE id = :id")
    Optional<BlobRefRecord> findById(@Bind("id") long id);
}
