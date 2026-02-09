# Events

Event-driven processing patterns using Quarkus.

## vault-mvp → Quarkus Migration

vault-mvp built a custom `EventBus` and `EventService` with:
- Handler dispatch (exactly-once per event type)
- Broadcast subscribe (many observers via Reactor Flux)
- `@Consumes` and `@Emits` annotations
- Thread pool dispatch

Quarkus provides two event mechanisms that replace this:

| Mechanism | Use Case | Quarkus Feature |
|-----------|----------|-----------------|
| CDI Events | In-process, synchronous/async | `@Observes`, `Event<T>.fire()` |
| Vert.x Event Bus | In-process or clustered, async | `@ConsumeEvent`, `EventBus` |

## CDI Events (Recommended for Pipeline)

CDI events are the idiomatic Quarkus approach for in-process event-driven
processing. They're compile-time verified, type-safe, and well-integrated
with CDI lifecycle.

### Firing Events

```java
@ApplicationScoped
public class ProcessContainerHandler {

    @Inject Event<ChildDiscoveredEvent> childEvent;
    @Inject Event<ContainerCompleteEvent> completeEvent;

    public void handle(IngestFileEvent event) {
        // Process container, extract children...
        for (ContainerChild child : plugin.extractChildren()) {
            childEvent.fire(new ChildDiscoveredEvent(child, event.fanIn()));
        }
        completeEvent.fire(new ContainerCompleteEvent(event.containerRef()));
    }
}
```

### Observing Events

```java
@ApplicationScoped
public class ProcessChildHandler {

    // Synchronous observation (same thread as fire())
    void onChild(@Observes ChildDiscoveredEvent event) {
        // Process child...
    }

    // Async observation (different thread, fire-and-forget)
    void onChildAsync(@ObservesAsync ChildDiscoveredEvent event) {
        // Process child on worker thread...
    }
}
```

### Async Events (Fire and Forget)

```java
@Inject Event<ChildDiscoveredEvent> childEvent;

// Synchronous - blocks until all observers complete
childEvent.fire(event);

// Async - returns CompletionStage, observers run on worker threads
childEvent.fireAsync(event);
```

> **DECISION:** CDI async events with a bounded `ExecutorService` passed to
> `fireAsync()`. CDI's `NotificationOptions.ofExecutor(executor)` controls
> the thread pool. This gives concurrency control without leaving CDI.
> vault-mvp used a fixed thread pool (CPU cores) — same pattern.

## Event Types

Carry forward from vault-mvp, adapted as Java records:

```java
// Ingestion pipeline
public record IngestFileEvent(int taskId, String tenantId, int dbTenantId, BinaryData buffer, String filename, FanInContext fanIn) {}
public record ChildDiscoveredEvent(ContainerChild child, FanInContext fanIn, String tenantId, int dbTenantId, int taskId) {}
public record AllChildrenCompleteEvent(FanInContext fanIn, List<ChildResult> results) {}
public record ObjectCreatedEvent(BlobRef ref, long blobId) {}
public record ManifestBuiltEvent(BlobRef containerRef) {}

// System
public record ServiceStateChangedEvent(String serviceId, ManagedService.State oldState, ManagedService.State newState, Instant timestamp) {}
```

> **DEPENDENCY:** Events reference [BlobRef](Architecture.md), FanInContext
> (defined in core), and types from [FileFormats](FileFormats.md).

## Fan-In Synchronization

The key innovation from vault-mvp: tracking when all children of a container
have been processed, without polling.

```java
public class FanInContext {
    private final UUID contextId;
    private final AtomicInteger remaining;
    private final FanInContext parent;
    private final ConcurrentLinkedQueue<ChildResult> results;
    private final BlobRef containerRef;
    private final String containerPath;
    private final String tenantId;
    private final int dbTenantId;
    private final int taskId;

    public FanInContext(int expectedChildren, FanInContext parent,
                        BlobRef containerRef, String containerPath,
                        String tenantId, int dbTenantId, int taskId) { ... }

    /** Returns true if this was the last child */
    public boolean decrementAndCheck() {
        return remaining.decrementAndGet() == 0;
    }
}
```

When the last child completes, the handler fires `AllChildrenCompleteEvent`,
which triggers manifest building. If the completed container itself has a
parent, the parent's fan-in is decremented too (cascade up).

## Vert.x Event Bus (Alternative)

For cases where CDI events aren't sufficient (clustered deployment,
request-reply patterns):

```java
@ApplicationScoped
public class IngestConsumer {

    @ConsumeEvent("ingest.file")
    public void onIngest(IngestFileEvent event) {
        // Runs on Vert.x worker thread
    }
}

// Firing
@Inject EventBus bus;
bus.send("ingest.file", event);
```

See [Quarkus Event Bus](https://quarkus.io/guides/reactive-event-bus).

> **DECISION:** CDI events for the pipeline. Type-safe, compile-time verified,
> sufficient for single-process. Vert.x Event Bus only if we need clustering
> later (unlikely near-term).

## Handler Contract (@Consumes/@Emits)

Event handlers declare their input/output contract via annotations. This enables
compile-time routing table generation and chain validation (every `@Emits` type
has a `@Consumes` handler).

### Functional Handlers

Handlers are plain classes with annotated methods, not workflows or tasks:

```java
@ApplicationScoped
public class ProcessContainerHandler {

    @Inject BlobService blobService;
    @Inject FormatRegistry formatRegistry;
    @Inject Event<ChildDiscoveredEvent> childEvent;

    @Consumes(IngestFileEvent.class)
    @Emits({ChildDiscoveredEvent.class, ContainerCompleteEvent.class})
    public void handle(IngestFileEvent event) {
        // Extract children from container
        List<ContainerChild> children = plugin.extractChildren(buffer);

        // Create fan-in context
        FanInContext fanIn = new FanInContext(UUID.randomUUID(), children.size(), event.fanIn());

        // Fire event per child
        for (ContainerChild child : children) {
            childEvent.fire(new ChildDiscoveredEvent(child, fanIn));
        }
    }
}
```

**Key points:**
- `@Consumes(EventType.class)` — declares what event triggers this handler
- `@Emits({...})` — declares what events this handler can fire
- Synchronous methods — `.block()` on reactive services at the boundary
- One handler per event type (exactly-once dispatch)
- CDI `@Observes` still works for observation (metrics, logging)

### Chain Validation

At startup, validate that every `@Emits` type has a registered `@Consumes` handler:

```java
@ApplicationScoped
public class EventPipeline {

    @Inject Instance<Object> handlers;  // All @ApplicationScoped beans

    @PostConstruct
    void init() {
        Set<Class<?>> consumed = new HashSet<>();
        Set<Class<?>> emitted = new HashSet<>();

        for (Object handler : handlers) {
            for (Method method : handler.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Consumes.class)) {
                    consumed.add(method.getAnnotation(Consumes.class).value());
                }
                if (method.isAnnotationPresent(Emits.class)) {
                    emitted.addAll(Arrays.asList(method.getAnnotation(Emits.class).value()));
                }
            }
        }

        Set<Class<?>> orphaned = new HashSet<>(emitted);
        orphaned.removeAll(consumed);

        if (!orphaned.isEmpty()) {
            throw new IllegalStateException("Events emitted but not consumed: " + orphaned);
        }
    }
}
```

This catches broken event chains at startup, not at runtime.

## Observability

Both mechanisms support observation for metrics/logging:

```java
@ApplicationScoped
public class IngestMetrics {

    @Inject MeterRegistry registry;

    void onLeafStored(@Observes ObjectCreatedEvent event) {
        registry.counter("vault.leaves.stored").increment();
        registry.summary("vault.leaves.size").record(event.ref().leafSize());
    }

    void onCreated(@Observes ObjectCreatedEvent event) {
        registry.counter("vault.objects.created").increment();
    }
}
```

See [Quarkus Micrometer](https://quarkus.io/guides/micrometer).
