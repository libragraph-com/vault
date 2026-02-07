# Reconstruction

Protocol for rebuilding original containers from manifests and stored leaves.

## Flow

```
1. Load manifest via container BlobRef:
   BlobRef manifestRef = BlobRef.manifest(containerHash, containerSize)
   ManifestProto manifest = manifestManager.readManifest(manifestRef)

2. For each ManifestEntry:
   BlobRef leafRef = new BlobRef(entry.hash, entry.leafSize, entry.leafSize, entry.extension)
   ReadableByteChannel content = blobService.retrieve(leafRef)

3. Reassemble using format-specific reconstructor:
   handler.reconstruct(entries, outputStream)

4. Verify (if bit-identical mode):
   assert hash(output) == manifest.originalHash
```

**Key property:** No database query during reconstruction. Every ManifestEntry
contains `storage_extension` — all information needed to create a BlobRef.
Single lookup per leaf.

## Reconstruction Modes

| Mode | Guarantee | Use Case |
|------|-----------|----------|
| **Semantic** | Same files, container may differ | Default. "Give me a working ZIP" |
| **Bit-Identical** | SHA-256(output) == original | Legal/compliance, verification |

```java
public enum ReconstructionMode {
    SEMANTIC,       // Contents match, container structure may differ
    BIT_IDENTICAL   // Hash must match original
}
```

vault-mvp achieved 99.997% bit-identical accuracy for ZIP (remaining 24 bytes
from compression recompression — known limitation).

## Quarkus Implementation

```java
@ApplicationScoped
public class ReconstructionService {

    @Inject ManifestManager manifestManager;
    @Inject BlobService blobService;
    @Inject FormatRegistry formatRegistry;

    public ReconstructionResult reconstruct(BlobRef containerRef, Path outputPath,
                                            ReconstructionMode mode) {
        // 1. Read manifest
        ManifestProto manifest = manifestManager.readManifest(containerRef);

        // 2. Get format handler
        Handler handler = formatRegistry.getHandler(manifest.getHeader().getFormat());

        // 3. Collect entries with their content
        List<ContainerChild> children = manifest.getEntriesList().stream()
            .map(entry -> retrieveEntry(entry))
            .toList();

        // 4. Reconstruct
        try (OutputStream out = Files.newOutputStream(outputPath)) {
            handler.reconstruct(children, out);
        }

        // 5. Verify
        ContentHash outputHash = hashFile(outputPath);
        boolean verified = outputHash.equals(manifest.getHeader().getOriginalHash());

        return new ReconstructionResult(
            Files.size(outputPath), outputHash, verified
        );
    }

    private ContainerChild retrieveEntry(ManifestProto.ManifestEntry entry) {
        BlobRef ref = new BlobRef(
            ContentHash.fromBytes(entry.getTargetHash()),
            entry.getTargetSize(),
            entry.getTargetSize(),  // storedSize = leafSize for lookup
            entry.getStorageExtension()
        );
        ReadableByteChannel content = blobService.retrieve(ref);
        return new ContainerChild(entry.getPath(), content, entry.getMetadataMap());
    }
}
```

## Manifest Lookup

Manifests are stored as blobs with the underscore extension marker:

```
Storage key: {containerHash}-{containerSize}_
```

Lookup is by **container hash** (what the manifest describes/creates), not
by manifest hash. This is a key architectural decision — see [Architecture](Architecture.md).

```java
BlobRef manifestRef = BlobRef.manifest(containerHash, containerSize);
// manifestRef.toStoragePath() → "{containerHash}-{containerSize}_"
```

## REST Endpoint

```
POST /api/containers/{id}/reconstruct
  → Returns: 200 with streaming file download
  → Or: 202 Accepted with task ID for large containers
```

See [REST](REST.md) for API details.

> **DEPENDENCY:** Depends on [ObjectStore](ObjectStore.md) for blob retrieval,
> [FileFormats](FileFormats.md) for format-specific reconstruction,
> [Tasks](Tasks.md) for async reconstruction of large containers.
