package com.libragraph.vault.core.test;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads test configuration from {@code vault.test.*} properties.
 * Properties can be set via Gradle {@code -P} flags:
 * <pre>
 *   ./gradlew test -Pvault.test.profile=dev -Pvault.test.resetTenant=true
 * </pre>
 */
@ApplicationScoped
public class VaultTestConfig {

    @ConfigProperty(name = "vault.test.tenant-id")
    Optional<String> configuredTenantId;

    @ConfigProperty(name = "vault.test.reset-tenant", defaultValue = "false")
    boolean resetTenant;

    /** Returns configured tenant ID, or auto-generates a random UUID. */
    public String tenantId() {
        return configuredTenantId
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    public boolean resetTenant() {
        return resetTenant;
    }
}
