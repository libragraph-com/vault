package com.libragraph.vault.core.dao;

import org.jdbi.v3.core.mapper.reflect.ColumnName;

import java.time.Instant;

public record BlobRefRecord(
        @ColumnName("id") long id,
        @ColumnName("content_hash") byte[] contentHash,
        @ColumnName("leaf_size") long leafSize,
        @ColumnName("container") boolean container,
        @ColumnName("mime_type") String mimeType,
        @ColumnName("handler") Short handler,
        @ColumnName("created") Instant created
) {}
