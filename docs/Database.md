# Database

PostgreSQL, Liquibase migrations, JDBI access patterns.

## Stack

| Component | Technology | Quarkus Extension |
|-----------|------------|-------------------|
| Database | PostgreSQL 18 | `quarkus-jdbc-postgresql` |
| Connection pool | Agroal | `quarkus-agroal` (included with JDBC) |
| Migrations | Liquibase | `quarkus-liquibase` |
| Query API | JDBI (SqlObject) | `quarkus-jdbi` or manual integration |

> **OPEN QUESTION:** There is a community `quarkus-jdbi` extension. Need to
> evaluate if it's mature enough or if we wire JDBI manually against Agroal.
> Manual wiring is simple: `Jdbi.create(agroalDataSource)`.

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

Liquibase runs at startup, before the app serves requests.

```properties
quarkus.liquibase.migrate-at-start=true
quarkus.liquibase.change-log=db/changelog-master.xml
```

```xml
<!-- src/main/resources/db/changelog-master.xml -->
<databaseChangeLog>
    <include file="db/changelog/001-initial-schema.xml"/>
    <include file="db/changelog/002-add-tenant-id.xml"/>
</databaseChangeLog>
```

See [Quarkus Liquibase Guide](https://quarkus.io/guides/liquibase).

> **OPEN QUESTION:** Liquibase vs Flyway? Both have Quarkus extensions.
> Liquibase is XML-based with rollback support. Flyway is SQL-file-based
> and simpler. vault-mvp used neither (raw schema.sql). Flyway is simpler
> for a SQL-first approach. Liquibase is more flexible for complex migrations.

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

> **OPEN QUESTION:** vault-mvp wrapped all JDBC calls in `Mono.fromCallable()`
> on `Schedulers.boundedElastic()` for async. In Quarkus, do we:
> (a) Keep reactive with Mutiny + worker thread,
> (b) Use imperative/blocking (Quarkus RESTEasy Reactive handles offloading), or
> (c) Use virtual threads (`quarkus.virtual-threads.enabled=true`)?
>
> Virtual threads are the simplest path — blocking JDBC "just works" without
> Reactor overhead. Quarkus 3.x has first-class virtual thread support.

## Dev Services

Quarkus auto-provisions a PostgreSQL container for dev/test:

```properties
# No config needed — Quarkus detects quarkus-jdbc-postgresql
# and starts a testcontainer automatically in dev mode
quarkus.datasource.devservices.enabled=true  # default: true
```

See [Quarkus Dev Services](https://quarkus.io/guides/databases-dev-services).
