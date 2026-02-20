# Vault

Content-addressed storage and knowledge graph system built on Quarkus.

**Status:** Design phase — documenting architecture before implementation.

## What It Does

- Recursively unpacks nested archives (ZIP, PST, TAR, ISO, etc.)
- Deduplicates content globally via content-addressed storage
- Builds provenance graph (where every file came from)
- Supports bit-identical container reconstruction from manifests + leaves
- Full-text and semantic search via PostgreSQL
- Pluggable format handlers and enrichment tasks

## Documentation

### Foundations
| Document | Description |
|----------|-------------|
| [Architecture](docs/Architecture.md) | Deployment topologies, module structure |
| [Platform](docs/Platform.md) | Configuration, multi-tenancy, partitioning |
| [Identity](docs/Identity.md) | Authentication, OIDC, tokens, passkeys |
| [Lifecycle](docs/Lifecycle.md) | Service boot, health checks, dependency ordering |

### Data Layer
| Document | Description |
|----------|-------------|
| [Database](docs/Database.md) | PostgreSQL, Flyway migrations, JDBI access |
| [ObjectStore](docs/ObjectStore.md) | Blob storage abstraction (FS, MinIO, S3) |

### Processing
| Document | Description |
|----------|-------------|
| [Events](docs/Events.md) | CDI events, Quarkus event bus patterns |
| [Tasks](docs/Tasks.md) | Background task/workflow system |
| [Ingestion](docs/Ingestion.md) | Ingest protocol and pipeline |
| [Reconstruction](docs/Reconstruction.md) | Container reconstruction protocol |
| [RebuildSQL](docs/RebuildSQL.md) | Rebuild database index from object store |
| [FileFormats](docs/FileFormats.md) | Format plugin system |

### Operations
| Document | Description |
|----------|-------------|
| [REST](docs/REST.md) | REST API, JWT identity, file upload/download |
| [Logging](docs/Logging.md) | Logging API and configuration |
| [Testing](docs/Testing.md) | Test patterns and infrastructure |

## Prior Art

- **vault-mvp** — Working prototype that proved core data processing. Being superseded by this Quarkus rebuild.
- **pm/** — Product vision, ADRs, design docs. High-level source of truth for *what*; this repo is source of truth for *how*.

## Tech Stack

| Component | Technology |
|-----------|------------|
| Framework | Quarkus (virtual threads) |
| Language | Java 25 (`--release 21` bytecode) |
| Database | PostgreSQL 17 (JDBI, Flyway) |
| Object Storage | MinIO (local), S3 (cloud) |
| Auth | OIDC (Cognito for cloud, passkey for local) |
| Build | Gradle (multi-module) |
| Serialization | Protobuf (manifests) |
| Hashing | BLAKE3-128 |
| Compression | Zstandard (driver-level) |

## License

**Vault:** AGPL-3.0 with commercial dual-license option — See [LICENSE](LICENSE)

LibRAGraph uses an **open-core model** ([ADR-019](../pm/docs/decisions/adr-019-open-core-gateway-model.md), [ADR-028](../pm/docs/decisions/adr-028-commercial-model.md)):
- **Vault** (this repo): AGPL-3.0 — genuinely open source. Commercial dual-license available for ISVs who need to avoid copyleft obligations.
- **Console & Gateway**: BSL 1.1 — source-available, converts to Apache 2.0 after 3-4 years
- **OAuth Relay**: Apache 2.0

This preserves vault sovereignty (fully functional without our services) while protecting cloud service revenue.
