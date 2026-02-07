# Database

PostgreSQL, Liquibase migrations, JDBI access patterns.

## Stack

| Component | Technology | Quarkus Extension |
|-----------|------------|-------------------|
| Database | PostgreSQL 18 | `quarkus-jdbc-postgresql` |
| Connection pool | Agroal | `quarkus-agroal` (included with JDBC) |
| Migrations | Flyway | `quarkus-flyway` |
| Query API | JDBI (SqlObject) | Manual wiring against Agroal |

> **DECISION:** Manual JDBI wiring via CDI producer: `Jdbi.create(agroalDataSource)`.
> Simple, zero extra dependencies, full control.

## Connection Configuration

```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/vault
quarkus.datasource.username=vault
quarkus.datasource.password=${DB_PASSWORD}

# Pool tuning
quarkus.datasource.jdbc.min-size=2
quarkus.datasource.jdbc.max-size=10
quarkus.datasource.jdbc.idle-removal-interval=PT10M
quarkus.datasource.jdbc.max-lifetime=PT30M
```

See [Quarkus Datasource Guide](https://quarkus.io/guides/datasource).

## Migrations

Flyway runs at startup, before the app serves requests.

```properties
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=db/migration
```

Migration files are plain SQL in `src/main/resources/db/migration/`:

```
V1__initial_schema.sql
V2__add_fts_indexes.sql
```

> **DECISION:** Flyway over Liquibase. SQL-file-based migrations are simpler
> for a SQL-first project. No XML, no abstraction layer. See
> [Quarkus Flyway Guide](https://quarkus.io/guides/flyway).

## Schema Design

Database is an **index**, not source of truth. Blobs and manifests in
object store are authoritative. See [RebuildSQL](RebuildSQL.md).

**Core tables:**
- `leaves` - Content-addressed leaf nodes (deduplicated data)
- `containers` - Original container files (ZIPs, PSTs, etc.)
- `entries` - Files/directories within containers

**Key principles:**
- UUIDv7 for all surrogate keys (time-ordered, k-sortable)
- No storage details (compression, extensions) - that's ObjectStorage concern
- JSONB for format-specific metadata
- Full-text search via PostgreSQL's built-in GIN indexes

> **See [research/Database-Schema.md](research/Database-Schema.md) for complete schema.**

## JDBI Access Pattern

JDBI SqlObject for declarative SQL mapping:

```java
public interface LeafDao {

    @SqlQuery("SELECT * FROM leaves WHERE content_hash = :hash AND tenant_id = :tenantId")
    @RegisterBeanMapper(Leaf.class)
    Optional<Leaf> findByHash(@Bind("hash") byte[] hash, @Bind("tenantId") UUID tenantId);

    @SqlQuery("SELECT EXISTS(SELECT 1 FROM leaves WHERE content_hash = :hash)")
    boolean exists(@Bind("hash") byte[] hash);

    @SqlUpdate("""
        INSERT INTO leaves (content_hash, size_bytes, mime_type, tenant_id)
        VALUES (:hash, :sizeBytes, :mimeType, :tenantId)
        ON CONFLICT (content_hash) DO NOTHING
        """)
    void insert(@BindBean LeafRecord record);
}
```

### Wiring JDBI

```java
@ApplicationScoped
public class JdbiProducer {

    @Inject AgroalDataSource dataSource;

    @Produces @ApplicationScoped
    public Jdbi jdbi() {
        Jdbi jdbi = Jdbi.create(dataSource);
        jdbi.installPlugin(new SqlObjectPlugin());
        jdbi.installPlugin(new PostgresPlugin());
        return jdbi;
    }

    @Produces @ApplicationScoped
    public LeafDao leafDao(Jdbi jdbi) {
        return jdbi.onDemand(LeafDao.class);
    }
}
```

> **DECISION:** Virtual threads. Blocking JDBC calls "just work" on virtual
> threads without reactive wrappers. REST endpoints use `@RunOnVirtualThread`.
> Quarkus 3.x has first-class virtual thread support. See
> [Quarkus Virtual Threads Guide](https://quarkus.io/guides/virtual-threads).

## Dev Services

Quarkus auto-provisions a PostgreSQL container for dev/test:

```properties
# No config needed — Quarkus detects quarkus-jdbc-postgresql
# and starts a testcontainer automatically in dev mode
quarkus.datasource.devservices.enabled=true  # default: true
```

See [Quarkus Dev Services](https://quarkus.io/guides/databases-dev-services).
