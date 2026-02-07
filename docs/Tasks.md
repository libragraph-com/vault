# Tasks

Background task/workflow system for long-running and extensible operations.

The task scheduler is a key component for maintaining system responsiveness
and resource protection. Tasks are never "just dispatched" — they are blocked
until all their dependencies (subtasks AND services) are satisfied, and
services can throttle concurrency to protect shared resources.

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

    /** Called when blocked task resumes (all dependencies satisfied) */
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

Provides subtask creation, service dependency declaration, and result access:

```java
public interface TaskContext {
    UUID taskId();
    String taskType();

    /** Create a subtask. Parent must return Blocked with the subtask IDs. */
    <I2, O2> UUID createSubtask(Class<? extends VaultTask<I2, O2>> taskClass, I2 input);
    <I2, O2> UUID createSubtask(Class<? extends VaultTask<I2, O2>> taskClass, I2 input, int priority);

    /**
     * Declare that this task requires a service to be UP.
     * Task will be blocked until the service reports RUNNING status.
     * If the service goes DOWN while the task is queued, the task stays blocked.
     */
    void requireService(String serviceId);

    /**
     * Declare dependency on a named resource with optional concurrency slot.
     * Used for throttled resources (e.g., GPU with max 2 concurrent jobs).
     */
    void requireResource(String resourceId);

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

## Dependencies

Tasks can be blocked on two kinds of dependencies:

### 1. Subtask Dependencies

A task creates subtasks and returns `Blocked` with their IDs. The parent
resumes (via `onResume()`) when ALL subtasks reach COMPLETE. If any subtask
fails, the parent receives `onError()`.

Tracked in `task_dependencies` (task → task).

### 2. Service Dependencies

A task declares services it needs via `ctx.requireService("ollama")`. The
task will not be dispatched until the service is RUNNING. If the service
goes DOWN while tasks are queued, those tasks remain blocked.

Tracked in `task_resource_deps` (task → resource).

**Why this matters:** Not all services are always available. Ollama may be
offline, a GPU may be overloaded, an external API may be rate-limited. The
scheduler must not dispatch work that will immediately fail.

## Service Resources

Services publish state changes that the task scheduler uses for blocking
and unblocking decisions.

### resources table

```sql
CREATE TABLE resources (
    service_id      VARCHAR(128) PRIMARY KEY,
    status          VARCHAR(32) NOT NULL,   -- STARTING, RUNNING, STOPPING, STOPPED, FAILED
    max_concurrency INTEGER,                -- NULL = unlimited, e.g., 2 for GPU
    current_load    INTEGER DEFAULT 0,      -- Current active tasks using this resource
    started_at      TIMESTAMPTZ,
    stopped_at      TIMESTAMPTZ,
    error_message   TEXT,
    CHECK (status IN ('STARTING', 'RUNNING', 'STOPPING', 'STOPPED', 'FAILED'))
);
```

### Service State Events

Services publish up/down events. The task scheduler observes these and
updates the `resources` table, then unblocks or blocks dependent tasks.

```java
public record ServiceStateEvent(String serviceId, String oldStatus, String newStatus) {}
```

```java
@ApplicationScoped
public class ResourceTracker {

    @Inject TaskService taskService;

    void onServiceState(@Observes ServiceStateEvent event) {
        updateResourceStatus(event.serviceId(), event.newStatus());

        if ("RUNNING".equals(event.newStatus())) {
            // Unblock tasks waiting on this service
            taskService.unblockByResource(event.serviceId());
        }
    }
}
```

### Throttling

Services with limited capacity declare `max_concurrency`. The scheduler
checks `current_load < max_concurrency` before dispatching a task that
requires that resource. This protects shared resources like GPUs, external
APIs, or rate-limited services.

```
Example: Ollama embedding service, max_concurrency = 2

Task A (enrichment) → requires "ollama" → dispatched (load 1/2)
Task B (enrichment) → requires "ollama" → dispatched (load 2/2)
Task C (enrichment) → requires "ollama" → BLOCKED (load 2/2, at capacity)
Task A completes    → load drops to 1/2 → Task C unblocked
```

The claim query incorporates resource availability:

```sql
-- Only claim tasks whose resource dependencies are all satisfied
SELECT t.id FROM tasks t
WHERE t.status = 'OPEN' AND t.claimed_by IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM task_resource_deps trd
      JOIN resources r ON r.service_id = trd.service_id
      WHERE trd.task_id = t.id
        AND (r.status != 'RUNNING'
             OR (r.max_concurrency IS NOT NULL AND r.current_load >= r.max_concurrency))
  )
ORDER BY t.priority DESC, t.created_at ASC
FOR UPDATE SKIP LOCKED LIMIT 1
```

## State Machine

```
OPEN → IN_PROGRESS → COMPLETE
                   → BLOCKED → OPEN (when subtasks complete AND services available)
                   → BACKGROUND → COMPLETE | ERROR (via external API)
                   → ERROR → OPEN (if retryable, retries < max)
                           → DEAD (retries exhausted)
```

- **OPEN** — Available for dispatch (all dependencies satisfied)
- **IN_PROGRESS** — Claimed by a worker
- **BLOCKED** — Waiting on subtasks or service dependencies
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

| Task Type | Input | Output | Pattern | Services |
|-----------|-------|--------|---------|----------|
| `ingest.container` | `IngestInput(sourcePath)` | `IngestOutput(containerRef, leafCount)` | Background | objectstore, database |
| `reconstruct.container` | `ReconstructInput(containerRef, outputPath)` | `ReconstructOutput(outputPath, verified)` | Background | objectstore, database |
| `rebuild.sql` | `RebuildInput(tenantId)` | `RebuildOutput(leafCount, manifestCount)` | Direct | objectstore, database |
| `enrichment` | `EnrichmentInput(leafRef)` | `EnrichmentOutput(metadata)` | Direct | ollama (throttled) |

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

    /** Unblock tasks waiting on a service that just became available */
    void unblockByResource(String serviceId);

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

Workers claim tasks atomically via `FOR UPDATE SKIP LOCKED`, but only
tasks whose dependencies are all satisfied (subtasks complete, services
running, resource capacity available):

```sql
UPDATE tasks SET status = 'IN_PROGRESS', claimed_by = ?, claimed_at = NOW()
WHERE id = (
    SELECT t.id FROM tasks t
    WHERE t.status = 'OPEN' AND t.claimed_by IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM task_resource_deps trd
          JOIN resources r ON r.service_id = trd.service_id
          WHERE trd.task_id = t.id
            AND (r.status != 'RUNNING'
                 OR (r.max_concurrency IS NOT NULL AND r.current_load >= r.max_concurrency))
      )
    ORDER BY t.priority DESC, t.created_at ASC
    FOR UPDATE SKIP LOCKED LIMIT 1
)
```

Execution dispatch:
1. If subtask error → call `onError()`
2. Else if resuming (has completed subtasks) → call `onResume()`
3. Else → call `onStart()`

Worker pool uses PostgreSQL `LISTEN/NOTIFY` for instant dispatch when
tasks become available or services change state.

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

Tracks subtask blocking (task → task):

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

### task_resource_deps table

Tracks service/resource blocking (task → service):

```sql
CREATE TABLE task_resource_deps (
    task_id         UUID REFERENCES tasks(id),
    service_id      VARCHAR(128) REFERENCES resources(service_id),
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (task_id, service_id)
);
```

When a service becomes RUNNING, the system checks for tasks blocked only
on that service and transitions them BLOCKED → OPEN (if all other
dependencies are also satisfied).

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
CREATE INDEX idx_task_resource_deps_service ON task_resource_deps(service_id);
CREATE INDEX idx_resources_status ON resources(status);
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
- Embedding generation (Ollama/OpenAI, throttled via `requireResource("ollama")`)

> **DEPENDENCY:** Task execution depends on [Events](Events.md) for the
> Background task pattern, service state tracking, and [Database](Database.md)
> for persistence.
