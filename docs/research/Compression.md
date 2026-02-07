# Compression Strategy

**Status:** Proposed
**Last Updated:** 2026-02-07

## Principle

**Compression is an ObjectStorage driver implementation detail.**

Callers provide uncompressed data and receive uncompressed data. The driver decides whether to compress based on:
1. MIME type hint
2. Driver configuration
3. Storage backend capabilities

## Driver Behaviors

### Filesystem (Dev Mode)

**Never compress.**

Rationale:
- Easy debugging (can `cat` files directly)
- Fast (no CPU overhead)
- Dev mode is local, disk is cheap

### MinIO (Production)

**MinIO handles compression internally.**

MinIO's compression is:
- Transparent to clients
- Based on object metadata
- Configured at bucket level

We just write objects, MinIO decides compression.

### S3 (Production)

**No automatic compression.**

Options:
1. **Client-side compression** - compress before upload
2. **S3 Intelligent-Tiering** - automatic compression for archived objects
3. **None** - rely on content already being compressed

For Vault, we can enable client-side compression for text-like MIME types.

## Compression Decision Logic

**Example (if implementing client-side):**

```java
boolean shouldCompress(String mimeType, long size) {
    // Already compressed formats
    if (mimeType.startsWith("image/") && !mimeType.equals("image/svg+xml")) {
        return false;  // JPEG, PNG, WebP already compressed
    }
    if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) {
        return false;  // Media already compressed
    }
    if (mimeType.equals("application/zip") ||
        mimeType.equals("application/gzip") ||
        mimeType.equals("application/x-7z-compressed")) {
        return false;  // Archive formats
    }

    // Highly compressible formats
    if (mimeType.startsWith("text/") ||
        mimeType.equals("application/json") ||
        mimeType.equals("application/xml") ||
        mimeType.equals("application/javascript")) {
        return size > 1024;  // Compress if > 1 KB
    }

    // Unknown - compress if large enough to matter
    return size > 10_000;  // 10 KB threshold
}
```

## Compression Algorithms

**Preference order:**

1. **Zstandard (zstd)** - best ratio/speed tradeoff, modern
2. **LZ4** - fastest, lower ratio (good for already-compressed data that needs framing)
3. **Gzip** - universal compatibility (if needed for browser/CDN)

For Vault: **zstd** is ideal.

## Storage Format (Filesystem Example)

Even though compression is transparent to callers, filesystem driver may store:

```
# Uncompressed (because JPEG)
a1b2c3.../abc123-1048576

# Compressed with zstd (because text)
d4e5f6.../def456-524288.zst
```

The `.zst` extension is internal to the driver. Callers never see it.

## BlobRef Doesn't Know

**BlobRef has no compression info:**

```java
BlobRef(hash, leafSize, isContainer)  // No extension field!
```

Why?
- BlobRef is the **logical reference**
- Compression is **physical storage detail**
- Different drivers may compress differently
- Same BlobRef works across all drivers

## Read/Write Contract

**Write:**
```java
// Caller provides uncompressed data
BinaryFile uncompressed = ...;
storage.write(tenantId, ref, uncompressed, "text/plain");
// Driver may compress during write
```

**Read:**
```java
// Caller receives uncompressed data
BinaryFile uncompressed = storage.read(tenantId, ref).await();
// Driver decompresses if needed
```

Caller never knows or cares if compression happened.

## Benefits

1. **Flexibility** - Can change compression strategy without breaking callers
2. **Simplicity** - BlobRef doesn't carry compression metadata
3. **Performance** - Driver can optimize per backend (MinIO internal, filesystem zstd, S3 none)
4. **Correctness** - Reconstruction gets same bytes as ingestion (compression is transparent)

## Dev vs Prod

| Aspect | Dev (Filesystem) | Prod (MinIO) |
|--------|------------------|--------------|
| Compression | None | MinIO internal (transparent) |
| Rationale | Debuggability | Storage cost |
| Speed | Faster | Optimized by MinIO |
| Format | Raw files | MinIO manages |

## See Also

- [ObjectStorage API](ObjectStorage-API.md) - How mimeType hint is used
- [BlobRef Design](BlobRef-Design.md) - Why no extension field
