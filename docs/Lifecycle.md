# Lifecycle

Service boot, health checks, dependency ordering, and the ManagedService framework.

## ManagedService Framework

Vault services with managed lifecycle extend `AbstractManagedService`, which provides:

- **State machine:** `STOPPED → STARTING → RUNNING → STOPPING → STOPPED` (plus `FAILED`)
- **CDI events:** `ServiceStateChangedEvent` fired on every state transition
- **Dependency ordering:** `@DependsOn` annotation — service won't start unless dependencies are `RUNNING`
- **Failure cascade:** `ServiceDependencyCascade` observes failure events and propagates to dependents

### State Enum

```java
enum State { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
```

Matches the `resources` table CHECK constraint in V1 migration.

### ManagedService Interface

```java
public interface ManagedService {
    String serviceId();
    State state();
    void start() throws Exception;
    void stop() throws Exception;
    void fail(Throwable cause);
    default boolean isRunning() { return state() == State.RUNNING; }
}
```

### AbstractManagedService

Base class providing thread-safe state transitions and CDI event integration.
Subclasses implement `doStart()` and `doStop()`:

```java
@ApplicationScoped
@Startup
public class DatabaseService extends AbstractManagedService {

    @Inject Jdbi jdbi;

    @Override public String serviceId() { return "database"; }

    @Override protected void doStart() {
        // verify connectivity, store PG version
    }

    @Override protected void doStop() {
        // log, Agroal manages pool
    }

    @PostConstruct void init() { start(); }
    @PreDestroy void shutdown() { stop(); }
}
```

Key behaviors:
- `start()` is idempotent (no-op if already `RUNNING`)
- `stop()` is idempotent (no-op if already `STOPPED`)
- `fail()` transitions any state → `FAILED`, logs the cause
- State transitions are atomic via `AtomicReference<State>`

### @DependsOn Annotation

Declares that a service requires another service to be `RUNNING` before it can start:

```java
@ApplicationScoped
@DependsOn(DatabaseService.class)
public class TaskService extends AbstractManagedService {
    // start() will throw if DatabaseService is not RUNNING
}
```

Repeatable — a service can depend on multiple services.

### ServiceStateChangedEvent

CDI event record fired on every state transition:

```java
public record ServiceStateChangedEvent(
    String serviceId,
    ManagedService.State oldState,
    ManagedService.State newState,
    Instant timestamp
) {}
```

### Failure Cascade

`ServiceDependencyCascade` is a dedicated CDI observer bean that watches for `FAILED` events
and cascades to dependent services. It's separated from `AbstractManagedService` to avoid
circular bean creation during CDI event delivery at startup.

> **DECISION:** ManagedService framework with CDI events. Plain CDI lifecycle
> (`@PostConstruct`, `@Startup`) handles bean initialization, while `AbstractManagedService`
> adds state tracking, dependency validation, and failure cascade on top.

## Quarkus Lifecycle Mapping

| vault-mvp | Vault (Quarkus) |
|-----------|-----------------|
| `ServiceManager` | CDI/ArC + `AbstractManagedService` |
| `@Service` | `@ApplicationScoped` |
| `@DependsOn` | `@DependsOn(Service.class)` (custom annotation) |
| `@Inject` (custom) | `@Inject` (standard CDI) |
| `AbstractVaultService.onStart()` | `doStart()` via `AbstractManagedService` |
| `AbstractVaultService.onStop()` | `doStop()` via `AbstractManagedService` |
| Health checks | SmallRye Health (`@Liveness`, `@Readiness`) |
| Circuit breakers | MicroProfile Fault Tolerance (`@CircuitBreaker`) |

## Health Checks

SmallRye Health provides standardized health endpoints at `/q/health`.

`DatabaseHealthCheck` delegates to `DatabaseService`:

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

## Fault Tolerance

MicroProfile Fault Tolerance replaces vault-mvp's Resilience4j circuit breakers:

```java
@ApplicationScoped
public class ObjectStorageClient {

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(5000)
    public byte[] readBlob(BlobRef ref) {
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
called on all beans (triggering `doStop()` on managed services). Connection pools drain.

See [Quarkus Lifecycle](https://quarkus.io/guides/lifecycle).
