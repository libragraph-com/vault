package com.libragraph.vault.core.health;

import com.libragraph.vault.core.db.DatabaseService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class DatabaseHealthCheck implements HealthCheck {

    @Inject
    DatabaseService databaseService;

    @Override
    public HealthCheckResponse call() {
        if (databaseService.isRunning()) {
            return HealthCheckResponse.named("database")
                    .up()
                    .withData("version", databaseService.pgVersion())
                    .withData("state", databaseService.state().name())
                    .build();
        } else {
            return HealthCheckResponse.named("database")
                    .down()
                    .withData("state", databaseService.state().name())
                    .build();
        }
    }
}
