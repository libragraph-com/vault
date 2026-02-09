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
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Test helper that drives the ingestion pipeline synchronously.
 *
 * <p>Accepts data directly — the raw file is never stored in ObjectStorage.
 * Only the ingestion pipeline stores blobs (leaves + manifests).
 *
 * <p>CDI events are synchronous, so the entire pipeline runs inline
 * and BuildManifestHandler calls taskService.complete() at the end.
 */
@ApplicationScoped
public class TestTaskRunner {

    @Inject
    TaskService taskService;

    @Inject
    Event<IngestFileEvent> ingestFileEvent;

    /**
     * Runs the full ingestion pipeline synchronously with in-memory data.
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
        ingestFileEvent.fire(new IngestFileEvent(
                taskId, tenantId, dbTenantId,
                data, filename, null));

        return taskId;
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
