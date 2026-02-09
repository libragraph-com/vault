package com.libragraph.vault.core.dao;

import org.jdbi.v3.core.mapper.reflect.ColumnName;

public record EntryRecord(
        @ColumnName("id") long id,
        @ColumnName("blob_id") long blobId,
        @ColumnName("container_id") long containerId,
        @ColumnName("entry_type_id") short entryTypeId,
        @ColumnName("internal_path") String internalPath,
        @ColumnName("metadata") String metadata
) {}
