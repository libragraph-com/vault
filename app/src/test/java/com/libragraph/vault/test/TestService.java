package com.libragraph.vault.test;

import com.libragraph.vault.core.db.DatabaseService;
import com.libragraph.vault.core.service.AbstractManagedService;
import com.libragraph.vault.core.service.DependsOn;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Test-only service that {@code @DependsOn(DatabaseService)} to validate
 * dependency ordering and failure cascade semantics.
 */
@ApplicationScoped
@DependsOn(DatabaseService.class)
public class TestService extends AbstractManagedService {

    @Override
    public String serviceId() {
        return "test-service";
    }

    @Override
    protected void doStart() {
        log.info("TestService started");
    }

    @Override
    protected void doStop() {
        log.info("TestService stopped");
    }

    /** Resets state to STOPPED for test isolation. Package-private. */
    void reset() {
        forceState(State.STOPPED);
    }
}
