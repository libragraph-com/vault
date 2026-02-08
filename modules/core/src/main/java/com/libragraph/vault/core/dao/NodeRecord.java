package com.libragraph.vault.core.dao;

import org.jdbi.v3.core.mapper.reflect.ColumnName;

import java.time.Instant;

public record NodeRecord(
        @ColumnName("id") int id,
        @ColumnName("hostname") String hostname,
        @ColumnName("last_seen") Instant lastSeen
) {}
