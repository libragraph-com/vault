# ObjectStorage API Design

**Status:** Proposed (Quarkus rebuild)
**Last Updated:** 2026-02-07
**Reference:** `vault-mvp/modules/core/.../ObjectStorage.java` (current implementation)

## Overview

ObjectStorage is the abstraction for blob persistence. This document proposes changes for the Quarkus rebuild based on lessons from vault-mvp.

**New requirements:**
- Explicit tenant isolation (tenantId parameter)
- Reactive streaming for list operations (not `List<>`)
- Uses existing `BinaryData` type from vault-mvp

## Core Interface

```java
public interface ObjectStorage {

    /**
     * Read a blob (always returns uncompressed data)
     */
    Uni<BinaryData> read(UUID tenantId, BlobRef ref);

    /**
     * Write a blob (driver decides compression)
     * @param mimeType optional hint for compression decision
     */
    Uni<Void> write(UUID tenantId, BlobRef ref, BinaryData data, String mimeType);

    /**
     * Check existence
     */
    Uni<Boolean> exists(UUID tenantId, BlobRef ref);

    /**
     * Delete blob
     */
    Uni<Void> delete(UUID tenantId, BlobRef ref);

    /**
     * List tenants (paginated)
     */
    Multi<UUID> listTenants();

    /**
     * List containers for tenant (paginated)
     * Only returns blobs where ref.isContainer() == true
     */
    Multi<BlobRef> listContainers(UUID tenantId);
}
```

## Key Design Decisions

### 1. TenantId as First Parameter

**Always explicit, never implicit:**

```java
// ✅ Correct
storage.read(tenantId, blobRef)

// ❌ Wrong - no ambient context
storage.read(blobRef)  // Where does tenantId come from?
```

Tenant must be in the REST path (`/api/tenants/{tenantId}/...`), passed explicitly through the call chain.

### 2. Reactive Return Types

**Not List<>, use Multi<>:**

```java
// ❌ Wrong - loads millions into memory
Uni<List<BlobRef>> listContainers(UUID tenantId);

// ✅ Correct - streams results
Multi<BlobRef> listContainers(UUID tenantId);
```

Callers can:
- `take(100)` for pagination
- `subscribe()` for streaming
- `collect().asList()` if they really want a list (their choice)

### 3. BinaryData Abstraction

**Uses existing vault-mvp `BinaryData` type:**

```java
public abstract class BinaryData implements SeekableByteChannel {
    public abstract ContentHash hash();
    public abstract long size();
    public InputStream inputStream(long pos);
    public byte[] readHeader(int maxBytes);
}
```

Benefits:
- Already exists in vault-mvp
- Seekable channel interface (not just streams)
- Hash and size always available
- Header reading for format detection
- Works with files, byte arrays, memory-mapped files

### 4. Compression Transparency

**Caller provides uncompressed data, receives uncompressed data:**

```java
// Write
BinaryData uncompressed = BinaryData.wrap(channel);
storage.write(tenantId, ref, uncompressed, "application/zip");
// Driver compresses if beneficial (e.g., text files)
// Driver skips compression if not (e.g., already-compressed ZIP)

// Read
BinaryData uncompressed = storage.read(tenantId, ref).await().indefinitely();
// Driver decompresses transparently
// Caller always gets original data
```

The `mimeType` parameter is a **hint**, not a command. Driver decides:
- `text/plain` → probably compress
- `image/jpeg` → don't compress (already compressed)
- `application/zip` → don't compress
- `application/octet-stream` → maybe compress (unknown type)

## Changes from vault-mvp

**vault-mvp ObjectStorage:**
```java
interface ObjectStorage {
    Mono<Void> write(Path path, SeekableByteChannel content);
    Mono<SeekableByteChannel> read(Path path);
    Mono<Boolean> exists(Path path);
    Mono<Void> delete(Path path);
}
```

**Issues:**
- No list operations (needed for RebuildSQL, container enumeration)
- Path includes everything (tenant, sharding) - coupling
- No tenant isolation at API level
- Returns `SeekableByteChannel` not `BinaryData` (loses hash/size)

**Quarkus rebuild (proposed):**
```java
interface ObjectStorage {
    Uni<BinaryData> read(UUID tenantId, BlobRef ref);
    Uni<Void> write(UUID tenantId, BlobRef ref, BinaryData data, String mimeType);
    Uni<Boolean> exists(UUID tenantId, BlobRef ref);
    Uni<Void> delete(UUID tenantId, BlobRef ref);
    Multi<UUID> listTenants();
    Multi<BlobRef> listContainers(UUID tenantId);
}
```

**Key changes:**
1. **Add list operations** - `Multi<>` for streaming (millions of items)
2. **Explicit tenantId** - always first parameter, never ambient
3. **BlobRef parameter** - not Path (driver resolves to storage path)
4. **Returns BinaryData** - not SeekableByteChannel (includes hash/size)
5. **Quarkus reactive** - `Uni<>` / `Multi<>` instead of `Mono<>` / `Flux<>`

## Implementation Strategies

### Filesystem (Dev)

- **Compression:** None (store as-is for debugging)
- **Layout:** `{root}/{tenantId}/{tier1}/{tier2}/{key}`
- **Sharding:** 2-tier (ab/cd/) to avoid too many files per directory

Same as vault-mvp, but driver now handles path resolution internally.

### MinIO (Prod)

- **Compression:** MinIO's internal compression (transparent)
- **Layout:** `{bucket-prefix}{tenantId}/{key}`
- **Sharding:** None (MinIO handles it)

New: bucket-per-tenant for isolation, cost tracking, and access control.

## Usage Examples

See implementation examples in:
- [Filesystem Implementation](filesystem-impl.md)
- [MinIO Implementation](minio-impl.md)
- [BlobService Layer](blobservice-layer.md)

## Pagination Pattern

```java
// List first 100 containers
Multi<BlobRef> containers = storage.listContainers(tenantId)
    .select().first(100);

// Stream all containers (memory-safe)
storage.listContainers(tenantId)
    .subscribe().with(
        ref -> processContainer(ref),
        failure -> handleError(failure),
        () -> done()
    );
```

## Error Handling

- **BlobNotFoundException** - read/delete on non-existent blob
- **TenantNotFoundException** - operations on non-existent tenant
- **StorageException** - I/O errors, network failures

## See Also

- [BlobRef Design](BlobRef-Design.md) - Reference structure
- [Compression Strategy](Compression.md) - When to compress
- [Tenant Isolation](../Platform.md) - Multi-tenancy model
