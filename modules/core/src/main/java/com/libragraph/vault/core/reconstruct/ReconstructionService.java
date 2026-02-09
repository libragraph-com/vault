package com.libragraph.vault.core.reconstruct;

import com.libragraph.vault.core.ingest.ManifestManager;
import com.libragraph.vault.core.storage.BlobService;
import com.libragraph.vault.formats.api.ContainerChild;
import com.libragraph.vault.formats.api.FileContext;
import com.libragraph.vault.formats.api.Handler;
import com.libragraph.vault.formats.proto.ManifestProto;
import com.libragraph.vault.formats.registry.FormatRegistry;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.ContentHash;
import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.Buffer;
import com.libragraph.vault.util.buffer.RamBuffer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reconstructs containers from ObjectStorage alone (no database queries).
 *
 * <p>Reads the manifest, retrieves each child blob, and uses the format handler
 * to reconstruct the original container bit-identically.
 */
@ApplicationScoped
public class ReconstructionService {

    private static final Logger log = Logger.getLogger(ReconstructionService.class);

    @Inject
    ManifestManager manifestManager;

    @Inject
    BlobService blobService;

    @Inject
    FormatRegistry formatRegistry;

    /**
     * Reconstructs a container from its manifest and stored blobs.
     *
     * @param tenantId storage tenant key
     * @param containerRef the container BlobRef (must be isContainer=true)
     * @return the reconstructed container as BinaryData
     */
    public BinaryData reconstruct(String tenantId, BlobRef containerRef) {
        ManifestProto.Manifest manifest = manifestManager.load(tenantId, containerRef);

        String formatKey = manifest.getFormatKey();
        log.debugf("Reconstructing container %s (format=%s, %d entries)",
                containerRef, formatKey, manifest.getEntriesCount());

        // Resolve children — for each entry, read from storage or recursively reconstruct
        List<ContainerChild> children = new ArrayList<>();
        for (ManifestProto.ManifestEntry entry : manifest.getEntriesList()) {
            ContentHash childHash = new ContentHash(entry.getContentHash().toByteArray());
            boolean isContainer = entry.getIsContainer();
            long leafSize = entry.getLeafSize();

            BinaryData childBuffer;
            if (isContainer) {
                BlobRef childRef = BlobRef.container(childHash, leafSize);
                childBuffer = reconstruct(tenantId, childRef); // recurse
            } else if (entry.getEntryType() == 1) {
                // Directory entry — empty buffer
                childBuffer = Buffer.allocate(0);
            } else {
                BlobRef childRef = BlobRef.leaf(childHash, leafSize);
                childBuffer = blobService.retrieve(tenantId, childRef).await().indefinitely();
            }

            // Reconstruct metadata map with proto bytes for format handler
            Map<String, Object> metadata = Map.of();
            if (!entry.getFormatMetadata().isEmpty()) {
                metadata = Map.of(
                        "__proto_bytes__", entry.getFormatMetadata().toByteArray(),
                        "__proto_type__", "entry"
                );
            }

            children.add(new ContainerChild(entry.getPath(), childBuffer, metadata));
        }

        // Use format handler to reconstruct the container
        // We need a dummy buffer to create the handler — use the first child's data
        // Actually we just need to find the right handler by format key
        Buffer outputBuffer = Buffer.allocate(containerRef.leafSize());
        try (OutputStream out = outputBuffer.outputStream(0)) {
            // Find a handler that can reconstruct this format
            Optional<Handler> optHandler = findHandlerByFormatKey(formatKey);
            if (optHandler.isEmpty()) {
                throw new RuntimeException("No handler found for format: " + formatKey);
            }
            Handler handler = optHandler.get();
            try {
                handler.reconstruct(children, out);
            } finally {
                try { handler.close(); } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to reconstruct container: " + containerRef, e);
        }

        try {
            outputBuffer.position(0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to reset output buffer position", e);
        }
        return outputBuffer;
    }

    private Optional<Handler> findHandlerByFormatKey(String formatKey) {
        // Create a minimal buffer that matches the format's detection criteria
        // For reconstruction, we use the format key to select the right handler
        // This is a simple lookup — in practice the format key maps directly
        String filename = switch (formatKey) {
            case "zip" -> "dummy.zip";
            case "tar" -> "dummy.tar";
            default -> "dummy." + formatKey;
        };

        // We need a buffer with the right magic bytes for detection
        // For ZIP: PK\x03\x04, for TAR: "ustar" at offset 257
        // Since we're reconstructing, we know the format — create a minimal detection buffer
        byte[] magic = switch (formatKey) {
            case "zip" -> new byte[]{0x50, 0x4B, 0x03, 0x04};
            case "tar" -> {
                byte[] buf = new byte[263];
                System.arraycopy("ustar".getBytes(), 0, buf, 257, 5);
                yield buf;
            }
            default -> new byte[0];
        };

        BinaryData detectionBuffer = new RamBuffer(magic, null);
        FileContext ctx = FileContext.of(filename);
        return formatRegistry.findHandler(detectionBuffer, ctx);
    }
}
