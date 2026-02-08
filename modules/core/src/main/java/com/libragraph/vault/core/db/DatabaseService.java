package com.libragraph.vault.core.db;

import com.libragraph.vault.core.dao.DatabaseDao;
import com.libragraph.vault.core.service.AbstractManagedService;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;

/**
 * Root infrastructure service that gates access to the database.
 * Starts eagerly at boot via {@code @Startup}, verifies PG connectivity,
 * and exposes {@link #jdbi()} only when RUNNING.
 */
@ApplicationScoped
@Startup
public class DatabaseService extends AbstractManagedService {

    @Inject
    Jdbi jdbi;

    private String pgVersion;

    @Override
    public String serviceId() {
        return "database";
    }

    @Override
    protected void doStart() {
        pgVersion = jdbi.withExtension(DatabaseDao.class, DatabaseDao::pgVersion);
        log.infof("Connected to: %s", pgVersion);
    }

    @Override
    protected void doStop() {
        log.info("DatabaseService stopping (Agroal manages pool shutdown)");
    }

    /** Returns the JDBI instance. Throws if service is not RUNNING. */
    public Jdbi jdbi() {
        if (!isRunning()) {
            throw new IllegalStateException(
                    "DatabaseService is not running (state=" + state() + ")");
        }
        return jdbi;
    }

    /** Executes SELECT 1 to verify connectivity. Calls {@link #fail} on error. */
    public boolean ping() {
        try {
            jdbi.withExtension(DatabaseDao.class, DatabaseDao::ping);
            return true;
        } catch (Exception e) {
            fail(e);
            return false;
        }
    }

    /** PostgreSQL version string from startup probe. */
    public String pgVersion() {
        return pgVersion;
    }

    @PostConstruct
    void init() {
        try {
            start();
        } catch (Exception e) {
            throw new RuntimeException("DatabaseService failed to start", e);
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            stop();
        } catch (Exception e) {
            log.warn("Error stopping DatabaseService", e);
        }
    }
}
