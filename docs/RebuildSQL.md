# RebuildSQL

Protocol to rebuild the PostgreSQL index from object store contents.

## Why

The database is an **index**, not the source of truth. Blobs and manifests in
object store are authoritative. If the database is lost or corrupted, it can
be fully reconstructed.

## Flow

```
1. ObjectStorage.listContainers(tenantId) + listLeaves(tenantId) → enumerate all blob keys
2. For each blob key:
   a. BlobRef.parse(key) → BlobRef
   b. If ref.isContainer() → it's a manifest
      - Read and parse Protobuf manifest
      - Insert container record
      - Insert entry records for each ManifestEntry
      - Each entry has target_hash, target_size, is_container
   c. If !ref.isContainer() → it's a leaf
      - Insert leaf record (hash, size)
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

        storage.listAll(input.tenantId()).forEach(key -> {
            BlobRef ref = BlobRef.parse(key);
            if (ref.isContainer()) {
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

> **DECISION:** `ObjectStorage.listAll(tenantId)` returns `Multi<BlobRef>` —
> always recursive. S3 = prefix scan, filesystem = recursive walk. The driver
> handles traversal; callers just stream results.

> **DECISION:** Rebuild is structural only (leaves, containers, entries from
> filenames and manifests). Metadata re-extraction and FTS re-indexing are
> a separate follow-up enrichment task — too expensive to bundle.

## ObjectStorage Requirements

This workflow needs the ability to:
1. List all blobs for a tenant (`listAll(tenantId)` → `Multi<BlobRef>`)
2. Read manifest blobs to parse their Protobuf content
3. Parse blob keys via `BlobRef.parse(key)` to extract hash, size, isContainer

See [ObjectStore](ObjectStore.md) for the interface.

## Triggers

- Admin CLI: `vault rebuild-sql --tenant=<id>`
- Admin REST: `POST /api/admin/rebuild-sql`
- Disaster recovery: after database restore failure

> **DEPENDENCY:** Depends on [ObjectStore](ObjectStore.md) for enumeration,
> [Database](Database.md) for schema, [Tasks](Tasks.md) for task tracking.
