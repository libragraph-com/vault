# Tasks

Background task/workflow system for long-running and extensible operations.

The task scheduler is a key component for maintaining system responsiveness
and resource protection. Tasks are never "just dispatched" — they are blocked
until all their dependencies (subtasks AND resources) are satisfied, and
resources can throttle concurrency to protect shared services.

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
    record Blocked<O>(List<Integer> subtaskIds) implements TaskOutcome<O> {}
    record Background<O>(String reason, Duration timeout) implements TaskOutcome<O> {}
    record Failed<O>(TaskError error) implements TaskOutcome<O> {}
}
```

- **Complete** — Task finished, output stored in `task.output` (JSONB)
- **Blocked** — Waiting on subtasks; resumes via `onResume()` when all complete
- **Background** — Running externally (event-driven pipeline); completed via `TaskService.complete()`
- **Failed** — Error occurred; retried automatically if `error.retryable()`

### TaskContext

Provides subtask creation, resource dependency declaration, and result access:

```java
public interface TaskContext {
    int taskId();
    String taskType();

    /** Create a subtask. Parent must return Blocked with the subtask IDs. */
    <I2, O2> int createSubtask(Class<? extends VaultTask<I2, O2>> taskClass, I2 input);
    <I2, O2> int createSubtask(Class<? extends VaultTask<I2, O2>> taskClass, I2 input, int priority);

    /**
     * Declare dependency on a named resource with optional concurrency limit.
     * Used for throttled resources (e.g., Ollama with max 2 concurrent jobs).
     * Task will not be dispatched if the resource is at capacity.
     */
    void requireResource(String resourceName);

    /** Access subtask results (in onResume) */
    <O2> Optional<O2> getSubtaskResult(int subtaskId, Class<O2> outputType);
    Optional<TaskError> getSubtaskError(int subtaskId);
    <O2> Stream<O2> getCompletedSubtasks(Class<O2> outputType);
}
```

### TaskError

Serialized into `task.output` (JSONB) when the task fails:

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

Tracked in `task_task_dep` (task → task).

### 2. Resource Dependencies

A task declares resources it needs via `ctx.requireResource("ollama")`. The
task will not be dispatched if the resource is at capacity. Resource
capacity is checked at claim time — the claiming node passes its set of
available services, and the query verifies capacity.

Tracked in `task_resource_dep` (task → resource).

**Why this matters:** Not all services are always available. Ollama may be
offline, a GPU may be overloaded, an external API may be rate-limited. The
scheduler must not dispatch work that will immediately fail.

## Resources

Resources are static configuration that define capacity limits for shared
services. No runtime state is stored in the database — service health is
determined at claim time by each node, and current load is derived from
the count of IN_PROGRESS tasks.

> **DECISION:** Resource health is not stored in the database. Each claiming
> node knows which services it can reach (maintained in-memory via health
> checks / CDI `ServiceStateEvent`). The node passes its available services
> as a parameter to the claim query. This avoids per-node health rows,
> stale status conflicts, and cleanup logic.

> **DECISION:** `current_load` is derived, not stored. `COUNT(IN_PROGRESS
> tasks with resource dep)` is self-healing — when stale recovery reclaims
> a task, the count naturally drops. No mutable counter to corrupt.

### task_resource table

```sql
CREATE TABLE task_resource (
    id              SMALLINT PRIMARY KEY,
    name            CITEXT NOT NULL UNIQUE,
    max_concurrency INTEGER NULL        -- NULL = unlimited
);
```

### Throttling

Resources with limited capacity declare `max_concurrency`. The scheduler
derives current load from IN_PROGRESS task count and checks capacity
before dispatching.

```
Example: Ollama embedding service, max_concurrency = 2

Task A (enrichment) → requires "ollama" → dispatched (load 1/2)
Task B (enrichment) → requires "ollama" → dispatched (load 2/2)
Task C (enrichment) → requires "ollama" → BLOCKED (load 2/2, at capacity)
Task A completes    → load drops to 1/2 → Task C unblocked
```

## State Machine

```
OPEN → IN_PROGRESS → COMPLETE
                   → BLOCKED → OPEN (when subtasks complete AND capacity available)
                   → BACKGROUND → COMPLETE | ERROR (via external API)
                   → ERROR → OPEN (if retryable, retries < max)
                           → DEAD (retries exhausted)
                   → CANCELLED
```

- **OPEN** — Available for dispatch (all dependencies satisfied)
- **IN_PROGRESS** — Claimed by a worker node
- **BLOCKED** — Waiting on subtasks or resource capacity
- **BACKGROUND** — Running externally; updated via `TaskService.complete()/fail()`
- **COMPLETE** — Finished successfully, output stored
- **ERROR** — Failed; may be retried
- **CANCELLED** — User-initiated cancellation
- **DEAD** — Exhausted retries or timed out

Status values are stored in a `task_status` lookup table (smallint FK),
not as PostgreSQL ENUMs or unbounded strings.

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

| Task Type | Input | Output | Pattern | Resources |
|-----------|-------|--------|---------|-----------|
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
    <I, O> int submit(Class<? extends VaultTask<I, O>> taskClass, I input);
    <I, O> int submit(Class<? extends VaultTask<I, O>> taskClass, I input, int priority);

    /** Submit and get a handle for awaiting completion */
    <I, O> TaskHandle<O> submitTracked(Class<? extends VaultTask<I, O>> taskClass, I input, Class<O> outputType);

    /** External completion (for Background tasks) */
    void complete(int taskId, Object output);
    void fail(int taskId, TaskError error);

    /** Query */
    Optional<TaskRecord> get(int taskId);
}
```

### TaskHandle (Observability)

```java
public class TaskHandle<O> {
    int taskId();               // Available immediately at submit time
    boolean isDone();
    O await();                  // Block until complete (virtual thread safe)
    O await(Duration timeout);
}
```

TaskHandle wraps a `CompletableFuture` held in a node-local map. In a
cluster, the task may complete on a different node — see [Clustering](#clustering).

## Task Execution

Workers claim tasks atomically via `FOR UPDATE SKIP LOCKED`. The claiming
node passes its set of available services as a query parameter, and the
query verifies both service availability and resource capacity:

```sql
WITH available AS (
    SELECT unnest(?::text[]) AS service_id
),
under_capacity AS (
    SELECT r.id FROM task_resource r
    LEFT JOIN (
        SELECT trd.depends_on, COUNT(*) AS active
        FROM task_resource_dep trd
        JOIN task t ON t.id = trd.task_id
        WHERE t.status = 1  -- IN_PROGRESS
        GROUP BY trd.depends_on
    ) load ON load.depends_on = r.id
    WHERE r.max_concurrency IS NULL
       OR COALESCE(load.active, 0) < r.max_concurrency
)
UPDATE task SET status = 1, executor = ?, claimed_at = NOW()
WHERE id = (
    SELECT t.id FROM task t
    WHERE t.status = 0 AND t.executor IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM task_resource_dep trd
          JOIN task_resource r ON r.id = trd.depends_on
          WHERE trd.task_id = t.id
            AND (r.name NOT IN (SELECT service_id FROM available)
                 OR r.id NOT IN (SELECT id FROM under_capacity))
      )
    ORDER BY t.priority DESC, t.created_at ASC
    FOR UPDATE SKIP LOCKED LIMIT 1
)
RETURNING *
```

Execution dispatch:
1. If subtask error → call `onError()`
2. Else if resuming (has completed subtasks) → call `onResume()`
3. Else → call `onStart()`

Worker pool uses PostgreSQL `LISTEN/NOTIFY` for instant dispatch when
tasks become available.

### Stale Task Recovery

Stale detection is timeout-based — nodes do not detect whether other nodes
are alive. A periodic sweep (any node, `@Scheduled`) reclaims stale tasks:

- **Leaked IN_PROGRESS** — `claimed_at` older than lease duration → returned to OPEN
- **Stale BACKGROUND** — `expires_at` has passed → returned to OPEN

Multiple nodes running the sweep concurrently is safe — `FOR UPDATE SKIP
LOCKED` prevents double-reclaim. The `executor` column tells you which
node's task was reclaimed (for log correlation).

## Clustering

PostgreSQL is the coordination layer. Nodes do not communicate directly.

### Node Identity

Each node self-registers in the `node` table on startup using its hostname
(pod name in K8s, hostname in Docker Compose). The node ID is an opaque
label used for debugging and stale detection — not for routing, not for
scheduling, not for inter-node communication.

```sql
CREATE TABLE node (
    id          SERIAL PRIMARY KEY,
    hostname    CITEXT NOT NULL UNIQUE,
    last_seen   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Configuration: `vault.cluster.node-id` defaults to `${HOSTNAME}`.
In K8s, the Downward API injects the pod name as `HOSTNAME` automatically.

### Cross-Node Task Completion (LISTEN/NOTIFY)

TaskHandle is node-local (in-memory `CompletableFuture`). If Node A submits
a tracked task and Node B completes it, Node A needs to be notified.

PostgreSQL `LISTEN/NOTIFY` solves this:

1. Task completes on any node → `NOTIFY task_completed, '{taskId}'`
   inside the same transaction as the status UPDATE (transactional —
   only fires on commit).
2. Every node listens on `task_completed` via a dedicated PG connection.
3. On receive, each node checks its local TaskHandle map. If present,
   loads result from DB and completes the future. If absent, ignores.

The `task_available` channel (worker wake-up) works the same way and is
already cross-node by nature of PG NOTIFY.

### Pipeline Locality

CDI events (`ChildDiscoveredEvent`, `AllChildrenCompleteEvent`, etc.) are
in-process only — they do not cross node boundaries. The ingestion pipeline
for a given container runs entirely on one node via synchronous `fire()`.
Tasks are the cross-node boundary, not CDI events.

`FanInContext` (in-memory `AtomicInteger`) is node-local by design. If
child processing ever needs to be scattered across nodes, the fan-in
counter would move to a SQL row (`UPDATE ... SET remaining = remaining - 1
RETURNING remaining`). Not needed until then.

### What Nodes Don't Do

- **Detect other nodes' liveness** — timeout-based stale recovery handles
  crashed nodes. No heartbeat protocol, no consensus, no ZooKeeper.
- **Route requests to specific nodes** — any node can serve any query
  (read from DB). No affinity needed.
- **Communicate directly** — PG LISTEN/NOTIFY is the broadcast layer.
  No RPC, no mesh, no Vert.x clustering.

## Task Persistence

See [RevisedSchema.sql](research/RevisedSchema.sql) for the full loadable
schema. Task-related tables:

### task table

```sql
CREATE TABLE task (
    id              SERIAL PRIMARY KEY,
    tenant_id       INTEGER NOT NULL REFERENCES tenant(id),
    parent_id       INTEGER NULL REFERENCES task(id),

    type            CITEXT NOT NULL,
    status          SMALLINT NOT NULL REFERENCES task_status(id),
    priority        SMALLINT DEFAULT 128,

    input           JSONB NULL,
    output          JSONB NULL,         -- holds error or result

    retryable       BOOLEAN,
    retry_count     INTEGER DEFAULT 0,

    executor        INTEGER NULL REFERENCES node(id),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ
);
```

### task_task_dep table

Tracks subtask blocking (task → task):

```sql
CREATE TABLE task_task_dep (
    task_id         INTEGER NOT NULL REFERENCES task(id),
    depends_on      INTEGER NOT NULL REFERENCES task(id),
    PRIMARY KEY (task_id, depends_on)
);
CREATE INDEX idx_task_dep_depends_on ON task_task_dep(depends_on);
```

When a subtask completes, the system checks if all blocking tasks for the
parent are now COMPLETE. If so, the parent transitions BLOCKED → OPEN.

### task_resource_dep table

Tracks resource dependencies (task → resource):

```sql
CREATE TABLE task_resource_dep (
    task_id         INTEGER NOT NULL REFERENCES task(id),
    depends_on      SMALLINT NOT NULL REFERENCES task_resource(id),
    PRIMARY KEY (task_id, depends_on)
);
CREATE INDEX idx_task_resource_dep ON task_resource_dep(depends_on);
```

### Indexes

```sql
CREATE INDEX idx_task_claim ON task(priority DESC, created_at ASC)
    WHERE status = 0 AND executor IS NULL;
CREATE INDEX idx_task_tenant ON task(tenant_id);
CREATE INDEX idx_task_parent ON task(parent_id);
CREATE INDEX idx_task_stale_bg ON task(expires_at)
    WHERE status = 3;   -- BACKGROUND
CREATE INDEX idx_task_stale_claimed ON task(claimed_at)
    WHERE status = 1;   -- IN_PROGRESS
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

## Workflow Triggers

Tasks are often created automatically in response to events. A **workflow**
declares triggers with match expressions — the system evaluates the predicate
against the event and creates the task only when it matches.

### @Trigger Annotation

```java
@WorkflowDef("content-analysis")
public class ContentAnalysisWorkflow {

    @Trigger(on = ObjectCreatedEvent.class, match = "mimeType = 'audio/mpeg'")
    public TaskSpec<?> onMp3Created(ObjectCreatedEvent event) {
        return TaskSpec.of(ExtractId3TagsTask.class,
            new ExtractId3Input(event.hash()), 150);
    }

    @Trigger(on = ObjectCreatedEvent.class, match = "mimeType startsWith 'image/'")
    public TaskSpec<?> onImageCreated(ObjectCreatedEvent event) {
        return TaskSpec.of(ExtractExifTask.class,
            new ExtractExifInput(event.hash()), 100);
    }
}
```

- `on` — The event type that triggers evaluation
- `match` — Expression evaluated against the event fields
- Return `TaskSpec` describing which task to create, with what input and priority

### Expression Engine

Match expressions support property access, comparison, and string operations:

```
mimeType = 'audio/mpeg'                    # Equality
size > 1048576                             # Comparison
mimeType startsWith 'image/'              # String prefix
mimeType contains 'zip' AND size > 1024   # Logical AND
filename endsWith '.pdf' OR mimeType = 'application/pdf'  # Logical OR
```

The expression engine is intentionally simple — no scripting language, no
Turing completeness. It evaluates event record fields via reflection.

### @JoinTrigger (Fan-In)

For workflows that need multiple events before creating a task:

```java
@JoinTrigger(
    taskTypes = {"extract.id3", "extract.exif"},
    joinKey = "hash",
    workflow = "post-enrichment"
)
public TaskSpec<?> onAllEnrichmentsComplete(List<TaskCompletedEvent> events) {
    return TaskSpec.of(BuildSearchIndexTask.class, new BuildSearchInput(hash));
}
```

Join state is tracked in a `task_joins` table:

```sql
CREATE TABLE task_joins (
    join_key        VARCHAR(256) NOT NULL,
    workflow_id     VARCHAR(128) NOT NULL,
    task_type       VARCHAR(128) NOT NULL,
    task_id         INTEGER REFERENCES task(id),
    completed_at    TIMESTAMPTZ,
    PRIMARY KEY (join_key, workflow_id, task_type)
);
```

### TriggerRegistry

CDI bean that discovers `@WorkflowDef` classes, subscribes triggers to the
event system, evaluates match expressions, and dispatches `TaskSpec` to
TaskService when predicates are satisfied.

```java
@ApplicationScoped
public class TriggerRegistry {

    @Inject Instance<Object> workflowDefs;  // All @WorkflowDef beans
    @Inject TaskService taskService;
    @Inject ExpressionEngine expressionEngine;

    @PostConstruct
    void init() {
        // Scan @WorkflowDef beans for @Trigger methods
        // Subscribe to event types, evaluate match expressions on fire
    }
}
```

## Extensibility

New tasks are added by:
1. Implement `VaultTask<I, O>` with typed input/output records
2. Annotate with `@ApplicationScoped`
3. CDI discovers it automatically

New workflow triggers are added by:
1. Create a `@WorkflowDef` class
2. Add `@Trigger` methods with match expressions
3. Return `TaskSpec` describing the task to create
4. CDI discovers it automatically

Examples of extension tasks:
- ID3 tag extraction (`mimeType = 'audio/mpeg'`)
- EXIF extraction (`mimeType startsWith 'image/'`)
- OCR extraction (`mimeType = 'application/pdf'`)
- Virus scanning (all files, `size > 0`)
- Embedding generation (`mimeType startsWith 'text/'`, throttled via `requireResource("ollama")`)

> **DEPENDENCY:** Task execution depends on [Events](Events.md) for the
> Background task pattern, workflow triggers, service state tracking, and
> [Database](Database.md) for persistence.
