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

> **OPEN QUESTION:** The ingestion pipeline needs controlled concurrency
> (e.g., max 4 concurrent container extractions). CDI async events don't
> have built-in backpressure. Options:
> (a) Use `@ObservesAsync` with a custom `Executor` (CDI supports this),
> (b) Use Vert.x Event Bus with worker verticle pool,
> (c) Combine CDI events with a `Semaphore` or `ExecutorService`.
>
> vault-mvp used a fixed thread pool (CPU cores). Option (a) is closest.

## Event Types

Carry forward from vault-mvp, adapted as Java records:

```java
// Ingestion pipeline
public record IngestFileEvent(UUID taskId, BlobRef containerRef, Path path, FanInContext fanIn) {}
public record ChildDiscoveredEvent(ContainerChild child, FanInContext fanIn) {}
public record AllChildrenCompleteEvent(FanInContext fanIn, List<ChildResult> results) {}
public record ObjectCreatedEvent(BlobRef ref, UUID leafId) {}
public record DedupHitEvent(BlobRef ref) {}
public record ManifestBuiltEvent(BlobRef containerRef, BlobRef manifestRef) {}
public record ContainerCompleteEvent(BlobRef containerRef, int childCount) {}

// System
public record ServiceStateEvent(String serviceName, String oldState, String newState) {}
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
    private final FanInContext parent;  // For nested containers
    private final ConcurrentLinkedQueue<ChildResult> results;

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

> **OPEN QUESTION:** CDI events vs Vert.x Event Bus? CDI events are simpler
> and type-safe. Vert.x Event Bus supports clustering and request-reply.
> For single-process Vault, CDI events are likely sufficient. Vert.x Event
> Bus would be needed if we split into microservices (unlikely near-term).
>
> Recommendation: CDI events for the pipeline, Vert.x Event Bus only if
> we need clustering later.

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

    void onDedup(@Observes DedupHitEvent event) {
        registry.counter("vault.dedup.hits").increment();
    }
}
```

See [Quarkus Micrometer](https://quarkus.io/guides/micrometer).
