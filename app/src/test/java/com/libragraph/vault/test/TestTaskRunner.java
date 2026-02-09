package com.libragraph.vault.test;

import com.libragraph.vault.core.event.IngestFileEvent;
import com.libragraph.vault.core.ingest.IngestContainerTask;
import com.libragraph.vault.core.task.TaskRecord;
import com.libragraph.vault.core.task.TaskService;
import com.libragraph.vault.core.task.TaskStatus;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Test helper that drives the ingestion pipeline via async CDI events.
 *
 * <p>Accepts data directly — the raw file is never stored in ObjectStorage.
 * Only the ingestion pipeline stores blobs (leaves + manifests).
 *
 * <p>CDI events are async — use {@link #awaitTask} to poll for completion.
 */
@ApplicationScoped
public class TestTaskRunner {

    @Inject
    TaskService taskService;

    @Inject
    Event<IngestFileEvent> ingestFileEvent;

    @Inject
    @Named("eventExecutor")
    ExecutorService executor;

    /**
     * Runs the full ingestion pipeline with in-memory data.
     *
     * @param data       the raw file data (e.g. ZIP bytes)
     * @param filename   the original filename (used for format detection, e.g. "archive.zip")
     * @param tenantId   the storage tenant key (String)
     * @param dbTenantId the DB tenant ID (int)
     * @return the task ID
     */
    public int ingest(BinaryData data, String filename, String tenantId, int dbTenantId) {
        BlobRef rootRef = BlobRef.container(data.hash(), data.size());

        // Submit the task to get a real task ID
        int taskId = taskService.submit(IngestContainerTask.class,
                Map.of("storageKey", rootRef.toString()), dbTenantId, 999);

        // Fire the event with the data directly — no ObjectStorage round-trip
        ingestFileEvent.fireAsync(new IngestFileEvent(
                taskId, tenantId, dbTenantId,
                data, filename, null),
                NotificationOptions.ofExecutor(executor));

        return taskId;
    }

    /**
     * Polls until a task reaches a terminal state or the timeout expires.
     */
    public TaskRecord awaitTask(int taskId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            TaskRecord record = taskService.get(taskId).orElseThrow(
                    () -> new IllegalStateException("Task not found: " + taskId));
            if (record.status() == TaskStatus.COMPLETE
                    || record.status() == TaskStatus.ERROR
                    || record.status() == TaskStatus.DEAD) {
                return record;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Task " + taskId + " did not complete within " + timeout);
    }

    /**
     * Checks if a task completed successfully.
     */
    public boolean isComplete(int taskId) {
        return taskService.get(taskId)
                .map(r -> r.status() == TaskStatus.COMPLETE)
                .orElse(false);
    }

    /**
     * Gets the task record for a given task ID.
     */
    public TaskRecord getTask(int taskId) {
        return taskService.get(taskId).orElseThrow(
                () -> new IllegalStateException("Task not found: " + taskId));
    }
}
