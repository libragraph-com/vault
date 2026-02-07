# Ingestion

Event-driven protocol for ingesting files into Vault.

## Core Principles

1. **Stream first, decide later** — Hash before parse. Buffer content while
   computing hash. Decision about container vs leaf happens after EOF.
2. **Dedup gate** — After hash, check database (not storage) for existing leaf.
   If hit, skip entire subtree.
3. **No recursion** — Event-driven dispatch with fan-in synchronization.
   Bounded stack depth regardless of nesting.
4. **Detection after content** — Format detection only runs if dedup check
   is a miss. ([ADR-011](../pm/docs/decisions/adr-011-detection-after-content.md))

## Pipeline Flow

```
IngestFileEvent
  │
  ├─► ProcessContainerHandler
  │     ├─ Detect format via FormatRegistry
  │     ├─ Create FanInContext (child counter)
  │     ├─ Extract children via Handler.extractChildren()
  │     └─ Fire ChildDiscoveredEvent per child
  │
  ├─► ProcessChildHandler (per child)
  │     ├─ Hash + dedup check (leafDao.exists())
  │     ├─ If nested container → fire IngestFileEvent (re-enter pipeline)
  │     ├─ If new leaf → compress, store blob, insert DB record
  │     ├─ Fire ObjectCreatedEvent or DedupHitEvent
  │     └─ Decrement fan-in → if last child, fire AllChildrenCompleteEvent
  │
  └─► BuildManifestHandler (when all children complete)
        ├─ Build Protobuf manifest from ChildResults
        ├─ Store manifest blob ({containerHash}-{containerSize}_)
        ├─ Insert container + entry records in DB
        └─ Cascade: decrement parent fan-in (nested containers)
```

## Quarkus Implementation

### Handlers as CDI Beans

```java
@ApplicationScoped
public class ProcessContainerHandler {

    @Inject FormatRegistry formatRegistry;
    @Inject Event<ChildDiscoveredEvent> childEvent;

    void onIngest(@Observes IngestFileEvent event) {
        BlobRef containerRef = event.containerRef();
        Handler handler = formatRegistry.findHandler(containerRef);

        FanInContext fanIn = new FanInContext(
            UuidCreator.getTimeOrderedEpoch(),
            handler.extractChildren().count(),
            event.fanIn()  // parent context, if nested
        );

        handler.extractChildren().forEach(child ->
            childEvent.fire(new ChildDiscoveredEvent(child, fanIn, containerRef))
        );
    }
}
```

### Dedup Check

```java
@ApplicationScoped
public class ProcessChildHandler {

    @Inject LeafDao leafDao;
    @Inject BlobService blobService;
    @Inject Event<IngestFileEvent> ingestEvent;
    @Inject Event<ObjectCreatedEvent> storedEvent;

    void onChild(@Observes ChildDiscoveredEvent event) {
        ContentHash hash = computeHash(event.child().buffer());
        long size = event.child().buffer().size();

        // Dedup via DB query (NOT storage probing)
        if (leafDao.exists(hash.bytes())) {
            handleDedupHit(event, hash);
            return;
        }

        if (isContainer(event.child())) {
            // Re-enter pipeline for nested container
            BlobRef childRef = BlobRef.raw(hash, size, size);
            ingestEvent.fire(new IngestFileEvent(childRef, event.child().path(), event.fanIn()));
        } else {
            // Store leaf
            BlobRef ref = compressAndStore(event.child(), hash, size);
            leafDao.insert(toRecord(ref));
            storedEvent.fire(new ObjectCreatedEvent(ref));
            event.fanIn().addResult(new ChildResult(ref, event.child().path()));
            maybeComplete(event.fanIn());
        }
    }

    private void maybeComplete(FanInContext fanIn) {
        if (fanIn.decrementAndCheck()) {
            // Last child — trigger manifest building
            completeEvent.fire(new AllChildrenCompleteEvent(fanIn));
        }
    }
}
```

### Manifest Building

```java
@ApplicationScoped
public class BuildManifestHandler {

    @Inject ManifestManager manifestManager;
    @Inject BlobService blobService;

    void onComplete(@Observes AllChildrenCompleteEvent event) {
        FanInContext fanIn = event.fanIn();
        BlobRef containerRef = fanIn.containerRef();

        // Build and store manifest
        manifestManager.createManifest(containerRef, fanIn.results());

        // Insert container + entry records in DB
        insertContainerRecords(containerRef, fanIn.results());

        // Cascade up if this container has a parent
        if (fanIn.parent() != null) {
            fanIn.parent().addResult(new ChildResult(containerRef, fanIn.containerPath()));
            maybeComplete(fanIn.parent());
        }
    }
}
```

## Manifest Format

Protobuf binary format. See vault-mvp `manifest.proto`.

- **ManifestHeader**: magic, version, format, original_hash, original_size, entry_count
- **ManifestEntry**: path, entry_type, target_hash, target_size, storage_extension
- **Provenance**: user, IP, timestamp, custom metadata

Key design: `storage_extension` field (field 10) in each ManifestEntry contains
all info needed to create a BlobRef during reconstruction — no database query needed.

Manifests stored at: `{containerHash}-{containerSize}_` (underscore extension)

## Container Fate (Reconstruction Tiers)

| Tier | Fate | Storage | Formats | Example |
|------|------|---------|---------|---------|
| 1 | Reconstructable | Manifest only | ZIP, TAR, GZIP | Bit-identical rebuild |
| 2 | Stored | Manifest + original blob | 7Z, RAR | Can't reconstruct; keep original |
| 3 | Contents Only | Manifest + leaves (lossy) | PST, MBOX, VMDK | Extract contents, discard container |

The format plugin declares its capabilities:
```java
ContainerCapabilities.reconstructable()   // Tier 1
ContainerCapabilities.storeBlob()         // Tier 2
ContainerCapabilities.contentsOnly("mbox", "eml")  // Tier 3
```

See [ADR-009](../pm/docs/decisions/adr-009-container-reconstruction-tiers.md).

## Adaptive Buffering

Content is buffered during streaming for hash computation and re-read:

| Size | Strategy |
|------|----------|
| ≤ 1 MB | RAM buffer |
| ≤ 100 MB | Memory-mapped file |
| > 100 MB | Disk file with streaming |

See [ADR-010](../pm/docs/decisions/adr-010-adaptive-buffering.md).

## Configuration

```properties
vault.ingest.buffer.small-threshold=1MB
vault.ingest.buffer.medium-threshold=100MB
vault.ingest.buffer.temp-directory=/tmp/vault-ingest
vault.ingest.parallelism.max-concurrent-containers=4
vault.ingest.parallelism.max-concurrent-leaves=16
vault.ingest.errors.max-retries=3
vault.ingest.errors.on-corruption=skip
vault.ingest.errors.on-encrypted=queue
```

> **DEPENDENCY:** Depends on [Events](Events.md) for dispatch,
> [ObjectStore](ObjectStore.md) for blob storage, [Database](Database.md)
> for dedup checks and record insertion, [FileFormats](FileFormats.md)
> for format detection and child extraction.
