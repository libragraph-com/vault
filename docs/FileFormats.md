# File Formats

Pluggable format detection and handling system.

## Architecture

The format system has two layers:

1. **Detection** — Identify what a file is (magic bytes, extension, MIME type)
2. **Handling** — Process it (extract children, extract text, reconstruct)

### FormatHandlerFactory (Plugin Entry Point)

```java
public interface FormatHandlerFactory {
    /** What this plugin can detect */
    DetectionCriteria getDetectionCriteria();

    /** Create a handler for a matched file */
    Handler createInstance(BinaryData buffer, FileContext context);
}
```

### Handler (Per-File Processor)

```java
public interface Handler extends AutoCloseable {
    boolean hasChildren();           // Is this a container?
    boolean isCompressible();        // Should we zstd-compress the stored blob?
    Stream<ContainerChild> extractChildren();  // Yield children (containers only)
    void reconstruct(List<ContainerChild> children, OutputStream output);
    Map<String, Object> extractMetadata();
    String extractText();            // For full-text indexing
}
```

### DetectionCriteria

```java
public record DetectionCriteria(
    Set<String> mimeTypes,        // e.g., {"application/zip"}
    Set<String> extensions,       // e.g., {".zip", ".jar", ".docx"}
    byte[] magicBytes,            // e.g., {0x50, 0x4B, 0x03, 0x04}
    int magicOffset,              // Usually 0
    int priority                  // Higher priority wins on conflict
) {}
```

## FormatRegistry

Central registry that matches files to handlers:

```java
@ApplicationScoped
public class FormatRegistry {

    @Inject Instance<FormatHandlerFactory> factories;

    public Optional<Handler> findHandler(BinaryData buffer, FileContext context) {
        return StreamSupport.stream(factories.spliterator(), false)
            .filter(f -> f.getDetectionCriteria().matches(buffer, context))
            .max(Comparator.comparingInt(f -> f.getDetectionCriteria().priority()))
            .map(f -> f.createInstance(buffer, context));
    }
}
```

## Plugin Discovery

### In Quarkus (CDI)

Plugins are CDI beans, discovered automatically via Jandex:

```java
@ApplicationScoped
public class ZipHandlerFactory implements FormatHandlerFactory {

    @Override
    public DetectionCriteria getDetectionCriteria() {
        return new DetectionCriteria(
            Set.of("application/zip"),
            Set.of(".zip", ".jar", ".war", ".ear"),
            new byte[]{0x50, 0x4B, 0x03, 0x04},
            0, 100  // High priority
        );
    }

    @Override
    public Handler createInstance(BinaryData buffer, FileContext context) {
        return new ZipHandler(buffer, context);
    }
}
```

> **DECISION:** CDI only. External plugins must include a Jandex index or
> be registered via `quarkus.index-dependency` in config. No SPI fallback.

## Built-in Handlers

| Format | Detection | Container? | Reconstructable? |
|--------|-----------|------------|-------------------|
| ZIP | Magic bytes `PK\x03\x04` | Yes | Yes (Tier 1) |
| TAR | Magic bytes at offset 257 | Yes | Yes (Tier 1) |
| GZIP | Magic bytes `\x1f\x8b` | Yes (wraps TAR) | Yes (Tier 1) |
| 7Z | Magic bytes `7z\xbc\xaf` | Yes | No (Tier 2 — store blob) |
| RAR | Magic bytes `Rar!` | Yes | No (Tier 2 — store blob) |
| PST | Magic bytes | Yes (email archive) | No (Tier 3 — contents only) |
| ISO | Magic bytes at offset 32769 | Yes | Future |

## Supporting Types

### BinaryData

Abstraction over content bytes — can be RAM, mmap, or disk-backed:

```java
public interface BinaryData {
    InputStream openStream();
    long size();
    byte[] readHeader(int bytes);
    void release();
}
```

### FileContext

```java
public record FileContext(
    String filename,
    Optional<Path> sourcePath,
    Optional<String> detectedMimeType
) {}
```

### ContainerChild

```java
public record ContainerChild(
    String path,
    BinaryData buffer,
    Map<String, Object> metadata
) {}
```

## How to Add a New Format

1. Create a class implementing `FormatHandlerFactory`
2. Define `DetectionCriteria` (magic bytes, extensions, MIME types)
3. Implement `Handler` for the format
4. Annotate with `@ApplicationScoped`
5. That's it — CDI discovers it automatically

For external plugins (separate JAR):
- Add Jandex index to the JAR, or
- Register via `quarkus.index-dependency` in config

## Apache Tika Integration

For formats where we don't have a custom handler, delegate to Tika:

```java
@ApplicationScoped
public class TikaHandlerFactory implements FormatHandlerFactory {

    @Override
    public DetectionCriteria getDetectionCriteria() {
        return DetectionCriteria.catchAll(priority = 0);  // Lowest priority fallback
    }

    @Override
    public Handler createInstance(BinaryData buffer, FileContext context) {
        return new TikaHandler(buffer, context);
    }
}
```

Tika provides metadata extraction and text extraction for 1000+ formats.
Custom handlers take priority; Tika handles everything else.

> **DEPENDENCY:** Format handlers are used by [Ingestion](Ingestion.md)
> (child extraction) and [Reconstruction](Reconstruction.md) (container rebuild).
