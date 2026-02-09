package com.libragraph.vault.core.ingest;

import com.libragraph.vault.core.event.AllChildrenCompleteEvent;
import com.libragraph.vault.core.event.ChildDiscoveredEvent;
import com.libragraph.vault.core.event.ChildResult;
import com.libragraph.vault.core.event.FanInContext;
import com.libragraph.vault.core.event.IngestFileEvent;
import com.libragraph.vault.core.event.ObjectCreatedEvent;
import com.libragraph.vault.core.task.TaskError;
import com.libragraph.vault.core.task.TaskService;
import com.libragraph.vault.formats.api.ContainerCapabilities;
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
    Event<ObjectCreatedEvent> objectCreatedEvent;

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

            // Detect format with match criteria
            FileContext fileCtx = FileContext.of(child.path());
            Optional<FormatRegistry.HandlerMatch> optMatch =
                    formatRegistry.findHandlerWithMatch(buffer, fileCtx);

            Handler handler = optMatch.map(FormatRegistry.HandlerMatch::handler).orElse(null);
            String mimeType = optMatch.map(m -> m.criteria().primaryMimeType()).orElse(null);
            boolean isContainer = handler != null && handler.hasChildren();

            // Check for TIER_2 container
            boolean isTier2 = false;
            if (isContainer) {
                ContainerCapabilities caps = handler.getCapabilities();
                isTier2 = caps != null
                        && caps.tier() == ContainerCapabilities.ReconstructionTier.TIER_2_STORED;
            }

            BinaryData formatMeta = extractFormatMetadata(child.metadata());
            Instant mtime = extractMtime(child.metadata());

            if (isTier2) {
                // TIER_2: store original as leaf, report leaf to parent, bonus-ingest
                BlobRef leafRef = BlobRef.leaf(buffer.hash(), buffer.size());
                BlobInserter.InsertResult insert = blobInserter.insertLeaf(
                        event.tenantId(), event.dbTenantId(), buffer, leafRef, mimeType);

                // Report LEAF to parent FanIn (parent manifest references the leaf, not container)
                ChildResult result = new ChildResult(leafRef, child.path(), false,
                        entryType, formatMeta, mtime);
                fanIn.addResult(result);
                if (fanIn.decrementAndCheck()) {
                    allChildrenCompleteEvent.fireAsync(
                            new AllChildrenCompleteEvent(fanIn, fanIn.results()),
                            NotificationOptions.ofExecutor(executor));
                }

                // Fire ObjectCreatedEvent for the stored leaf
                objectCreatedEvent.fireAsync(new ObjectCreatedEvent(
                        leafRef, insert.blobId(), mimeType, handler, buffer,
                        event.tenantId(), event.dbTenantId(), event.taskId()),
                        NotificationOptions.ofExecutor(executor));

                // Fire BONUS IngestFileEvent — detached from parent FanIn
                ingestFileEvent.fireAsync(new IngestFileEvent(
                        event.taskId(), event.tenantId(), event.dbTenantId(),
                        buffer, child.path(), null, true),
                        NotificationOptions.ofExecutor(executor));
            } else if (isContainer) {
                // TIER_1 container — close handler (IngestFileHandler re-creates it), recurse
                try { handler.close(); } catch (Exception ignored) {}

                ingestFileEvent.fireAsync(new IngestFileEvent(
                        event.taskId(), event.tenantId(), event.dbTenantId(),
                        buffer, child.path(), fanIn),
                        NotificationOptions.ofExecutor(executor));
            } else {
                // Leaf — store and report
                if (handler != null) {
                    try { handler.close(); } catch (Exception ignored) {}
                }

                BlobRef leafRef = BlobRef.leaf(buffer.hash(), buffer.size());
                BlobInserter.InsertResult insert = blobInserter.insertLeaf(
                        event.tenantId(), event.dbTenantId(), buffer, leafRef, mimeType);

                // Fire ObjectCreatedEvent for the stored leaf
                objectCreatedEvent.fireAsync(new ObjectCreatedEvent(
                        leafRef, insert.blobId(), mimeType, null, buffer,
                        event.tenantId(), event.dbTenantId(), event.taskId()),
                        NotificationOptions.ofExecutor(executor));

                ChildResult result = new ChildResult(leafRef, child.path(), false,
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
