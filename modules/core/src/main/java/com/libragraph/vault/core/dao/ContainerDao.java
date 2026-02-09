package com.libragraph.vault.core.dao;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;

@RegisterConstructorMapper(ContainerRecord.class)
public interface ContainerDao {

    @SqlUpdate("INSERT INTO container (blob_id, entry_count) VALUES (:blobId, :entryCount) " +
            "ON CONFLICT (blob_id) DO NOTHING")
    void insert(@Bind("blobId") long blobId, @Bind("entryCount") int entryCount);

    @SqlQuery("SELECT * FROM container WHERE blob_id = :blobId")
    Optional<ContainerRecord> findByBlobId(@Bind("blobId") long blobId);
}
