# Testing

Test patterns and infrastructure.

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

Full CDI container. Quarkus Dev Services auto-provisions PostgreSQL, MinIO, etc.:

```java
@QuarkusTest
class BlobServiceTest {

    @Inject BlobService blobService;

    @Test
    void storeAndRetrieve() {
        BlobRef ref = BlobRef.leaf(hash, 1024);
        blobService.store(tenantId, ref, testContent(), "application/octet-stream");
        assertThat(blobService.exists(ref)).isTrue();
    }
}
```

No manual database setup — Quarkus starts a testcontainer automatically.

### REST Tests

```java
@QuarkusTest
class IngestEndpointTest {

    @Test
    void testHealthReady() {
        given()
            .when().get("/q/health/ready")
            .then()
            .statusCode(200)
            .body("status", is("UP"));
    }

    @Test
    void testIngestUpload() {
        given()
            .multiPart("file", testZipFile())
            .when().post("/api/ingest")
            .then()
            .statusCode(202);
    }
}
```

## Test Configuration

Tests use the `test` profile automatically. Override in `application-test.properties`:

```properties
# Quarkus Dev Services handles DB and object store automatically
# Override only what's needed:

vault.object-store.type=filesystem
vault.object-store.base-path=${java.io.tmpdir}/vault-test

# Logging
quarkus.log.level=WARN
quarkus.log.category."com.libragraph.vault".level=INFO
```

## Test Parameters

> **DECISION:** Use Quarkus config properties with sensible defaults. Dev
> Services recreates containers per test run, so `preserveData` is only
> for debugging. Keep it simple.

```properties
# Test tuning (optional)
vault.test.tenant-id=                           # Empty = auto-create
vault.test.preserve-data=false                  # Keep test data after run
vault.test.reset-tenant=false                   # Wipe tenant on init
```

## Dev Services

Quarkus auto-provisions external dependencies for dev and test:

| Service | Extension | Auto-provisioned |
|---------|-----------|-----------------|
| PostgreSQL | `quarkus-jdbc-postgresql` | Yes (testcontainer) |
| MinIO/S3 | `quarkus-amazon-s3` | Yes (localstack) |
| Keycloak | `quarkus-oidc` | Yes (keycloak container) |

No Docker Compose needed for tests. See [Quarkus Dev Services](https://quarkus.io/guides/dev-services).

## Test Logging

Tests use SLF4J (not `System.out.println()`). See [Logging](Logging.md).
