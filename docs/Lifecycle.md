# Lifecycle

Service boot, health checks, and dependency ordering.

## What Quarkus Provides

vault-mvp built a custom `ServiceManager` with topological sort, `@DependsOn`,
`@Service` annotations, circuit breakers, and reflection-based `@Inject`.
Quarkus provides all of this natively:

| vault-mvp | Quarkus Equivalent |
|-----------|-------------------|
| `ServiceManager` | CDI/ArC container |
| `@Service` | `@ApplicationScoped` |
| `@DependsOn` | CDI `@Inject` (implicit ordering) |
| `@Inject` (custom) | `@Inject` (standard CDI) |
| `AbstractVaultService.onStart()` | `@PostConstruct` or `@Startup` |
| `AbstractVaultService.onStop()` | `@PreDestroy` or `Shutdown` event |
| Health checks | SmallRye Health (`@Liveness`, `@Readiness`) |
| Circuit breakers | MicroProfile Fault Tolerance (`@CircuitBreaker`) |
| SPI discovery | Jandex bean discovery |

## Bean Lifecycle

```java
@ApplicationScoped
public class BlobService {

    @Inject ObjectStorage storage;
    @Inject AgroalDataSource dataSource;

    @PostConstruct
    void init() {
        // storage and dataSource are guaranteed injected
        // Called once, when first needed (lazy) or at startup (if @Startup)
    }

    @PreDestroy
    void shutdown() {
        // Cleanup resources
    }
}
```

### Eager Startup

By default, CDI beans are lazy. Force eager initialization with `@Startup`:

```java
@ApplicationScoped
@Startup
public class DatabaseMigrator {

    @Inject Liquibase liquibase;

    @PostConstruct
    void migrate() {
        liquibase.migrate();
        Log.info("Database migrations applied");
    }
}
```

See [Quarkus CDI Lifecycle](https://quarkus.io/guides/cdi-reference#startup-event).

## Boot Ordering

CDI injection naturally creates a dependency graph. If `TaskService` injects
`DatabaseService`, the database bean is initialized before the task service.

For explicit ordering between `@Startup` beans, use `@Dependent` injection
or observe startup events:

```java
@ApplicationScoped
@Startup
public class TaskService {

    @Inject DatabaseService db;  // Forces db to init first

    @PostConstruct
    void init() {
        // db is guaranteed to be up
        db.ping(); // Optional explicit health check
    }
}
```

> **OPEN QUESTION:** vault-mvp's `ManagedService` with state machine
> (DOWN → STARTING → UP → STOPPING → DOWN) is richer than CDI lifecycle.
> Do we need a `ManagedService` interface in Quarkus, or is
> `@ApplicationScoped` + health checks sufficient?
>
> Proposal: Start with plain CDI lifecycle. Add `ManagedService` wrapper
> only if we need cascading failure propagation (service A goes down →
> dependents also go down). Quarkus doesn't do this natively.

## Health Checks

SmallRye Health provides standardized health endpoints at `/q/health`.

```java
@Readiness
@ApplicationScoped
public class DatabaseHealthCheck implements HealthCheck {

    @Inject AgroalDataSource dataSource;

    @Override
    public HealthCheckResponse call() {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SELECT 1");
            return HealthCheckResponse.up("database");
        } catch (SQLException e) {
            return HealthCheckResponse.down("database");
        }
    }
}
```

Quarkus auto-generates health checks for datasources and other managed resources.

```properties
# Enabled by default with quarkus-smallrye-health
quarkus.health.extensions.enabled=true
```

See [Quarkus Health Guide](https://quarkus.io/guides/smallrye-health).

## Fault Tolerance

MicroProfile Fault Tolerance replaces vault-mvp's Resilience4j circuit breakers:

```java
@ApplicationScoped
public class ObjectStorageClient {

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(5000)
    public byte[] readBlob(BlobRef ref) {
        // If this fails too often, circuit opens
        return storage.read(ref);
    }
}
```

See [Quarkus Fault Tolerance](https://quarkus.io/guides/smallrye-fault-tolerance).

## Graceful Shutdown

Quarkus handles graceful shutdown natively:

```properties
quarkus.shutdown.timeout=30s
```

In-flight requests complete within the timeout. `@PreDestroy` methods are
called on all beans. Connection pools drain.

See [Quarkus Lifecycle](https://quarkus.io/guides/lifecycle).
