package com.libragraph.vault.core.task;

import org.jdbi.v3.core.mapper.reflect.ColumnName;

import java.time.Instant;

public record TaskRecord(
        @ColumnName("id") int id,
        @ColumnName("tenant_id") int tenantId,
        @ColumnName("parent_id") Integer parentId,
        @ColumnName("type") String type,
        @ColumnName("status") TaskStatus status,
        @ColumnName("priority") int priority,
        @ColumnName("input") String input,
        @ColumnName("output") String output,
        @ColumnName("retryable") Boolean retryable,
        @ColumnName("retry_count") int retryCount,
        @ColumnName("executor") Integer executor,
        @ColumnName("created_at") Instant createdAt,
        @ColumnName("claimed_at") Instant claimedAt,
        @ColumnName("completed_at") Instant completedAt,
        @ColumnName("expires_at") Instant expiresAt
) {}
