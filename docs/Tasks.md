# Tasks

Background task/workflow system for long-running and extensible operations.

## Overview

Tasks are durable, tracked units of work. Unlike events (fire-and-forget),
tasks have persistent state, can be retried, and support subtask hierarchies.

From vault-mvp:
```
Task<I, O>
  ├── onStart(input, context) → TaskOutcome
  ├── onResume(input, context) → TaskOutcome   (when subtasks complete)
  └── onError(input, context, error) → TaskOutcome

States: OPEN → IN_PROGRESS → [BLOCKED] → COMPLETE | ERROR
```

## Quarkus Approach

> **DECISION:** Port vault-mvp TaskService to CDI. The `Task<I, O>` pattern
> worked well. Replace custom DI/SPI with CDI beans, keep PostgreSQL-backed
> persistence. Quarkus `@Scheduled` for cron triggers. Temporal is premature.

## Task Interface (Proposed)

```java
public interface VaultTask<I, O> {

    String taskType();  // e.g., "ingest-container"

    TaskOutcome<O> execute(I input, TaskContext ctx);

    default TaskOutcome<O> onResume(I input, TaskContext ctx) {
        return execute(input, ctx);  // Default: re-execute
    }

    default TaskOutcome<O> onError(I input, TaskContext ctx, Throwable error) {
        return TaskOutcome.failed(error);
    }
}
```

### TaskContext

```java
public interface TaskContext {
    UUID taskId();
    String taskType();

    /** Create a subtask, blocks this task until subtask completes */
    <I2, O2> UUID submitSubtask(Class<? extends VaultTask<I2, O2>> taskClass, I2 input);

    /** Get result of a completed subtask */
    <O2> Optional<O2> getSubtaskResult(UUID subtaskId, Class<O2> type);
}
```

### TaskOutcome

```java
public sealed interface TaskOutcome<O> {
    record Complete<O>(O result) implements TaskOutcome<O> {}
    record Blocked<O>(List<UUID> awaitingSubtasks) implements TaskOutcome<O> {}
    record Failed<O>(Throwable error) implements TaskOutcome<O> {}
    record Retry<O>(Duration delay) implements TaskOutcome<O> {}
}
```

## Built-in Tasks

| Task Type | Input | Output | Triggers |
|-----------|-------|--------|----------|
| `ingest-container` | Path + BlobRef | IngestResult | REST upload, CLI |
| `reconstruct-container` | BlobRef + output Path | ReconstructResult | REST request, CLI |
| `rebuild-sql` | Tenant ID | RebuildResult | Admin CLI |
| `enrichment` | Leaf BlobRef | EnrichmentResult | Post-ingest event |

See [Ingestion](Ingestion.md), [Reconstruction](Reconstruction.md), [RebuildSQL](RebuildSQL.md).

## Task Persistence

Tasks are persisted in PostgreSQL for durability and tracking:

```sql
CREATE TABLE tasks (
    id          UUID PRIMARY KEY,       -- UUIDv7, generated in Java
    task_type   TEXT NOT NULL,
    status      SMALLINT NOT NULL,      -- 0=pending, 1=running, 2=blocked, 3=complete, 4=error
    input       JSONB NOT NULL,
    output      JSONB,
    parent_id   UUID,                   -- Subtask parent
    tenant_id   UUID NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    started_at  TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error       TEXT,
    FOREIGN KEY (parent_id) REFERENCES tasks(id)
);
```

## Task Discovery

Tasks are CDI beans, discovered automatically:

```java
@ApplicationScoped
public class IngestContainerTask implements VaultTask<IngestInput, IngestOutput> {

    @Inject BlobService blobService;
    @Inject Event<IngestFileEvent> ingestEvent;

    @Override
    public String taskType() { return "ingest-container"; }

    @Override
    public TaskOutcome<IngestOutput> execute(IngestInput input, TaskContext ctx) {
        // ...
    }
}
```

Registration via CDI `Instance<VaultTask<?, ?>>`:

```java
@ApplicationScoped
public class TaskRegistry {

    @Inject Instance<VaultTask<?, ?>> allTasks;

    Map<String, VaultTask<?, ?>> tasksByType() {
        return StreamSupport.stream(allTasks.spliterator(), false)
            .collect(Collectors.toMap(VaultTask::taskType, t -> t));
    }
}
```

## Cron Triggers

Quarkus Scheduler for periodic tasks:

```java
@ApplicationScoped
public class ScheduledTasks {

    @Inject TaskService taskService;

    @Scheduled(cron = "0 0 2 * * ?")  // 2 AM daily
    void nightlyEnrichment() {
        taskService.submit(EnrichmentTask.class, new EnrichmentInput("pending"));
    }
}
```

See [Quarkus Scheduler](https://quarkus.io/guides/scheduler).

## Extensibility

New tasks are added by:
1. Implement `VaultTask<I, O>` interface
2. Annotate with `@ApplicationScoped`
3. That's it — CDI discovers it automatically

Examples of extension tasks:
- Virus scanner (ClamAV integration)
- MP3 cover art lookup (MusicBrainz API)
- OCR extraction (Tesseract)
- Embedding generation (Ollama/OpenAI)

> **DEPENDENCY:** Task execution depends on [Events](Events.md) for pipeline
> coordination and [Database](Database.md) for persistence.
