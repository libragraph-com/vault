package com.libragraph.vault.core.ingest;

import com.libragraph.vault.core.event.AllChildrenCompleteEvent;
import com.libragraph.vault.core.event.ChildDiscoveredEvent;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

/**
 * Observes IngestFileEvent: detects format, extracts children, fires ChildDiscoveredEvent for each.
 */
@ApplicationScoped
public class IngestFileHandler {

    private static final Logger log = Logger.getLogger(IngestFileHandler.class);

    @Inject
    FormatRegistry formatRegistry;

    @Inject
    Event<ChildDiscoveredEvent> childDiscoveredEvent;

    @Inject
    Event<AllChildrenCompleteEvent> allChildrenCompleteEvent;

    @Inject
    Event<ObjectCreatedEvent> objectCreatedEvent;

    @Inject
    Event<IngestFileEvent> ingestFileEvent;

    @Inject
    BlobInserter blobInserter;

    @Inject
    TaskService taskService;

    @Inject
    @Named("eventExecutor")
    ExecutorService executor;

    void onIngestFile(@ObservesAsync IngestFileEvent event) {
        try {
            BinaryData buffer = event.buffer();
            String filename = event.filename();
            FanInContext parentFanIn = event.fanIn();

            FileContext fileCtx = FileContext.of(filename);
            Optional<FormatRegistry.HandlerMatch> optMatch =
                    formatRegistry.findHandlerWithMatch(buffer, fileCtx);

            Handler handler = optMatch.map(FormatRegistry.HandlerMatch::handler).orElse(null);
            String mimeType = optMatch.map(m -> m.criteria().primaryMimeType()).orElse(null);

            if (handler == null || !handler.hasChildren()) {
                // Leaf file — if this is the root (no fanIn), it's a leaf-only ingest
                if (parentFanIn == null && !event.bonus()) {
                    // Root is a leaf, not a container. Store it directly.
                    BlobRef leafRef = BlobRef.leaf(buffer.hash(), buffer.size());
                    BlobInserter.InsertResult insert = blobInserter.insertLeaf(
                            event.tenantId(), event.dbTenantId(), buffer, leafRef, mimeType);

                    // Fire ObjectCreatedEvent for the root leaf
                    objectCreatedEvent.fireAsync(new ObjectCreatedEvent(
                            leafRef, insert.blobId(), mimeType, handler, buffer,
                            event.tenantId(), event.dbTenantId(), event.taskId()),
                            NotificationOptions.ofExecutor(executor));

                    log.infof("Root is a leaf: ref=%s", leafRef);
                }
                if (handler != null) {
                    try { handler.close(); } catch (Exception ignored) {}
                }
                return;
            }

            // Check for TIER_2 at root or non-bonus child level
            ContainerCapabilities caps = handler.getCapabilities();
            int tier = (caps != null) ? caps.tier().ordinal() + 1 : 1;
            boolean isTier2 = caps != null
                    && caps.tier() == ContainerCapabilities.ReconstructionTier.TIER_2_STORED;

            if (isTier2 && parentFanIn == null && !event.bonus()) {
                // Root-level TIER_2: store original as leaf, complete task, fire bonus
                BlobRef leafRef = BlobRef.leaf(buffer.hash(), buffer.size());
                BlobInserter.InsertResult insert = blobInserter.insertLeaf(
                        event.tenantId(), event.dbTenantId(), buffer, leafRef, mimeType);

                objectCreatedEvent.fireAsync(new ObjectCreatedEvent(
                        leafRef, insert.blobId(), mimeType, handler, buffer,
                        event.tenantId(), event.dbTenantId(), event.taskId()),
                        NotificationOptions.ofExecutor(executor));

                log.infof("Root TIER_2 stored as leaf: ref=%s", leafRef);
                taskService.complete(event.taskId(), Map.of(
                        "leafRef", leafRef.toString(),
                        "tier", "TIER_2_STORED"
                ));

                // Fire bonus IngestFileEvent for decomposition
                ingestFileEvent.fireAsync(new IngestFileEvent(
                        event.taskId(), event.tenantId(), event.dbTenantId(),
                        buffer, filename, null, true),
                        NotificationOptions.ofExecutor(executor));
                return;
            }

            try (Handler h = handler) {
                Stream<ContainerChild> childStream = h.extractChildren();
                List<ContainerChild> children = childStream.toList();

                if (children.isEmpty()) {
                    // Empty container — still build a manifest
                    BlobRef containerRef = BlobRef.container(buffer.hash(), buffer.size());
                    FanInContext fanIn = new FanInContext(0, parentFanIn,
                            containerRef, filename,
                            event.tenantId(), event.dbTenantId(), event.taskId(),
                            event.bonus(), tier);
                    allChildrenCompleteEvent.fireAsync(
                            new AllChildrenCompleteEvent(fanIn, List.of()),
                            NotificationOptions.ofExecutor(executor));
                    return;
                }

                // Create FanInContext for this container
                BlobRef containerRef = BlobRef.container(buffer.hash(), buffer.size());
                FanInContext fanIn = new FanInContext(children.size(), parentFanIn,
                        containerRef, filename,
                        event.tenantId(), event.dbTenantId(), event.taskId(),
                        event.bonus(), tier);

                log.debugf("Container %s has %d children", filename, children.size());

                for (ContainerChild child : children) {
                    childDiscoveredEvent.fireAsync(new ChildDiscoveredEvent(
                            child, fanIn,
                            event.tenantId(), event.dbTenantId(), event.taskId()),
                            NotificationOptions.ofExecutor(executor));
                }
            }
        } catch (Exception e) {
            log.errorf(e, "Failed in pipeline (task %d)", event.taskId());
            taskService.fail(event.taskId(), TaskError.from(e));
        }
    }
}
