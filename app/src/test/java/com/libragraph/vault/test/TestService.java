package com.libragraph.vault.test;

import com.libragraph.vault.core.service.AbstractManagedService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Lightweight test-only service for validating state event lifecycle.
 * No dependencies — safe in shared CDI container.
 */
@ApplicationScoped
public class TestService extends AbstractManagedService {

    @Override
    public String serviceId() {
        return "test-service";
    }

    @Override
    protected void doStart() {
    }

    @Override
    protected void doStop() {
    }
}
