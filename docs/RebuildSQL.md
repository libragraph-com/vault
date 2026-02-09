# RebuildSQL

Protocol to rebuild the PostgreSQL index from object store contents.

## Why

The database is an **index**, not the source of truth. Blobs and manifests in
object store are authoritative. If the database is lost or corrupted, it can
be fully reconstructed.

## Flow

Two-pass approach using `listContainers()` and manifest parsing. Every blob is
reachable from a container manifest, so enumerating containers is sufficient to
reconstruct the full index.

```
Pass 1 — Enumerate containers:
1. ObjectStorage.listContainers(tenantId) → enumerate container keys (suffix `_`)
2. For each container key:
   a. BlobRef.parse(key) → BlobRef (isContainer=true)
   b. Read and parse Protobuf manifest
   c. Insert blob_ref + blob records for the container itself
   d. For each ManifestEntry:
      - Insert blob_ref + blob records for the child
      - Insert container + entry records
      - Each entry has target_hash, target_size, is_container

Pass 2 — Verify:
3. Verify referential integrity (all entry targets exist as blob_ref rows)
4. Rebuild indexes
```

Optionally pass `truncateFirst=true` to wipe existing rows before rebuild.

## Implementation

Same event/handler pattern as ingest/reconstruct. This is a task (see [Tasks](Tasks.md)).

```java
@ApplicationScoped
public class RebuildSqlTask implements VaultTask<RebuildInput, RebuildResult> {

    @Inject ObjectStorage storage;
    @Inject DatabaseService databaseService;

    @Override
    public String taskType() { return "rebuild.sql"; }

    @Override
    public TaskOutcome<RebuildResult> execute(RebuildInput input, TaskContext ctx) {
        String tenantId = input.tenantId();
        AtomicInteger containers = new AtomicInteger();
        AtomicInteger entries = new AtomicInteger();

        if (input.truncateFirst()) {
            truncateIndexTables();
        }

        // Enumerate containers only — every blob is reachable from a manifest
        storage.listContainers(tenantId).subscribe().asStream().forEach(containerRef -> {
            // Read and parse manifest
            BinaryData manifestData = storage.read(tenantId, containerRef).await().indefinitely();
            ManifestProto.Manifest manifest = ManifestProto.Manifest.parseFrom(manifestData.inputStream(0));

            // Insert blob_ref + blob for the container
            insertBlobRef(containerRef, tenantId);

            // Insert children from manifest entries
            for (ManifestProto.ManifestEntry entry : manifest.getEntriesList()) {
                BlobRef childRef = entry.getIsContainer()
                    ? BlobRef.container(ContentHash.fromBytes(entry.getTargetHash()), entry.getTargetSize())
                    : BlobRef.leaf(ContentHash.fromBytes(entry.getTargetHash()), entry.getTargetSize());
                insertBlobRef(childRef, tenantId);
                insertEntry(containerRef, childRef, entry);
                entries.incrementAndGet();
            }
            containers.incrementAndGet();
        });

        return TaskOutcome.complete(new RebuildResult(containers.get(), entries.get()));
    }
}
```

> **DECISION:** `ObjectStorage.listContainers(tenantId)` returns `Multi<BlobRef>` —
> enumerates only container keys (suffix `_`). S3 = prefix scan with suffix filter,
> filesystem = recursive walk with glob. Every blob is reachable from a container
> manifest, so a second pass over leaves is unnecessary.

> **DECISION:** Rebuild is structural only (leaves, containers, entries from
> filenames and manifests). Metadata re-extraction and FTS re-indexing are
> a separate follow-up enrichment task — too expensive to bundle.

## ObjectStorage Requirements

This workflow needs the ability to:
1. List containers for a tenant (`listContainers(tenantId)` → `Multi<BlobRef>`)
2. Read manifest blobs to parse their Protobuf content
3. Parse blob keys via `BlobRef.parse(key)` to extract hash, size, isContainer

See [ObjectStore](ObjectStore.md) for the interface.

## Triggers

- Admin CLI: `vault rebuild-sql --tenant=<id>`
- Admin REST: `POST /api/admin/rebuild-sql`
- Disaster recovery: after database restore failure

> **DEPENDENCY:** Depends on [ObjectStore](ObjectStore.md) for enumeration,
> [Database](Database.md) for schema, [Tasks](Tasks.md) for task tracking.
