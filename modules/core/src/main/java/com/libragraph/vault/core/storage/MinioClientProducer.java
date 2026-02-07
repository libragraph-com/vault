package com.libragraph.vault.core.storage;

import io.minio.MinioClient;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@IfBuildProperty(name = "vault.object-store.type", stringValue = "s3")
public class MinioClientProducer {

    @ConfigProperty(name = "vault.minio.endpoint")
    String endpoint;

    @ConfigProperty(name = "vault.minio.access-key")
    String accessKey;

    @ConfigProperty(name = "vault.minio.secret-key")
    String secretKey;

    @Produces
    @Singleton
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
