package com.libragraph.vault.core.dao;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.util.List;
import java.util.Optional;

public interface TaskResourceDao {

    @SqlQuery("SELECT id, name, max_concurrency FROM task_resource WHERE name = :name")
    Optional<TaskResourceRecord> findByName(@Bind("name") String name);

    @SqlQuery("SELECT id, name, max_concurrency FROM task_resource")
    List<TaskResourceRecord> findAll();
}
