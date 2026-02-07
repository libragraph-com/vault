# Tasks

Background task/workflow system for long-running and extensible operations.

> **DECISION:** Port vault-mvp TaskService to CDI. The `Task<I, O>` pattern
> worked well. Replace custom DI/SPI with CDI beans, keep PostgreSQL-backed
> persistence. Quarkus `@Scheduled` for cron triggers. Temporal is premature.

## Task Interface

Tasks declare typed inputs and outputs. The runtime serializes them as JSON
for database persistence.

```java
public interface VaultTask<I, O> {

    String taskType();  // e.g., "ingest.container"

    /** Called when task first starts (OPEN → IN_PROGRESS) */
    TaskOutcome<O> onStart(I input, TaskContext ctx);

    /** Called when blocked task resumes (all subtasks completed) */
    default TaskOutcome<O> onResume(I input, TaskContext ctx) {
        return onStart(input, ctx);
    }

    /** Called when a subtask fails */
    default TaskOutcome<O> onError(I input, TaskContext ctx, TaskError error) {
        return TaskOutcome.fail(error);
    }
}
```

### TaskOutcome

Four possible execution results:

```java
public sealed interface TaskOutcome<O> {
    record Complete<O>(O output) implements TaskOutcome<O> {}
    record Blocked<O>(List<UUID> subtaskIds) implements TaskOutcome<O> {}
    record Background<O>(String reason, Duration timeout) implements TaskOutcome<O> {}
    record Failed<O>(TaskError error) implements TaskOutcome<O> {}
}
```

- **Complete** — Task finished, output stored in database
- **Blocked** — Waiting on subtasks; resumes via `onResume()` when all complete
- **Background** — Running externally (event-driven pipeline); completed via `TaskService.complete()`
- **Failed** — Error occurred; retried automatically if `error.retryable()`

### TaskContext

Provides subtask creation and result access during execution:

```java
public interface TaskContext {
    UUID taskId();
    String taskType();

    /** Create a subtask. Parent must return Blocked with the subtask IDs. */
    <I2, O2> UUID createSubtask(Class<? extends VaultTask<I2, O2>> taskClass, I2 input);
    <I2, O2> UUID createSubtask(Class<? extends VaultTask<I2, O2>> taskClass, I2 input, int priority);

    /** Access subtask results (in onResume) */
    <O2> Optional<O2> getSubtaskResult(UUID subtaskId, Class<O2> outputType);
    Optional<TaskError> getSubtaskError(UUID subtaskId);
    <O2> Stream<O2> getCompletedSubtasks(Class<O2> outputType);
}
```

### TaskError

```java
public record TaskError(
    String message,
    String exceptionType,
    String stackTrace,
    boolean retryable   // IOException, TimeoutException → true; others → false
) {}
```

## State Machine

```
OPEN → IN_PROGRESS → COMPLETE
                   → BLOCKED → OPEN (when subtasks complete)
                   → BACKGROUND → COMPLETE | ERROR (via external API)
                   → ERROR → OPEN (if retryable, retries < max)
                           → DEAD (retries exhausted)
```

- **OPEN** — Available for dispatch
- **IN_PROGRESS** — Claimed by a worker
- **BLOCKED** — Waiting on subtasks (tracked in `task_dependencies` table)
- **BACKGROUND** — Running externally; updated via `TaskService.complete()/fail()`
- **COMPLETE** — Finished successfully, output stored
- **ERROR** — Failed; may be retried
- **DEAD** — Exhausted retries or timed out

## Background Task Pattern

Ingest and reconstruct use a "BG task umbrella" pattern: the task returns
`Background` immediately and the event-driven pipeline does the actual work.
A completion listener bridges the final pipeline event back to the task.

```java
@ApplicationScoped
public class IngestContainerTask implements VaultTask<IngestInput, IngestOutput> {

    @Override
    public String taskType() { return "ingest.container"; }

    @Override
    public TaskOutcome<IngestOutput> onStart(IngestInput input, TaskContext ctx) {
        // 1. Publish IngestFileEvent to start pipeline
        // 2. Return Background — pipeline runs via CDI events
        return TaskOutcome.background("ingesting container", Duration.ofHours(1));
    }
}
```

Completion listener (CDI observer):

```java
@ApplicationScoped
public class IngestCompletionListener {

    @Inject TaskService taskService;

    void onIngested(@Observes ContainerIngestedEvent event) {
        taskService.complete(event.taskId(), new IngestOutput(event.containerRef(), event.leafCount()));
    }
}
```

## Built-in Tasks

| Task Type | Input | Output | Pattern |
|-----------|-------|--------|---------|
| `ingest.container` | `IngestInput(sourcePath)` | `IngestOutput(containerRef, leafCount)` | Background |
| `reconstruct.container` | `ReconstructInput(containerRef, outputPath)` | `ReconstructOutput(outputPath, verified)` | Background |
| `rebuild.sql` | `RebuildInput(tenantId)` | `RebuildOutput(leafCount, manifestCount)` | Direct |
| `enrichment` | `EnrichmentInput(leafRef)` | `EnrichmentOutput(metadata)` | Direct |

See [Ingestion](Ingestion.md), [Reconstruction](Reconstruction.md), [RebuildSQL](RebuildSQL.md).

## TaskService

Public API for task submission and management:

```java
@ApplicationScoped
public class TaskService {

    /** Submit a task for execution */
    <I, O> UUID submit(Class<? extends VaultTask<I, O>> taskClass, I input);
    <I, O> UUID submit(Class<? extends VaultTask<I, O>> taskClass, I input, int priority);

    /** Submit and get a handle for awaiting completion */
    <I, O> TaskHandle<O> submitTracked(Class<? extends VaultTask<I, O>> taskClass, I input, Class<O> outputType);

    /** External completion (for Background tasks) */
    void complete(UUID taskId, Object output);
    void fail(UUID taskId, TaskError error);

    /** Query */
    Optional<TaskRecord> get(UUID taskId);
}
```

### TaskHandle (Observability)

```java
public class TaskHandle<O> {
    UUID taskId();          // Available immediately at submit time
    boolean isDone();
    O await();              // Block until complete (virtual thread safe)
    O await(Duration timeout);
}
```

## Task Execution

Workers claim tasks atomically via `FOR UPDATE SKIP LOCKED`:

```sql
UPDATE tasks SET status = 'IN_PROGRESS', claimed_by = ?, claimed_at = NOW()
WHERE id = (
    SELECT id FROM tasks
    WHERE status = 'OPEN' AND claimed_by IS NULL
    ORDER BY priority DESC, created_at ASC
    FOR UPDATE SKIP LOCKED LIMIT 1
)
```

Execution dispatch:
1. If subtask error → call `onError()`
2. Else if resuming (has completed subtasks) → call `onResume()`
3. Else → call `onStart()`

Worker pool uses PostgreSQL `LISTEN/NOTIFY` for instant dispatch when
tasks become available.

### Stale Task Recovery

- **Leaked IN_PROGRESS** — Worker crashed; detected by `claimed_at` age, returned to OPEN
- **Stale BACKGROUND** — Pipeline crashed; detected by `background_timeout_at`, returned to OPEN

## Task Persistence

### tasks table

```sql
CREATE TABLE tasks (
    id                      UUID PRIMARY KEY,       -- UUIDv7, generated in Java
    type                    VARCHAR(128) NOT NULL,   -- From taskType()
    status                  VARCHAR(32) NOT NULL,    -- OPEN, IN_PROGRESS, BLOCKED, etc.
    priority                SMALLINT DEFAULT 128,    -- 0-255, higher = more urgent

    input                   JSONB NOT NULL,          -- Serialized task input
    output                  JSONB,                   -- Serialized task output

    error_message           TEXT,
    error_type              VARCHAR(256),
    retryable               BOOLEAN,
    retry_count             INTEGER DEFAULT 0,

    claimed_by              VARCHAR(128),            -- Worker node ID
    background_timeout_at   TIMESTAMPTZ,             -- Stale BG detection

    parent_id               UUID REFERENCES tasks(id),
    tenant_id               UUID NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL,
    claimed_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ
);
```

### task_dependencies table

Tracks which tasks block which (for `Blocked` outcome):

```sql
CREATE TABLE task_dependencies (
    blocked_task_id     UUID REFERENCES tasks(id),
    blocking_task_id    UUID REFERENCES tasks(id),
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (blocked_task_id, blocking_task_id)
);
```

When a subtask completes, the system checks if all blocking tasks for the
parent are now COMPLETE. If so, the parent transitions BLOCKED → OPEN.

### Indexes

```sql
CREATE INDEX idx_tasks_claim ON tasks(priority DESC, created_at ASC)
    WHERE status = 'OPEN' AND claimed_by IS NULL;
CREATE INDEX idx_tasks_tenant ON tasks(tenant_id);
CREATE INDEX idx_tasks_parent ON tasks(parent_id);
CREATE INDEX idx_tasks_stale_bg ON tasks(background_timeout_at)
    WHERE status = 'BACKGROUND';
CREATE INDEX idx_tasks_stale_claimed ON tasks(claimed_at)
    WHERE status = 'IN_PROGRESS';
CREATE INDEX idx_task_deps_blocking ON task_dependencies(blocking_task_id);
```

## Task Discovery

Tasks are CDI beans, discovered automatically via Jandex:

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
1. Implement `VaultTask<I, O>` with typed input/output records
2. Annotate with `@ApplicationScoped`
3. CDI discovers it automatically

Examples of extension tasks:
- Virus scanner (ClamAV integration)
- OCR extraction (Tesseract)
- Embedding generation (Ollama/OpenAI)

> **DEPENDENCY:** Task execution depends on [Events](Events.md) for the
> Background task pattern and [Database](Database.md) for persistence.
