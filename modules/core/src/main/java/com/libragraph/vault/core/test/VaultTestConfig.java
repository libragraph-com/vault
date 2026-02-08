package com.libragraph.vault.core.test;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jdbi.v3.core.Jdbi;

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

    @Inject
    Jdbi jdbi;

    private volatile Integer cachedTenantId;

    /** Returns configured tenant ID string, or auto-generates a random UUID. */
    public String tenantId() {
        return configuredTenantId
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    /**
     * Returns an integer tenant ID by inserting a test org and tenant into the database.
     * Result is cached for the lifetime of this bean.
     */
    public int ensureTestTenant() {
        if (cachedTenantId != null) return cachedTenantId;

        synchronized (this) {
            if (cachedTenantId != null) return cachedTenantId;

            cachedTenantId = jdbi.withHandle(h -> {
                int orgId = h.createUpdate(
                                "INSERT INTO organization (name) VALUES ('test-org') " +
                                        "ON CONFLICT DO NOTHING")
                        .execute();

                // Get or create the org
                int org = h.createQuery("SELECT id FROM organization WHERE name = 'test-org'")
                        .mapTo(Integer.class).one();

                // Get or create the tenant
                h.createUpdate("INSERT INTO tenant (org_id, name) VALUES (:orgId, 'test-tenant') " +
                                "ON CONFLICT DO NOTHING")
                        .bind("orgId", org)
                        .execute();

                return h.createQuery("SELECT id FROM tenant WHERE name = 'test-tenant'")
                        .mapTo(Integer.class).one();
            });

            return cachedTenantId;
        }
    }

    public boolean resetTenant() {
        return resetTenant;
    }
}
