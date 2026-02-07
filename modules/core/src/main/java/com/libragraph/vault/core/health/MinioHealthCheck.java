package com.libragraph.vault.core.health;

import io.minio.MinioClient;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
@IfBuildProperty(name = "vault.object-store.type", stringValue = "s3")
public class MinioHealthCheck implements HealthCheck {

    @Inject
    MinioClient minioClient;

    @Override
    public HealthCheckResponse call() {
        try {
            minioClient.listBuckets();
            return HealthCheckResponse.named("minio")
                    .up()
                    .build();
        } catch (Exception e) {
            return HealthCheckResponse.named("minio")
                    .down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
