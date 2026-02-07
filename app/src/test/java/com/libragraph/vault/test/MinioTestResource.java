package com.libragraph.vault.test;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.MinIOContainer;

import java.util.Map;

/**
 * Starts an ephemeral MinIO container for the test profile.
 *
 * <p>When the dev profile is active (Docker Compose MinIO), this resource
 * is a no-op — the container is not started and no config is overridden.
 */
public class MinioTestResource implements QuarkusTestResourceLifecycleManager {

    static {
        // Ryuk has connectivity issues on WSL2; container cleanup handled by stop()
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    private MinIOContainer container;

    @Override
    public Map<String, String> start() {
        if ("dev".equals(System.getProperty("quarkus.test.profile"))) {
            return Map.of();
        }
        container = new MinIOContainer("minio/minio:RELEASE.2024-11-07T00-52-20Z");
        container.start();
        return Map.of(
                "vault.minio.endpoint", container.getS3URL(),
                "vault.minio.access-key", container.getUserName(),
                "vault.minio.secret-key", container.getPassword()
        );
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }
}
