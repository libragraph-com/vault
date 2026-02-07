# Vault — AI Context

See [README.md](README.md) for overview and documentation index.

## Quick Context

- **Framework:** Quarkus (CDI/ArC, SmallRye Config, RESTEasy)
- **Language:** Java 21
- **Build:** Gradle (Quarkus plugin)
- **Database:** PostgreSQL 18 (JDBI, Liquibase)
- **Architecture:** Event-driven, content-addressed storage

## Key Decisions

- PostgreSQL only — no SQLite, no H2 in prod ([ADR-001](../pm/docs/decisions/adr-001-postgresql-only.md))
- Store outputs, not inputs — deduplicate leaves, keep manifests ([ADR-002](../pm/docs/decisions/adr-002-store-outputs-not-inputs.md))
- Event-driven ingestion — no recursion, fan-in synchronization ([ADR-008](../pm/docs/decisions/adr-008-event-driven-ingestion.md))
- BlobRef — single-lookup blob references (hash + leafSize + extension)

## Core Types

- **BlobRef** — `(ContentHash, leafSize, storedSize, extension)` — complete blob reference
- **ContentHash** — BLAKE3-128 (16 bytes)
- **Manifest** — Protobuf binary, stored at `{containerHash}-{containerSize}_`

## Architecture Principles

1. If you know the hash, you must know everything (size, extension). No guessing. No probing.
2. Manifests indexed by container hash (what they create), not manifest hash.
3. Dedup checks via database query, not storage probing.
4. BlobRef.toStoragePath() returns filename only; ObjectStorage handles sharding.

## Development Process

TBD — will be defined as we finalize the design docs.

## Related Repos

- `../pm` — Product docs, ADRs, vision (private)
- `../vault-mvp` — Prior implementation (being superseded)
