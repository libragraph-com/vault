# Database Schema

**Status:** Proposed design for Quarkus rebuild
**Last Updated:** 2026-02-07

## Design Principles

1. **Database is an index** - Object store is source of truth
2. **Minimal schema** - Only what's needed for queries
3. **No storage details** - Compression/extension are ObjectStorage concerns
4. **UUIDv7 for IDs** - Time-ordered, k-sortable

## Core Tables

### leaves

Content-addressed leaf nodes (deduplicated data blobs).

```sql
CREATE TABLE leaves (
    content_hash  BYTEA PRIMARY KEY,     -- BLAKE3-128 (16 bytes)
    size_bytes    BIGINT NOT NULL,       -- Original/leaf size
    mime_type     TEXT,
    metadata      JSONB,                 -- Intrinsic metadata (EXIF, ID3)
    full_text     TEXT,                  -- Extracted text for FTS
    tenant_id     UUID NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Design notes:**
- **No `extension` column** - Compression is ObjectStorage driver detail
- **No `stored_bytes` column** - Storage size irrelevant for queries
- BlobRef = (content_hash, size_bytes, isContainer) - all info for lookup
- `metadata` JSONB for format-specific fields (EXIF GPS, ID3 artist, etc.)

### containers

Original container files (ZIPs, PSTs, directories, etc.).

```sql
CREATE TABLE containers (
    id              UUID PRIMARY KEY DEFAULT vault_uuid_v7(),
    content_hash    BYTEA NOT NULL,        -- Container's content hash
    original_size   BIGINT NOT NULL,
    container_type  SMALLINT NOT NULL,     -- ZIP=1, PST=2, TAR=3, etc.
    entry_count     INTEGER NOT NULL,
    source_path     TEXT NOT NULL,         -- Original path at ingest
    parent_id       UUID,                  -- If extracted from another container
    tenant_id       UUID NOT NULL,
    ingested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (parent_id) REFERENCES containers(id)
);
```

**Design notes:**
- `id` is surrogate key (UUIDv7)
- `content_hash` is the BlobRef hash (many containers can have same hash if identical)
- `parent_id` builds the extraction tree

### entries

Files/directories within containers (manifest contents).

```sql
CREATE TABLE entries (
    id              UUID PRIMARY KEY DEFAULT vault_uuid_v7(),
    container_id    UUID NOT NULL,
    internal_path   TEXT NOT NULL,         -- Path within container
    entry_type      SMALLINT NOT NULL,     -- 0=file, 1=directory, 2=symlink
    target_hash     BYTEA NOT NULL,        -- Points to leaf or sub-container
    target_size     BIGINT NOT NULL,       -- Original size of target
    mtime           TIMESTAMPTZ,           -- Modification time from container
    metadata        JSONB,                 -- Entry-specific metadata
    FOREIGN KEY (container_id) REFERENCES containers(id)
);
```

**Design notes:**
- **No `storage_ext` column** - Was for BlobRef extension, now removed
- `target_hash` points to either a leaf or another container
- `entry_type` distinguishes files from directories
- Reconstruction: read manifest from object store, not from this table

## Indexes

```sql
-- Full-text search
CREATE INDEX idx_leaves_fts ON leaves USING GIN (to_tsvector('english', full_text));

-- Tenant isolation
CREATE INDEX idx_leaves_tenant ON leaves(tenant_id);
CREATE INDEX idx_containers_tenant ON containers(tenant_id);

-- Queries
CREATE INDEX idx_leaves_mime ON leaves(mime_type);
CREATE INDEX idx_entries_container ON entries(container_id);
CREATE INDEX idx_entries_target ON entries(target_hash);
CREATE INDEX idx_containers_parent ON containers(parent_id);
```

## Tasks Table

Background task persistence (see [Tasks](../Tasks.md)):

```sql
CREATE TABLE tasks (
    id              UUID PRIMARY KEY,       -- UUIDv7, generated in Java
    task_type       TEXT NOT NULL,
    status          SMALLINT NOT NULL,      -- 0=pending, 1=running, 2=blocked, 3=complete, 4=error
    input           JSONB NOT NULL,
    output          JSONB,
    parent_id       UUID,                   -- Subtask parent
    tenant_id       UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    error           TEXT,
    FOREIGN KEY (parent_id) REFERENCES tasks(id)
);

CREATE INDEX idx_tasks_tenant ON tasks(tenant_id);
CREATE INDEX idx_tasks_status ON tasks(status) WHERE status < 3;
CREATE INDEX idx_tasks_parent ON tasks(parent_id);
```

## UUIDv7 Function

Database function for `DEFAULT vault_uuid_v7()`:

```sql
-- PostgreSQL 18+: delegates to native uuidv7()
-- PostgreSQL 13-17: PL/pgSQL fallback using gen_random_uuid() + timestamp overlay
CREATE OR REPLACE FUNCTION vault_uuid_v7() RETURNS uuid AS $$
BEGIN
    IF current_setting('server_version_num')::int >= 180000 THEN
        RETURN uuidv7();
    END IF;

    -- Fallback: overlay millisecond timestamp onto random UUID
    RETURN encode(
        set_bit(
            set_bit(
                overlay(uuid_send(gen_random_uuid())
                    placing substring(int8send(floor(extract(epoch from clock_timestamp()) * 1000)::bigint) from 3)
                    from 1 for 6
                ),
                52, 1
            ),
            53, 1
        ),
        'hex')::uuid;
END;
$$ LANGUAGE plpgsql VOLATILE;
```

**Note:** Application code generates UUIDs in Java via UuidCreator (needed for
`TaskHandle.taskId()` at submit time). This function is only for `DEFAULT` values
and SQL migrations.

## Example Queries

### Find leaf by hash

```sql
SELECT * FROM leaves
WHERE content_hash = :hash
  AND tenant_id = :tenantId;
```

### Find containers containing a leaf

```sql
SELECT c.*
FROM containers c
JOIN entries e ON e.container_id = c.id
WHERE e.target_hash = :leafHash
  AND c.tenant_id = :tenantId;
```

### Full-text search

```sql
SELECT content_hash, size_bytes, ts_rank(to_tsvector('english', full_text), query) AS rank
FROM leaves, plainto_tsquery('english', :searchTerms) AS query
WHERE to_tsvector('english', full_text) @@ query
  AND tenant_id = :tenantId
ORDER BY rank DESC
LIMIT 20;
```

## Changes from vault-mvp

**Removed columns:**
- `leaves.extension` - Compression is driver detail, not query concern
- `leaves.stored_bytes` - Storage size irrelevant for queries
- `entries.storage_ext` - Same as leaves.extension

**Why removed:**
- BlobRef no longer has `extension` field (just `isContainer`)
- ObjectStorage handles compression transparently
- Database doesn't need to know storage format
- Simplifies schema, reduces coupling

**If we need storage stats later:**
- Query object store directly for sizes
- Or add to a separate `storage_stats` table
- But not in core `leaves` table

## See Also

- [BlobRef Design](BlobRef-Design.md) - Why no extension field
- [ObjectStorage API](ObjectStorage-API.md) - Compression transparency
- [RebuildSQL](../RebuildSQL.md) - Rebuilding this schema from object store
