package com.libragraph.vault.core.ingest;

import com.libragraph.vault.core.dao.BlobDao;
import com.libragraph.vault.core.dao.BlobRefDao;
import com.libragraph.vault.core.dao.ContainerDao;
import com.libragraph.vault.core.dao.EntryDao;
import com.libragraph.vault.core.event.AllChildrenCompleteEvent;
import com.libragraph.vault.core.event.ChildResult;
import com.libragraph.vault.core.event.FanInContext;
import com.libragraph.vault.core.task.TaskError;
import com.libragraph.vault.core.task.TaskService;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jdbi.v3.core.Jdbi;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Observes AllChildrenCompleteEvent: builds and stores manifest, inserts
 * container + entry rows, cascades to parent FanInContext.
 */
@ApplicationScoped
public class BuildManifestHandler {

    private static final Logger log = Logger.getLogger(BuildManifestHandler.class);

    @Inject
    ManifestManager manifestManager;

    @Inject
    BlobInserter blobInserter;

    @Inject
    Jdbi jdbi;

    @Inject
    TaskService taskService;

    @Inject
    Event<AllChildrenCompleteEvent> allChildrenCompleteEvent;

    @Inject
    @Named("eventExecutor")
    ExecutorService executor;

    void onAllChildrenComplete(@ObservesAsync AllChildrenCompleteEvent event) {
        try {
            FanInContext fanIn = event.fanIn();
            List<ChildResult> results = event.results();

            BlobRef containerRef = fanIn.containerRef();
            String tenantId = fanIn.tenantId();
            int dbTenantId = fanIn.dbTenantId();

            log.debugf("Building manifest for container %s (%d children)",
                    containerRef, results.size());

            // 1. Build manifest proto
            String formatKey = detectFormatKey(fanIn.containerPath());
            BinaryData manifestData = manifestManager.build(containerRef, formatKey, null, results);

            // 2. Store manifest and create blob_ref + blob rows for the container
            BlobInserter.InsertResult containerInsert = blobInserter.insertContainer(
                    tenantId, dbTenantId, manifestData, containerRef);

            // 3. Insert container and entry rows
            jdbi.useHandle(h -> {
                ContainerDao containerDao = h.attach(ContainerDao.class);
                containerDao.insert(containerInsert.blobId(), results.size());

                BlobRefDao blobRefDao = h.attach(BlobRefDao.class);
                BlobDao blobDao = h.attach(BlobDao.class);

                List<EntryDao.EntryRow> entryRows = new ArrayList<>();
                for (ChildResult child : results) {
                    long childBlobRefId = blobRefDao.findOrInsert(child.ref(), null);
                    long childBlobId = blobDao.findOrInsert(dbTenantId, childBlobRefId);

                    entryRows.add(new EntryDao.EntryRow(
                            childBlobId,
                            containerInsert.blobId(),
                            child.entryType(),
                            child.internalPath(),
                            child.mtime(),
                            null // metadata JSON
                    ));
                }

                EntryDao entryDao = h.attach(EntryDao.class);
                entryDao.batchInsert(h, entryRows);
            });

            // 4. Cascade to parent FanInContext
            FanInContext parent = fanIn.parent();
            if (parent != null) {
                short entryType = fanIn.containerPath().endsWith("/") ? (short) 1 : (short) 0;
                ChildResult containerResult = new ChildResult(
                        containerRef, fanIn.containerPath(), true,
                        entryType, null, null);
                parent.addResult(containerResult);
                if (parent.decrementAndCheck()) {
                    allChildrenCompleteEvent.fireAsync(
                            new AllChildrenCompleteEvent(parent, parent.results()),
                            NotificationOptions.ofExecutor(executor));
                }
            } else {
                // Root container — task is complete
                log.infof("Ingestion complete for task %d, container %s",
                        fanIn.taskId(), containerRef);
                taskService.complete(fanIn.taskId(), Map.of(
                        "containerRef", containerRef.toString(),
                        "entryCount", results.size()
                ));
            }
        } catch (Exception e) {
            log.errorf(e, "Failed in pipeline (task %d)", event.fanIn().taskId());
            taskService.fail(event.fanIn().taskId(), TaskError.from(e));
        }
    }

    private String detectFormatKey(String path) {
        if (path == null) return "unknown";
        String lower = path.toLowerCase();
        if (lower.endsWith(".zip")) return "zip";
        if (lower.endsWith(".tar")) return "tar";
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) return "tar";
        if (lower.endsWith(".tar.bz2")) return "tar";
        return "unknown";
    }
}
