package com.libragraph.vault.core.dao;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;

public interface NodeDao {

    @SqlUpdate("INSERT INTO node (hostname) VALUES (:hostname) " +
            "ON CONFLICT (hostname) DO UPDATE SET last_seen = now()")
    @GetGeneratedKeys("id")
    int upsert(@Bind("hostname") String hostname);

    @SqlQuery("SELECT id, hostname, last_seen FROM node WHERE hostname = :hostname")
    Optional<NodeRecord> findByHostname(@Bind("hostname") String hostname);

    @SqlUpdate("UPDATE node SET last_seen = now() WHERE id = :id")
    void heartbeat(@Bind("id") int id);
}
