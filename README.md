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
| [Identity](docs/Identity.md) | Keycloak, authentication, tokens |
| [Lifecycle](docs/Lifecycle.md) | Service boot, health checks, dependency ordering |

### Data Layer
| Document | Description |
|----------|-------------|
| [Database](docs/Database.md) | PostgreSQL, Liquibase migrations, JDBI access |
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
| Framework | Quarkus |
| Language | Java 21 |
| Database | PostgreSQL 18 |
| Object Storage | MinIO (local), S3 (cloud) |
| Auth | Keycloak (OIDC) |
| Build | Gradle |
| Serialization | Protobuf (manifests) |
| Hashing | BLAKE3-128 |
| Compression | Zstandard |

## License

AGPL-3.0 — See [LICENSE](LICENSE)
