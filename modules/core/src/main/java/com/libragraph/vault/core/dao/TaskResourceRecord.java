package com.libragraph.vault.core.dao;

import org.jdbi.v3.core.mapper.reflect.ColumnName;

public record TaskResourceRecord(
        @ColumnName("id") int id,
        @ColumnName("name") String name,
        @ColumnName("max_concurrency") Integer maxConcurrency
) {}
