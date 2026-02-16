# Vault — AI Context

See [README.md](README.md) for overview and documentation index.

## Quick Context

- **Framework:** Quarkus (CDI/ArC, SmallRye Config, RESTEasy Reactive)
- **Language:** Java 25, `--release 21` bytecode (virtual threads)
- **Build:** Gradle 9.3.1 (multi-module, Quarkus 3.31.2 plugin)
- **Database:** PostgreSQL 17 (JDBI manual wiring, Flyway migrations)
- **Architecture:** Event-driven, content-addressed storage

## Key Decisions

- PostgreSQL only — no SQLite, no H2 in prod ([ADR-001](../pm/docs/decisions/adr-001-postgresql-only.md))
- Store outputs, not inputs — deduplicate leaves, keep manifests ([ADR-002](../pm/docs/decisions/adr-002-store-outputs-not-inputs.md))
- Event-driven ingestion — CDI events, fan-in synchronization ([ADR-008](../pm/docs/decisions/adr-008-event-driven-ingestion.md))
- Open-core architecture — Vault (AGPL), Console/Gateway (BSL) ([ADR-019](../pm/docs/decisions/adr-019-open-core-gateway-model.md))
- Passwordless authentication — passkey primary, OIDC exchange, no passwords ([ADR-014](../pm/docs/decisions/adr-014-authentication-principals.md), [ADR-030](../pm/docs/decisions/adr-030-invitations-and-roles.md))
- BlobRef — `(hash, leafSize, isContainer)` — no extension, no storedSize
- Virtual threads for blocking I/O (JDBC, object storage)
- Flyway for SQL migrations (SQL-file-based, no XML)
- Manual JDBI wiring via CDI producer (no quarkus-jdbi extension)
- CDI only for plugin discovery (no SPI fallback)
- Port vault-mvp task system to CDI with PostgreSQL persistence
- Config-driven ObjectStorage backend selection (YAML, not annotations)

## Core Types

- **BlobRef** — `(ContentHash, leafSize, isContainer)` — complete blob reference
- **ContentHash** — BLAKE3-128 (16 bytes)
- **Manifest** — Protobuf binary, stored at `{containerHash}-{containerSize}_`

## Architecture Principles

1. If you know the hash, you must know everything (size, isContainer). No guessing. No probing.
2. Manifests indexed by container hash (what they create), not manifest hash.
3. Dedup checks via database query, not storage probing.
4. BlobRef.toString() returns storage key; ObjectStorage handles sharding.
5. Compression is an ObjectStorage driver implementation detail — BlobRef has no extension.

## Development Process

TBD — will be defined as we finalize the design docs.

## Related Repos

- `../pm` — Product docs, ADRs, vision (private)
- `../vault-mvp` — Prior implementation (being superseded)
