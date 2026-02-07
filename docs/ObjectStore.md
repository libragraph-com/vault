# Object Store

**Status:** Implemented
**Reference:** `modules/core/src/main/java/com/libragraph/vault/core/storage/ObjectStorage.java`

Blob storage abstraction for filesystem, MinIO, and S3.

## Interface

ObjectStorage provides reactive, tenant-isolated blob persistence with transparent compression.

```java
public interface ObjectStorage {
    Uni<BinaryData> read(UUID tenantId, BlobRef ref);
    Uni<Void> write(UUID tenantId, BlobRef ref, BinaryData data, String mimeType);
    Uni<Boolean> exists(UUID tenantId, BlobRef ref);
    Uni<Void> delete(UUID tenantId, BlobRef ref);
    Multi<UUID> listTenants();
    Multi<BlobRef> listContainers(UUID tenantId);
}
```

**Key principles:**
1. **Tenant-first** - `tenantId` is always explicit, never ambient
2. **Reactive** - `Multi<>` for streaming (not `List<>` for millions of items)
3. **Transparent compression** - Driver decides, caller provides/receives uncompressed data
4. **BinaryData abstraction** - Existing vault-mvp type (SeekableByteChannel + hash + size)

> **See [research/ObjectStorage-API.md](research/ObjectStorage-API.md) for detailed API design.**

## BlobRef

The content-addressable reference to a stored blob:

```java
public record BlobRef(
    ContentHash hash,
    long leafSize,
    boolean isContainer
) {
    String toString();              // Serialize: "{hash}-{leafSize}[_]"
    static BlobRef parse(String);   // Deserialize (throws if invalid)
}
```

**Key properties:**
- **No extension field** - Compression is ObjectStorage driver detail
- **`isContainer` flag** - true = manifest/recipe (suffix `_`), false = data blob
- **String serialization** - `toString()` produces storage key, `parse()` reverses

Examples:
```
a1b2c3d4...xyz-1048576      # Data blob
f9e8d7c6...uvw-524288_      # Container (manifest)
```

The `_` suffix enables fast container enumeration (`ls **/*_` or glob patterns).

> **See [research/BlobRef-Design.md](research/BlobRef-Design.md) for complete specification.**

## BlobService Layer

Thin facade over ObjectStorage:

```java
@ApplicationScoped
public class BlobService {
    @Inject ObjectStorage storage;

    public Uni<BinaryData> retrieve(UUID tenantId, BlobRef ref) {
        return storage.read(tenantId, ref);
    }

    public Uni<Void> store(UUID tenantId, BlobRef ref, BinaryData data, String mimeType) {
        return storage.write(tenantId, ref, data, mimeType);
    }
}
```

BlobService may add caching, metrics, or validation in the future. For now, it's primarily a convenience layer.

## BinaryData Abstraction

Input/output type for blob data (existing vault-mvp type):

```java
public abstract class BinaryData implements SeekableByteChannel {
    public abstract ContentHash hash();
    public abstract long size();
    public InputStream inputStream(long pos);
    public byte[] readHeader(int maxBytes);
}
```

**Properties:**
- Hash and size always available
- Seekable channel interface for efficient I/O
- Header reading for format detection
- Works with files, byte arrays, memory-mapped files

Callers always work with uncompressed data. ObjectStorage driver handles compression transparently.

See `shared/utils/src/main/java/com/libragraph/vault/util/buffer/BinaryData.java`

## Implementations

### Filesystem (Dev Mode)

- **Compression:** None (store as-is for debugging)
- **Sharding:** 2-tier (`{tenantId}/ab/cd/{key}`) to avoid too many files per directory
- **Layout:** `{root}/{tenantId}/{tier1}/{tier2}/{key}`

### MinIO (Production)

- **Compression:** MinIO's internal compression (transparent to client)
- **Buckets:** One per tenant (`{bucket-prefix}{tenantId}`)
- **Layout:** Flat keys (MinIO handles sharding internally)

### Configuration

```properties
# Filesystem (test/dev)
vault.object-store.type=filesystem
vault.object-store.filesystem.root=/var/vault/blobs

# MinIO / S3 (dev/prod)
vault.object-store.type=s3
vault.minio.endpoint=http://localhost:9000
vault.minio.access-key=minioadmin
vault.minio.secret-key=minioadmin
vault.object-store.bucket-prefix=vault-
```

> **See [research/Compression.md](research/Compression.md) for compression strategy details.**

## Tenant Isolation

**TenantId is always explicit in API calls** (never ambient/implicit).

Storage layout:
- **Filesystem:** `{root}/{tenantId}/{tier1}/{tier2}/{key}`
- **MinIO/S3:** Bucket per tenant: `{bucket-prefix}{tenantId}/{key}`

Why buckets per tenant?
- S3 bucket policies for access control
- Cost tracking per tenant
- Easier tenant deletion (drop bucket)

See [Platform](Platform.md) for multi-tenancy model.

## Dev Services

Quarkus can auto-provision a MinIO container:

```properties
quarkus.s3.devservices.enabled=true
```

Or use filesystem backend for unit tests (no container needed).
