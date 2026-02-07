# Architecture

## Deployment Topologies

### Local (Docker Compose)

Primary development and personal-use deployment.

```
Docker Compose:
  ├── vault         (this app — Quarkus)
  ├── postgres:18   (database)
  ├── minio         (object storage)
  ├── keycloak      (OIDC/IDP)
  └── ollama        (embeddings, optional)
```

### SaaS / Enterprise

```
K8s Cluster:
  ├── vault         (this app)
  ├── keycloak      (OIDC/IDP)
  └── gateway       (reverse proxy, TLS termination)

Managed Services:
  ├── PostgreSQL    (RDS / Neon / Supabase)
  ├── Object Store  (S3 / Azure Blob)
  └── LLM API      (embeddings)
```

The same application artifact runs in both topologies. Behavior is controlled
entirely by Quarkus config profiles — see [Platform](Platform.md).

## Module Structure

> **OPEN QUESTION:** Should this be a Quarkus multi-module Gradle project (like
> vault-mvp was), or a single module? Quarkus supports both. Multi-module adds
> build complexity but cleanly separates concerns (core, api, cli, formats).

Proposed modules (carry forward from vault-mvp):

```
vault/
├── shared/types/         # BlobRef, ContentHash, enums — no framework deps
├── formats/framework/    # FormatHandlerFactory, Handler, DetectionCriteria
├── modules/
│   ├── core/             # Ingestion, reconstruction, blob storage, events
│   ├── api/              # REST endpoints (RESTEasy)
│   ├── cli/              # CLI (quarkus-picocli)
│   ├── mcp/              # MCP server (AI integration)
│   └── fuse/             # FUSE virtual filesystem
└── app/                  # Quarkus application entry point
```

**Dependency direction:** `shared/types` ← `formats/framework` ← `core` ← `api`, `cli`, `mcp`, `fuse`

> **DEPENDENCY:** Module boundaries affect how CDI bean discovery works.
> Quarkus uses Jandex to index beans at build time. Multi-module requires
> each module to produce a Jandex index. See
> [Quarkus CDI Reference](https://quarkus.io/guides/cdi-reference#bean_discovery).

## Data Flow Overview

```
Ingest:    Source → Buffer → Hash → Dedup Check → Detect → [Container|Leaf]
                                                     ↓
                                            Children (event-driven)
                                                     ↓
                                            Manifest + DB records

Retrieve:  BlobRef → ObjectStorage.read(path) → decompress → content

Reconstruct: ContainerRef → Manifest → entries[] → BlobRef per entry → reassemble
```

See [Ingestion](Ingestion.md), [Reconstruction](Reconstruction.md) for protocol details.

## Storage Layout

Blob storage keys encode the BlobRef:

```
{hash}-{leafSize}      # Data blob (leaf)
{hash}-{leafSize}_     # Container (manifest/recipe)
```

The `_` suffix distinguishes containers from data blobs, enabling fast enumeration
(`ls **/*_` or glob patterns for containers only).

**Compression is an ObjectStorage driver implementation detail:**
- **Filesystem (dev):** No compression, stored as-is for debugging
- **MinIO (prod):** Transparent internal compression
- **S3 (prod):** Optional client-side compression for text-like MIME types

Physical layout varies by backend:
- **Filesystem:** `{root}/{tenantId}/{tier1}/{tier2}/{key}` (2-tier sharding)
- **MinIO/S3:** `{bucket-prefix}{tenantId}/{key}` (bucket per tenant)

See [ObjectStore](ObjectStore.md) and [research/BlobRef-Design.md](research/BlobRef-Design.md).

## Key Architectural Principles

1. **Store outputs, not inputs** — Deduplicate leaves, keep manifests for reconstruction.
   Original containers are disposable after verified ingest. ([ADR-002](../pm/docs/decisions/adr-002-store-outputs-not-inputs.md))

2. **Content-addressed everything** — BlobRef (hash + leafSize + isContainer) is the
   compound key. Hash alone can collide. You always need the full BlobRef.

3. **Event-driven processing** — No recursion. Handlers emit events, other handlers
   consume them. Bounded stack depth regardless of nesting. ([ADR-008](../pm/docs/decisions/adr-008-event-driven-ingestion.md))

4. **Single-lookup retrieval** — BlobRef contains everything needed to locate a blob.
   No probing multiple extensions. No guessing.

5. **Database is an index, not source of truth** — Blobs and manifests in object store
   are authoritative. Database can be rebuilt from object store. See [RebuildSQL](RebuildSQL.md).

6. **Leverage the framework** — Use Quarkus CDI, config, health, lifecycle, dev services
   instead of reimplementing. "The Quarkus Way" over custom infrastructure.
