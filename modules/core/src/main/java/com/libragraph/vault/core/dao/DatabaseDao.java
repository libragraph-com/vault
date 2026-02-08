package com.libragraph.vault.core.dao;

import org.jdbi.v3.sqlobject.statement.SqlQuery;

public interface DatabaseDao {

    @SqlQuery("SELECT version()")
    String pgVersion();

    @SqlQuery("SELECT 1")
    int ping();
}
