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
V2__schema_fixes.sql
```

> **DECISION:** Flyway over Liquibase. SQL-file-based migrations are simpler
> for a SQL-first project. No XML, no abstraction layer. See
> [Quarkus Flyway Guide](https://quarkus.io/guides/flyway).

## Schema Design

Database is an **index**, not source of truth. Blobs and manifests in
object store are authoritative. See [RebuildSQL](RebuildSQL.md).

The canonical schema is defined in [`docs/research/RevisedSchema.sql`](research/RevisedSchema.sql).

**Core tables:**
- `blob_ref` — Global content-addressed blob registry (spans tenants, deduplication boundary)
- `blob` — Per-tenant blob ownership (same `blob_ref` in two tenants = two rows) (V2: `UNIQUE(tenant_id, blob_ref_id)` enforces dedup invariant)
- `container` — Blobs that have extractable children (V2: `parent_id` removed — multi-parent via entries table)
- `entry` — Files/directories within containers

**Lookup tables:**
- `task_status` — Task state enum (`OPEN`, `IN_PROGRESS`, `BLOCKED`, etc.)
- `entry_type` — Container entry kinds (`file`, `directory`, `symlink`)
- `format_handler` — Registered format handlers (upserted at startup)
- `organization` — Organization registry (V2: `UNIQUE(name)`)
- `tenant` — Tenant registry (V2: `UNIQUE(org_id, name)`)

**Key principles:**
- SERIAL/BIGSERIAL for surrogate keys on ephemeral/internal data (better join performance, smaller indexes)
- UUIDs only for external identity (`organization.global_id`, `tenant.global_id`)
- Lookup tables instead of ENUMs or VARCHAR CHECK constraints (extensible, FK-enforced)
- No storage details (compression, extensions) — that is an ObjectStorage concern
- JSONB for format-specific metadata
- Full-text search via PostgreSQL's built-in GIN indexes

## JDBI Access Pattern

JDBI SqlObject DAOs provide declarative SQL mapping. Each DAO interface is
attached via `jdbi.onDemand(Dao.class)` or `handle.attach(Dao.class)`:

```java
public interface BlobRefDao {

    @SqlQuery("SELECT * FROM blob_ref WHERE content_hash = :hash AND leaf_size = :leafSize AND container = :container")
    @RegisterConstructorMapper(BlobRefRecord.class)
    Optional<BlobRefRecord> findByRef(@Bind("hash") byte[] hash,
                                       @Bind("leafSize") long leafSize,
                                       @Bind("container") boolean container);

    @SqlQuery("SELECT EXISTS(SELECT 1 FROM blob_ref WHERE content_hash = :hash AND leaf_size = :leafSize AND container = :container)")
    boolean exists(@Bind("hash") byte[] hash,
                   @Bind("leafSize") long leafSize,
                   @Bind("container") boolean container);

    @SqlUpdate("""
        INSERT INTO blob_ref (content_hash, leaf_size, container, mime_type, handler)
        VALUES (:contentHash, :leafSize, :container, :mimeType, :handler)
        ON CONFLICT (content_hash, leaf_size, container) DO NOTHING
        RETURNING id
        """)
    @GetGeneratedKeys
    long insert(@BindBean BlobRefRecord record);
}
```

```java
public interface TaskDao {

    @SqlQuery("SELECT * FROM task WHERE id = :id")
    @RegisterBeanMapper(TaskRecord.class)
    Optional<TaskRecord> findById(@Bind("id") int id);

    @SqlUpdate("UPDATE task SET status = :status, completed_at = now() WHERE id = :id")
    void updateStatus(@Bind("id") int id, @Bind("status") short status);
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
