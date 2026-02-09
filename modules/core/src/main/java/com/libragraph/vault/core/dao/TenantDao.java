package com.libragraph.vault.core.dao;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.util.List;
import java.util.Optional;

@RegisterConstructorMapper(TenantRecord.class)
public interface TenantDao {

    @SqlQuery("SELECT * FROM tenant WHERE id = :id")
    Optional<TenantRecord> findById(@Bind("id") int id);

    @SqlQuery("SELECT * FROM tenant")
    List<TenantRecord> findAll();

    @SqlQuery("SELECT * FROM tenant WHERE name = :name")
    Optional<TenantRecord> findByName(@Bind("name") String name);
}
