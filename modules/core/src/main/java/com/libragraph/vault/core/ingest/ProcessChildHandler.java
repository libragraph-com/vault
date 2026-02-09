package com.libragraph.vault.core.ingest;

import com.libragraph.vault.core.event.AllChildrenCompleteEvent;
import com.libragraph.vault.core.event.ChildDiscoveredEvent;
import com.libragraph.vault.core.event.ChildResult;
import com.libragraph.vault.core.event.FanInContext;
import com.libragraph.vault.core.event.IngestFileEvent;
import com.libragraph.vault.core.task.TaskError;
import com.libragraph.vault.core.task.TaskService;
import com.libragraph.vault.formats.api.ContainerChild;
import com.libragraph.vault.formats.api.FileContext;
import com.libragraph.vault.formats.api.Handler;
import com.libragraph.vault.formats.registry.FormatRegistry;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.RamBuffer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * Observes ChildDiscoveredEvent: hashes, detects format, stores leaf, recurses for containers.
 */
@ApplicationScoped
public class ProcessChildHandler {

    private static final Logger log = Logger.getLogger(ProcessChildHandler.class);

    @Inject
    FormatRegistry formatRegistry;

    @Inject
    BlobInserter blobInserter;

    @Inject
    Event<IngestFileEvent> ingestFileEvent;

    @Inject
    Event<AllChildrenCompleteEvent> allChildrenCompleteEvent;

    @Inject
    TaskService taskService;

    @Inject
    @Named("eventExecutor")
    ExecutorService executor;

    void onChildDiscovered(@ObservesAsync ChildDiscoveredEvent event) {
        try {
            ContainerChild child = event.child();
            FanInContext fanIn = event.fanIn();
            BinaryData buffer = child.buffer();

            // Determine entry type from path
            short entryType = child.path().endsWith("/") ? (short) 1 : (short) 0;

            // Skip empty directories — just report as result
            if (entryType == 1 && buffer.size() == 0) {
                BlobRef dirRef = BlobRef.leaf(buffer.hash(), 1); // directories need size > 0
                BinaryData formatMeta = extractFormatMetadata(child.metadata());
                Instant mtime = extractMtime(child.metadata());

                ChildResult result = new ChildResult(dirRef, child.path(), false,
                        entryType, formatMeta, mtime);
                fanIn.addResult(result);
                if (fanIn.decrementAndCheck()) {
                    allChildrenCompleteEvent.fireAsync(
                            new AllChildrenCompleteEvent(fanIn, fanIn.results()),
                            NotificationOptions.ofExecutor(executor));
                }
                return;
            }

            // Detect format to know if child is a container
            FileContext fileCtx = FileContext.of(child.path());
            Optional<Handler> optHandler = formatRegistry.findHandler(buffer, fileCtx);
            boolean isContainer = optHandler.isPresent() && optHandler.get().hasChildren();
            if (optHandler.isPresent()) {
                try { optHandler.get().close(); } catch (Exception ignored) {}
            }

            // Construct BlobRef with full knowledge
            BlobRef childRef = isContainer
                    ? BlobRef.container(buffer.hash(), buffer.size())
                    : BlobRef.leaf(buffer.hash(), buffer.size());

            // Store leaf
            if (!isContainer) {
                blobInserter.insertLeaf(event.tenantId(), event.dbTenantId(),
                        buffer, childRef, null);
            }

            BinaryData formatMeta = extractFormatMetadata(child.metadata());
            Instant mtime = extractMtime(child.metadata());

            if (isContainer) {
                // Fire IngestFileEvent for recursive processing
                // The nested container's AllChildrenCompleteEvent will cascade back
                ingestFileEvent.fireAsync(new IngestFileEvent(
                        event.taskId(), event.tenantId(), event.dbTenantId(),
                        buffer, child.path(), fanIn),
                        NotificationOptions.ofExecutor(executor));
            } else {
                // Leaf — add result and check completion
                ChildResult result = new ChildResult(childRef, child.path(), false,
                        entryType, formatMeta, mtime);
                fanIn.addResult(result);
                if (fanIn.decrementAndCheck()) {
                    allChildrenCompleteEvent.fireAsync(
                            new AllChildrenCompleteEvent(fanIn, fanIn.results()),
                            NotificationOptions.ofExecutor(executor));
                }
            }
        } catch (Exception e) {
            log.errorf(e, "Failed in pipeline (task %d)", event.taskId());
            taskService.fail(event.taskId(), TaskError.from(e));
        }
    }

    private BinaryData extractFormatMetadata(Map<String, Object> metadata) {
        if (metadata == null) return null;
        byte[] protoBytes = (byte[]) metadata.get("__proto_bytes__");
        if (protoBytes == null || protoBytes.length == 0) return null;
        return new RamBuffer(protoBytes, null);
    }

    private Instant extractMtime(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object mtime = metadata.get("lastModifiedTime");
        if (mtime instanceof Long ms) {
            return Instant.ofEpochMilli(ms);
        }
        return null;
    }
}
