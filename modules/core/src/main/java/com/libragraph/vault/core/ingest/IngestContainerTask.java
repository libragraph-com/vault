package com.libragraph.vault.core.ingest;

import com.libragraph.vault.core.event.IngestFileEvent;
import com.libragraph.vault.core.storage.BlobService;
import com.libragraph.vault.core.storage.TenantStorageResolver;
import com.libragraph.vault.core.task.TaskContext;
import com.libragraph.vault.core.task.TaskIO;
import com.libragraph.vault.core.task.TaskOutcome;
import com.libragraph.vault.core.task.VaultTask;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Task that initiates ingestion of a container from ObjectStorage.
 *
 * <p>Input: a map with "storageKey" pointing to the root blob already in storage.
 * The root blob is read, format-detected, and if it's a container, the
 * event-driven ingestion pipeline is triggered.
 */
@ApplicationScoped
@TaskIO(input = Map.class, output = Map.class)
public class IngestContainerTask implements VaultTask {

    private static final Logger log = Logger.getLogger(IngestContainerTask.class);

    @Inject
    BlobService blobService;

    @Inject
    TenantStorageResolver tenantResolver;

    @Inject
    Event<IngestFileEvent> ingestFileEvent;

    @Inject
    @Named("eventExecutor")
    ExecutorService executor;

    @Override
    public String taskType() {
        return "ingest.container";
    }

    @Override
    @SuppressWarnings("unchecked")
    public TaskOutcome onStart(Object input, TaskContext ctx) {
        Map<String, Object> inputMap = (Map<String, Object>) input;
        String storageKey = (String) inputMap.get("storageKey");
        String tenantId = tenantResolver.resolve(ctx.tenantId());

        BlobRef rootRef = BlobRef.parse(storageKey);
        BinaryData rootData = blobService.retrieve(tenantId, rootRef).await().indefinitely();

        log.infof("Ingesting container: key=%s tenant=%s taskId=%d",
                storageKey, tenantId, ctx.taskId());

        // Fire IngestFileEvent to start the pipeline (async for concurrent processing)
        ingestFileEvent.fireAsync(new IngestFileEvent(
                ctx.taskId(), tenantId, ctx.tenantId(),
                rootData, storageKey, null),
                NotificationOptions.ofExecutor(executor));

        return TaskOutcome.background("Ingesting container", Duration.ofHours(1));
    }
}
