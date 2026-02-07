# Database

PostgreSQL, Flyway migrations, JDBI access via DatabaseService.

## Stack

| Component | Technology | Quarkus Extension |
|-----------|------------|-------------------|
| Database | PostgreSQL 17 | `quarkus-jdbc-postgresql` |
| Connection pool | Agroal | `quarkus-agroal` (included with JDBC) |
| Migrations | Flyway | `quarkus-flyway` |
| Query API | JDBI (SqlObject) | Manual wiring against Agroal |
| Lifecycle | DatabaseService | `AbstractManagedService` |

## DatabaseService

`DatabaseService` is the primary database access point. It extends `AbstractManagedService`
and starts eagerly via `@Startup`:

```java
@ApplicationScoped
@Startup
public class DatabaseService extends AbstractManagedService {
    // serviceId() → "database"
    // doStart() → SELECT version(), stores PG version
    // doStop() → log (Agroal manages pool shutdown)
    // jdbi() → gated on isRunning()
    // ping() → SELECT 1, calls fail() on error
    // pgVersion() → version string from startup probe
}
```

### Accessing JDBI

Use `DatabaseService.jdbi()` to get the JDBI instance. This method gates on the service
being in `RUNNING` state:

```java
@Inject DatabaseService databaseService;

void doWork() {
    Jdbi jdbi = databaseService.jdbi();
    jdbi.useHandle(h -> h.createUpdate("INSERT INTO ...").execute());
}
```

### JdbiProducer

`JdbiProducer` remains as the CDI producer that creates the `Jdbi` instance from
`AgroalDataSource`. DatabaseService injects this produced bean:

```java
@ApplicationScoped
public class JdbiProducer {
    @Produces @Singleton
    public Jdbi jdbi(AgroalDataSource dataSource) {
        return Jdbi.create(dataSource)
                .installPlugin(new PostgresPlugin())
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new Jackson2Plugin())
                .setSqlLogger(new Slf4JSqlLogger());
    }
}
```

> **DECISION:** Manual JDBI wiring via CDI producer: `Jdbi.create(agroalDataSource)`.
> Simple, zero extra dependencies, full control. DatabaseService wraps the produced
> Jdbi with lifecycle gating.

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
quarkus.flyway.locations=classpath:db/migration
```

Migration files are plain SQL in `app/src/main/resources/db/migration/`:

```
V1__initial_schema.sql
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

> **DECISION:** Virtual threads. Blocking JDBC calls "just work" on virtual
> threads without reactive wrappers. REST endpoints use `@RunOnVirtualThread`.
> Quarkus 3.x has first-class virtual thread support. See
> [Quarkus Virtual Threads Guide](https://quarkus.io/guides/virtual-threads).

## Health Check

`DatabaseHealthCheck` delegates to `DatabaseService.state()` and `pgVersion()`:

```java
@Readiness
@ApplicationScoped
public class DatabaseHealthCheck implements HealthCheck {

    @Inject DatabaseService databaseService;

    @Override
    public HealthCheckResponse call() {
        if (databaseService.isRunning()) {
            return HealthCheckResponse.named("database")
                    .up()
                    .withData("version", databaseService.pgVersion())
                    .withData("state", databaseService.state().name())
                    .build();
        } else {
            return HealthCheckResponse.named("database")
                    .down()
                    .withData("state", databaseService.state().name())
                    .build();
        }
    }
}
```

## Dev Services

Quarkus auto-provisions a PostgreSQL container for dev/test:

```properties
# No config needed — Quarkus detects quarkus-jdbc-postgresql
# and starts a testcontainer automatically in dev mode
quarkus.datasource.devservices.enabled=true  # default: true
```

See [Quarkus Dev Services](https://quarkus.io/guides/databases-dev-services).
