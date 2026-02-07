# Logging

Logging API and configuration.

## API

### Application Code (core module)

Use Quarkus built-in logging — zero-cost at build time:

```java
import io.quarkus.logging.Log;

public class BlobService {
    public void store(BlobRef ref) {
        Log.infof("Storing blob %s (%d bytes)", ref.hash().toHex(), ref.leafSize());
    }
}
```

`io.quarkus.logging.Log` is a static API that Quarkus resolves at build time.
No logger field needed. No `LoggerFactory.getLogger()`. Works only in
Quarkus-managed classes (CDI beans, REST resources, etc.).

See [Quarkus Logging Guide](https://quarkus.io/guides/logging).

### Shared Libraries and Tests

Code that runs outside Quarkus (shared types, format framework, tests) uses SLF4J:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContentHash {
    private static final Logger log = LoggerFactory.getLogger(ContentHash.class);
}
```

### Rule of Thumb

| Where | API |
|-------|-----|
| `modules/core/`, `modules/api/`, etc. | `io.quarkus.logging.Log` |
| `shared/types/`, `formats/framework/` | SLF4J |
| Tests | SLF4J |

## Backend

Both APIs route to JBoss Logging under the hood (Quarkus default).
No Logback dependency needed — Quarkus handles the backend.

## Configuration

Per-profile log configuration via `application.properties`:

```properties
# application.properties (base)
quarkus.log.level=INFO
quarkus.log.category."com.libragraph.vault".level=DEBUG

# Console format
quarkus.log.console.format=%d{yyyy-MM-dd HH:mm:ss} %-5p [%c{2.}] (%t) %s%e%n

# application-dev.properties
quarkus.log.level=DEBUG
quarkus.log.console.color=true

# application-prod.properties
quarkus.log.level=INFO
quarkus.log.console.json=true  # Structured JSON for log aggregation
```

### Test Runner

```properties
# application-test.properties
quarkus.log.level=WARN
quarkus.log.category."com.libragraph.vault".level=INFO
```

## No System.out

Tests and application code must not use `System.out.println()`.
Use the appropriate logger API for the module.
