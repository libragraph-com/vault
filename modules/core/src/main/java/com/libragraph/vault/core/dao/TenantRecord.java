package com.libragraph.vault.core.dao;

import org.jdbi.v3.core.mapper.reflect.ColumnName;

import java.util.UUID;

public record TenantRecord(
        @ColumnName("id") int id,
        @ColumnName("global_id") UUID globalId,
        @ColumnName("org_id") int orgId,
        @ColumnName("name") String name
) {}
