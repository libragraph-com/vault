# BlobRef Design Specification

**Status:** Proposed (Quarkus rebuild)
**Last Updated:** 2026-02-07
**Reference:** `vault-mvp/shared/types/.../BlobRef.java` (current implementation)

## Overview

BlobRef is the content-addressable reference to stored blobs. It contains exactly the information needed to locate and retrieve a blob with **zero probing**.

This document proposes a **simplified design** for the Quarkus rebuild, learning from vault-mvp.

## Structure

```java
public record BlobRef(
    ContentHash hash,
    long leafSize,
    boolean isContainer
)
```

### Fields

- **`hash`** - Content hash (BLAKE3, 128-bit / 16 bytes)
- **`leafSize`** - Uncompressed size of the leaf data
- **`isContainer`** - true = manifest/recipe (needs dereferencing), false = data blob

### Key Properties

1. **No extension field** - Compression is an ObjectStorage driver implementation detail
2. **isContainer flag** - Determines `_` suffix for glob/ls operations
3. **leafSize, not storedSize** - Storage size is driver-specific (compression ratio varies)

## Serialization

### String Format

```
{hash}-{leafSize}[_]
```

Where:
- `{hash}` - Hex-encoded BLAKE3 hash (32 chars)
- `{leafSize}` - Decimal long
- `[_]` - Underscore suffix present if `isContainer == true`

### Examples

```
# Data blob (leaf)
a1b2c3d4e5f6...xyz-1048576

# Container (manifest/recipe)
f9e8d7c6b5a4...uvw-524288_
```

### API

```java
public record BlobRef(...) {

    /**
     * Serialize to storage key format
     * @return "{hash}-{leafSize}" or "{hash}-{leafSize}_"
     */
    public String toString() { ... }

    /**
     * Parse from storage key format
     * @throws IllegalArgumentException if format invalid
     */
    public static BlobRef parse(String key) { ... }

    // Factory methods for clarity
    public static BlobRef leaf(ContentHash hash, long size) { ... }
    public static BlobRef container(ContentHash hash, long size) { ... }
}
```

## Storage Key Generation

The string representation **IS** the storage key:

```java
BlobRef ref = BlobRef.leaf(hash, 1048576);
String storageKey = ref.toString();
// → "a1b2c3d4...xyz-1048576"

// ObjectStorage uses this key directly
storage.write(tenantId, storageKey, binaryFile);
```

ObjectStorage implementations may add:
- Sharding paths (filesystem: `ab/cd/{key}`)
- Tenant prefixes (MinIO: `vault-{tenantId}/{key}`)
- Compression extensions (implementation detail)

But **BlobRef itself only produces the base key**.

## Why No Extension?

**vault-mvp design:**
```java
BlobRef(hash, leafSize, storedSize, extension)  // What vault-mvp has
```

**Problems identified:**
1. Compression is storage driver concern, not caller concern
2. Extension varies by driver (filesystem `.zst`, MinIO invisible)
3. Caller shouldn't know/care about compression
4. `storedSize` unused for lookup (only validation)
5. Adds API surface for no benefit

**Proposed Quarkus design:**
```java
BlobRef(hash, leafSize, isContainer)  // ✅ Minimal, complete
```

**Benefits:**
1. Driver decides compression (dev=none, prod=transparent)
2. Caller just provides mimeType hint
3. Read/write APIs always work with uncompressed data
4. BlobRef is storage-agnostic
5. Simpler serialization format

## Container Detection

The `_` suffix enables fast container enumeration:

```bash
# List all containers for a tenant (filesystem)
ls tenant-123/**/*_

# Glob pattern for containers
**/*_
```

This avoids reading every file to check if it's a manifest. The suffix is a **filesystem optimization** that also works with object store prefix scans.

## Validation Rules

- **hash**: Must be valid hex string (32 chars for BLAKE3-128)
- **leafSize**: Must be > 0
- **isContainer**: boolean (no validation needed)

Parse should fail fast on invalid input.

## Changes from vault-mvp

**vault-mvp:**
```java
BlobRef(hash, leafSize, storedSize, extension)
ref.toStoragePath() → "{hash}-{leafSize}.zst"
ref.isManifest() → extension.equals("_")
```

**Quarkus rebuild (proposed):**
```java
BlobRef(hash, leafSize, isContainer)
ref.toString() → "{hash}-{leafSize}_"  // if container
ref.parse(String) → BlobRef  // new deserialization method
```

**Key changes:**
1. **Remove `storedSize`** - unused for lookup, only needed for validation
2. **Remove `extension`** - compression is driver implementation detail
3. **Add `isContainer`** - clearer than checking extension
4. **Add `parse()`** - explicit deserialization (vault-mvp didn't have this)
5. **Rename `toStoragePath()` → `toString()`** - it's serialization, not pathing

## See Also

- [ObjectStorage API](ObjectStorage-API.md) - How BlobRef is used
- [Compression Strategy](Compression.md) - Why drivers handle compression
- [Container Format](../FileFormats.md) - Manifest structure
