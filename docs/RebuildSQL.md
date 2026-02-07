# RebuildSQL

Protocol to rebuild the PostgreSQL index from object store contents.

## Why

The database is an **index**, not the source of truth. Blobs and manifests in
object store are authoritative. If the database is lost or corrupted, it can
be fully reconstructed.

## Flow

```
1. ObjectStorage.list("") → enumerate all blob keys
2. For each blob key:
   a. Parse filename: {hash}-{leafSize}{extension}
   b. If extension == "_" → it's a manifest
      - Read and parse Protobuf manifest
      - Insert container record
      - Insert entry records for each ManifestEntry
      - Each entry has target_hash, target_size, storage_extension
   c. If extension != "_" → it's a leaf
      - Insert leaf record (hash, size, extension)
      - Optionally: re-extract metadata, re-index full text
3. Verify referential integrity
4. Rebuild indexes
```

## Implementation

Same event/handler pattern as ingest/reconstruct. This is a task (see [Tasks](Tasks.md)).

```java
@ApplicationScoped
public class RebuildSqlTask implements VaultTask<RebuildInput, RebuildResult> {

    @Inject ObjectStorage storage;
    @Inject LeafDao leafDao;
    @Inject ContainerDao containerDao;

    @Override
    public String taskType() { return "rebuild-sql"; }

    @Override
    public TaskOutcome<RebuildResult> execute(RebuildInput input, TaskContext ctx) {
        AtomicInteger leaves = new AtomicInteger();
        AtomicInteger manifests = new AtomicInteger();

        storage.list(input.tenantPrefix()).forEach(key -> {
            BlobRef ref = BlobRef.fromStoragePath(key);
            if (ref.isManifest()) {
                rebuildFromManifest(ref);
                manifests.incrementAndGet();
            } else {
                leafDao.insertIfAbsent(toLeafRecord(ref));
                leaves.incrementAndGet();
            }
        });

        return TaskOutcome.complete(new RebuildResult(leaves.get(), manifests.get()));
    }
}
```

> **OPEN QUESTION:** `ObjectStorage.list()` needs to work across the full
> tenant prefix, including sharded subdirectories. For S3 this is a prefix
> scan. For filesystem it's a recursive directory walk. The interface may
> need a `listRecursive(prefix)` method, or `list()` is always recursive.

> **OPEN QUESTION:** Should rebuild re-extract full text and metadata from
> blobs? This is expensive but makes the index complete. Could be a
> separate follow-up task. Minimum viable: just rebuild structural records
> (leaves, containers, entries) from filenames and manifests.

## ObjectStorage Requirements

This workflow needs the ability to:
1. List all keys under a prefix (`list(prefix)`)
2. Read manifest blobs to parse their Protobuf content
3. Parse blob filenames to extract hash, size, and extension

See [ObjectStore](ObjectStore.md) for the interface.

## Triggers

- Admin CLI: `vault rebuild-sql --tenant=<id>`
- Admin REST: `POST /api/admin/rebuild-sql`
- Disaster recovery: after database restore failure

> **DEPENDENCY:** Depends on [ObjectStore](ObjectStore.md) for enumeration,
> [Database](Database.md) for schema, [Tasks](Tasks.md) for task tracking.
