package com.libragraph.vault.core.storage;

import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.RamBuffer;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * S3/MinIO-backed ObjectStorage for production use.
 *
 * <p>Uses bucket-per-tenant: {@code {bucketPrefix}{tenantId}}.
 * Keys are flat {@code BlobRef.toString()} — MinIO handles sharding internally.
 */
@ApplicationScoped
@IfBuildProperty(name = "vault.object-store.type", stringValue = "s3")
public class S3ObjectStorage implements ObjectStorage {

    @Inject
    MinioClient minioClient;

    @ConfigProperty(name = "vault.object-store.bucket-prefix", defaultValue = "vault-")
    String bucketPrefix;

    @ConfigProperty(name = "vault.object-store.write-once-check", defaultValue = "false")
    boolean writeOnceCheck;

    private String bucketName(String tenantId) {
        return bucketPrefix + tenantId;
    }

    private void ensureBucket(String bucket) {
        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (ErrorResponseException e) {
            // Concurrent creation — another thread already created the bucket
            if ("BucketAlreadyOwnedByYou".equals(e.errorResponse().code())) {
                return;
            }
            throw new StorageException("Failed to ensure bucket: " + bucket, e);
        } catch (Exception e) {
            throw new StorageException("Failed to ensure bucket: " + bucket, e);
        }
    }

    @Override
    public Uni<BinaryData> read(String tenantId, BlobRef ref) {
        return Uni.createFrom().item(() -> {
            String bucket = bucketName(tenantId);
            String key = ref.toString();
            try (InputStream is = minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(key).build())) {
                byte[] bytes = is.readAllBytes();
                return (BinaryData) new RamBuffer(bytes, ref.hash());
            } catch (ErrorResponseException e) {
                String code = e.errorResponse().code();
                if ("NoSuchKey".equals(code) || "NoSuchBucket".equals(code)) {
                    throw new BlobNotFoundException(tenantId, ref);
                }
                throw new StorageException("Failed to read blob: " + ref, e);
            } catch (Exception e) {
                throw new StorageException("Failed to read blob: " + ref, e);
            }
        });
    }

    @Override
    public Uni<Void> create(String tenantId, BlobRef ref, BinaryData data, String mimeType) {
        return Uni.createFrom().voidItem().invoke(() -> {
            String bucket = bucketName(tenantId);
            ensureBucket(bucket);
            String key = ref.toString();
            if (writeOnceCheck) {
                try {
                    minioClient.statObject(StatObjectArgs.builder()
                            .bucket(bucket).object(key).build());
                    throw new BlobAlreadyExistsException(tenantId, ref);
                } catch (BlobAlreadyExistsException e) {
                    throw e;
                } catch (ErrorResponseException e) {
                    String code = e.errorResponse().code();
                    if (!"NoSuchKey".equals(code) && !"NoSuchBucket".equals(code)) {
                        throw new StorageException("Failed to check existence: " + ref, e);
                    }
                    // Good — does not exist, proceed
                } catch (Exception e) {
                    throw new StorageException("Failed to check existence: " + ref, e);
                }
            }
            try (InputStream is = data.inputStream(0)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(key)
                        .stream(is, data.size(), -1)
                        .contentType(mimeType != null ? mimeType : "application/octet-stream")
                        .build());
            } catch (Exception e) {
                throw new StorageException("Failed to write blob: " + ref, e);
            }
        });
    }

    @Override
    public Uni<Boolean> exists(String tenantId, BlobRef ref) {
        return Uni.createFrom().item(() -> {
            String bucket = bucketName(tenantId);
            try {
                minioClient.statObject(StatObjectArgs.builder()
                        .bucket(bucket).object(ref.toString()).build());
                return true;
            } catch (ErrorResponseException e) {
                if ("NoSuchKey".equals(e.errorResponse().code())
                        || "NoSuchBucket".equals(e.errorResponse().code())) {
                    return false;
                }
                throw new StorageException("Failed to check existence: " + ref, e);
            } catch (Exception e) {
                throw new StorageException("Failed to check existence: " + ref, e);
            }
        });
    }

    @Override
    public Uni<Void> delete(String tenantId, BlobRef ref) {
        return Uni.createFrom().voidItem().invoke(() -> {
            String bucket = bucketName(tenantId);
            String key = ref.toString();
            // Check existence first — MinIO removeObject is silent on missing keys
            try {
                minioClient.statObject(StatObjectArgs.builder()
                        .bucket(bucket).object(key).build());
            } catch (ErrorResponseException e) {
                if ("NoSuchKey".equals(e.errorResponse().code())
                        || "NoSuchBucket".equals(e.errorResponse().code())) {
                    throw new BlobNotFoundException(tenantId, ref);
                }
                throw new StorageException("Failed to delete blob: " + ref, e);
            } catch (Exception e) {
                throw new StorageException("Failed to delete blob: " + ref, e);
            }
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket).object(key).build());
            } catch (Exception e) {
                throw new StorageException("Failed to delete blob: " + ref, e);
            }
        });
    }

    @Override
    public Uni<Void> deleteTenant(String tenantId) {
        return Uni.createFrom().voidItem().invoke(() -> {
            String bucket = bucketName(tenantId);
            try {
                // Remove all objects first (S3 requires empty bucket for deletion)
                for (Result<Item> result : minioClient.listObjects(
                        ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
                    minioClient.removeObject(RemoveObjectArgs.builder()
                            .bucket(bucket).object(result.get().objectName()).build());
                }
                minioClient.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
            } catch (ErrorResponseException e) {
                if ("NoSuchBucket".equals(e.errorResponse().code())) {
                    return; // already gone
                }
                throw new StorageException("Failed to delete tenant: " + tenantId, e);
            } catch (Exception e) {
                throw new StorageException("Failed to delete tenant: " + tenantId, e);
            }
        });
    }

    @Override
    public Multi<String> listTenants() {
        return Multi.createFrom().items(() -> {
            try {
                List<String> tenants = new ArrayList<>();
                for (Bucket bucket : minioClient.listBuckets()) {
                    String name = bucket.name();
                    if (name.startsWith(bucketPrefix)) {
                        tenants.add(name.substring(bucketPrefix.length()));
                    }
                }
                return tenants.stream();
            } catch (Exception e) {
                throw new StorageException("Failed to list tenants", e);
            }
        });
    }

    @Override
    public Multi<BlobRef> listContainers(String tenantId) {
        return Multi.createFrom().items(() -> {
            String bucket = bucketName(tenantId);
            try {
                List<BlobRef> containers = new ArrayList<>();
                for (Result<Item> result : minioClient.listObjects(
                        ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
                    String name = result.get().objectName();
                    if (name.endsWith("_")) {
                        containers.add(BlobRef.parse(name));
                    }
                }
                return containers.stream();
            } catch (ErrorResponseException e) {
                if ("NoSuchBucket".equals(e.errorResponse().code())) {
                    return java.util.stream.Stream.<BlobRef>empty();
                }
                throw new StorageException("Failed to list containers for tenant: " + tenantId, e);
            } catch (Exception e) {
                throw new StorageException("Failed to list containers for tenant: " + tenantId, e);
            }
        });
    }
}
