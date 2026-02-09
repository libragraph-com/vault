package com.libragraph.vault.core.ingest;

import com.libragraph.vault.core.event.AllChildrenCompleteEvent;
import com.libragraph.vault.core.event.ChildDiscoveredEvent;
import com.libragraph.vault.core.event.FanInContext;
import com.libragraph.vault.core.event.IngestFileEvent;
import com.libragraph.vault.formats.api.ContainerChild;
import com.libragraph.vault.formats.api.FileContext;
import com.libragraph.vault.formats.api.Handler;
import com.libragraph.vault.formats.registry.FormatRegistry;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
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
    BlobInserter blobInserter;

    void onIngestFile(@Observes IngestFileEvent event) {
        BinaryData buffer = event.buffer();
        String filename = event.filename();
        FanInContext parentFanIn = event.fanIn();

        FileContext fileCtx = FileContext.of(filename);
        Optional<Handler> optHandler = formatRegistry.findHandler(buffer, fileCtx);

        if (optHandler.isEmpty() || !optHandler.get().hasChildren()) {
            // Leaf file — if this is the root (no fanIn), it's a leaf-only ingest
            if (parentFanIn == null) {
                // Root is a leaf, not a container. Store it directly.
                BlobRef leafRef = BlobRef.leaf(buffer.hash(), buffer.size());
                blobInserter.insertLeaf(event.tenantId(), event.dbTenantId(),
                        buffer, leafRef, null);
                log.infof("Root is a leaf: ref=%s", leafRef);
            }
            return;
        }

        try (Handler handler = optHandler.get()) {
            Stream<ContainerChild> childStream = handler.extractChildren();
            List<ContainerChild> children = childStream.toList();

            if (children.isEmpty()) {
                // Empty container — still build a manifest
                BlobRef containerRef = BlobRef.container(buffer.hash(), buffer.size());
                FanInContext fanIn = new FanInContext(0, parentFanIn,
                        containerRef, filename,
                        event.tenantId(), event.dbTenantId(), event.taskId());
                allChildrenCompleteEvent.fire(new AllChildrenCompleteEvent(fanIn, List.of()));
                return;
            }

            // Create FanInContext for this container
            BlobRef containerRef = BlobRef.container(buffer.hash(), buffer.size());
            FanInContext fanIn = new FanInContext(children.size(), parentFanIn,
                    containerRef, filename,
                    event.tenantId(), event.dbTenantId(), event.taskId());

            log.debugf("Container %s has %d children", filename, children.size());

            for (ContainerChild child : children) {
                childDiscoveredEvent.fire(new ChildDiscoveredEvent(
                        child, fanIn,
                        event.tenantId(), event.dbTenantId(), event.taskId()));
            }
        } catch (Exception e) {
            log.errorf(e, "Failed to process container: %s", filename);
            throw new RuntimeException("Failed to process container: " + filename, e);
        }
    }
}
