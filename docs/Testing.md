# Testing

Test patterns, infrastructure, and configuration.

## Framework

- **JUnit 5** (Jupiter) — test runner
- **`@QuarkusTest`** — full CDI container with dev services
- **`@QuarkusIntegrationTest`** — tests against packaged app (JAR or native)
- **AssertJ** — fluent assertions
- **RestAssured** — HTTP endpoint testing

## Test Categories

### Unit Tests (no container)

Plain JUnit 5. Used for shared types, format framework, pure logic:

```java
class ContentHashTest {
    @Test
    void hashEquality() {
        byte[] bytes = new byte[]{1, 2, 3};
        ContentHash a = ContentHash.of(bytes);
        ContentHash b = ContentHash.of(bytes);
        assertThat(a).isEqualTo(b);
    }
}
```

### Integration Tests (`@QuarkusTest`)

Full CDI container. Quarkus Dev Services auto-provisions PostgreSQL:

```java
@QuarkusTest
class DatabaseServiceTest {
    @Inject DatabaseService databaseService;

    @Test void startsInRunningState() {
        assertThat(databaseService.state()).isEqualTo(ManagedService.State.RUNNING);
    }

    @Test void pingReturnsTrue() {
        assertThat(databaseService.ping()).isTrue();
    }
}
```

### SQL Tests

Direct database verification via `DatabaseService.jdbi()`:

```java
@QuarkusTest
class LeafInsertTest {
    @Inject DatabaseService databaseService;
    @Inject VaultTestConfig testConfig;

    @Test void insertLeafAndVerify() {
        var jdbi = databaseService.jdbi();
        byte[] hash = new byte[16];
        new SecureRandom().nextBytes(hash);
        // INSERT into leaves, SELECT back, assert equality
    }
}
```

### Service Dependency Tests

`TestService` validates `@DependsOn` ordering and failure cascade:

```java
@ApplicationScoped
@DependsOn(DatabaseService.class)
public class TestService extends AbstractManagedService {
    // Starts only when DatabaseService is RUNNING
    // Cascades to FAILED when DatabaseService fails
}
```

```java
@QuarkusTest
class DependencyOrderingTest {
    @Inject DatabaseService databaseService;
    @Inject TestService testService;

    @Test void testServiceStartsAfterDatabase() {
        assertThat(databaseService.state()).isEqualTo(State.RUNNING);
        testService.start();
        assertThat(testService.state()).isEqualTo(State.RUNNING);
    }
}
```

### REST Tests

```java
@QuarkusTest
class DiagnosticResourceTest {
    @Test void healthReady_returnsUp() {
        given()
            .when().get("/q/health/ready")
            .then()
            .statusCode(200)
            .body("status", is("UP"));
    }
}
```

## Test Configuration

Tests use the `test` profile automatically. Override in `application-test.properties`:

```properties
quarkus.devservices.enabled=true
quarkus.datasource.db-kind=postgresql
quarkus.datasource.devservices.enabled=true

vault.object-store.type=filesystem
vault.ollama.enabled=false

vault.test.tenant-id=
vault.test.reset-tenant=false
```

## Test Parameters via Gradle

Forward test configuration from command line via `-P` flags:

```bash
# Default: ephemeral DB via Testcontainers, auto-generated tenant
./gradlew test

# Run against Docker Compose dev DB (data persists across runs)
docker compose up -d
./gradlew test -Pvault.test.profile=dev

# Use specific tenant ID
./gradlew test -Pvault.test.tenantId=abc-123

# Reset tenant data before test run (dev DB only — ephemeral DB is always fresh)
./gradlew test -Pvault.test.profile=dev -Pvault.test.resetTenant=true
```

### How profile override works

`-Pvault.test.profile=dev` sets `quarkus.test.profile`, which tells Quarkus to
activate the `dev` profile **instead of** the `test` profile. The `dev` profile disables
DevServices and points at `jdbc:postgresql://localhost:5432/vault` (Docker Compose),
so tests hit the persistent database instead of a throwaway Testcontainer.

Property forwarding is configured in `app/build.gradle.kts` (must be after the `io.quarkus`
plugin to avoid the plugin overriding settings). Gradle `-P` flags are mapped to
kebab-case JVM system properties for Quarkus SmallRye Config:

```kotlin
// app/build.gradle.kts
tasks.withType<Test> {
    val propMappings = mapOf(
        "vault.test.tenantId"     to "vault.test.tenant-id",
        "vault.test.resetTenant"  to "vault.test.reset-tenant",
        "vault.test.profile"      to "quarkus.test.profile",
    )
    propMappings.forEach { (gradleProp, sysProp) ->
        val value = project.findProperty(gradleProp) as String?
        inputs.property(gradleProp, value ?: "")  // Gradle re-runs when flags change
        if (value != null) systemProperty(sysProp, value)
    }
}
```

> **Note:** Property forwarding MUST live in `app/build.gradle.kts`, not the root
> `build.gradle.kts`. The Quarkus Gradle plugin overrides `Test` task configuration
> set by the root `subprojects` block. Gradle's `systemProperty()` only reaches the
> test JVM when configured after the plugin.

### VaultTestConfig

CDI bean that reads test configuration:

```java
@ApplicationScoped
public class VaultTestConfig {
    // tenantId() → configured UUID or auto-generated
    // resetTenant() → boolean, default false
}
```

## Dev Services

Quarkus auto-provisions external dependencies for dev and test:

| Service | Extension | Auto-provisioned |
|---------|-----------|-----------------|
| PostgreSQL | `quarkus-jdbc-postgresql` | Yes (testcontainer) |
| MinIO/S3 | Manual (MinioClientProducer) | Via Docker Compose |
| Keycloak | `quarkus-oidc` | Yes (keycloak container) |

No Docker Compose needed for tests. Testcontainers handles PostgreSQL automatically.

### Ephemeral vs Dev DB

| Mode | Command | Database | Data persists |
|------|---------|----------|---------------|
| Ephemeral (default) | `./gradlew test` | Testcontainers (auto) | No |
| Dev DB | `./gradlew test -Pvault.test.profile=dev` | Docker Compose PG | Yes |

- **Ephemeral:** Testcontainers creates a fresh PostgreSQL per test run. Data is discarded. No setup needed.
- **Dev DB:** Tests hit the Docker Compose PostgreSQL (`localhost:5432`). Run `docker compose up -d` first. Data accumulates across runs — useful for verifying persistence, inspecting rows, or debugging migrations.

## ObjectStorage Testing

ObjectStorage tests exercise the `ObjectStorage` interface via CDI injection. The active
implementation is determined by the `vault.object-store.type` build property.

| Profile | Backend | Command |
|---------|---------|---------|
| test (default) | `FilesystemObjectStorage` | `./gradlew test` |
| dev | `S3ObjectStorage` (MinIO) | `docker compose up -d && ./gradlew test -Pvault.test.profile=dev` |

The same `ObjectStorageTest` class runs against both backends — the interface abstraction
ensures consistent behavior. Tests use random tenant UUIDs and clean up after themselves.

**Test coverage:**
- Write + read round-trip (bytes and size match)
- `exists()` returns false for missing, true after write
- `delete()` removes blob, subsequent read throws `BlobNotFoundException`
- `listTenants()` includes tenant after write
- `listContainers()` returns only container BlobRefs (not leaves)
- Tenant isolation (blob in tenant A not visible in tenant B)

## Test Logging

Tests use SLF4J (not `System.out.println()`). See [Logging](Logging.md).
