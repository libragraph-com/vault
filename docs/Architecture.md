# Architecture

## Deployment Topologies

### Local (Docker Compose)

Primary development and personal-use deployment.

```
Docker Compose:
  ├── vault         (this app — Quarkus)
  ├── postgres:17   (database)
  ├── minio         (object storage)
  └── ollama        (embeddings, optional)
```

### SaaS / Enterprise

```
K8s Cluster:
  ├── vault         (this app)
  └── gateway       (reverse proxy, TLS termination)

Authentication:
  ├── Cloud:        Cognito via Console (ADR-020, ADR-030)
  └── Local:        Passkey/WebAuthn (ADR-030)

Managed Services:
  ├── PostgreSQL    (RDS / Neon / Supabase)
  ├── Object Store  (S3 / Azure Blob)
  └── LLM API      (embeddings)
```

The same application artifact runs in both topologies. Behavior is controlled
entirely by Quarkus config profiles — see [Platform](Platform.md).

## Module Structure

> **DECISION:** Multi-module Gradle project. vault-mvp proved the module
> boundaries are real (shared types have zero framework deps, format plugins
> are independently testable, API is a thin layer over core). Single-module
> would conflate these concerns and make future extraction (CLI, MCP) harder.

Modules:

```
vault/
├── shared/types/         # BlobRef, enums — no framework deps
├── shared/utils/         # ContentHash, BinaryData, Buffer — no framework deps
├── modules/
│   ├── core/             # Ingestion, reconstruction, blob storage, events
│   ├── formats/          # FormatHandlerFactory, Handler, DetectionCriteria, codecs, Tika
│   ├── api/              # REST endpoints (RESTEasy)
│   └── gateway/          # WebSocket client to cloud Gateway (planned)
└── app/                  # Quarkus application entry point
```

**Dependency direction:** `shared/types` ← `shared/utils` ← `modules/formats` ← `core` ← `api`

> **NOTE:** Multi-module requires each module to produce a Jandex index for
> CDI bean discovery. See
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
