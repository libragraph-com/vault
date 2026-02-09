package com.libragraph.vault.core.dao;

import org.jdbi.v3.core.mapper.reflect.ColumnName;

public record ContainerRecord(
        @ColumnName("blob_id") long blobId,
        @ColumnName("entry_count") int entryCount
) {}
