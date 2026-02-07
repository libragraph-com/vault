package com.libragraph.vault.core.health;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

@Readiness
@ApplicationScoped
public class DatabaseHealthCheck implements HealthCheck {

    @Inject
    AgroalDataSource dataSource;

    @Override
    public HealthCheckResponse call() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version()")) {
            rs.next();
            String version = rs.getString(1);
            return HealthCheckResponse.named("database")
                    .up()
                    .withData("version", version)
                    .build();
        } catch (Exception e) {
            return HealthCheckResponse.named("database")
                    .down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
