package com.libragraph.vault.core.ingest;

import com.libragraph.vault.core.event.ChildResult;
import com.libragraph.vault.core.storage.BlobService;
import com.libragraph.vault.formats.proto.ManifestProto;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.Buffer;
import com.google.protobuf.ByteString;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Builds, stores, and loads Manifest protos for container blobs.
 */
@ApplicationScoped
public class ManifestManager {

    @Inject
    BlobService blobService;

    /**
     * Builds a serialized Manifest proto from the container's child results.
     */
    public BinaryData build(BlobRef containerRef, String formatKey,
                            byte[] formatMetadata, List<ChildResult> results) {
        ManifestProto.Manifest.Builder builder = ManifestProto.Manifest.newBuilder()
                .setContainerHash(ByteString.copyFrom(containerRef.hash().bytes()))
                .setContainerSize(containerRef.leafSize())
                .setFormatKey(formatKey);

        if (formatMetadata != null && formatMetadata.length > 0) {
            builder.setFormatMetadata(ByteString.copyFrom(formatMetadata));
        }

        for (ChildResult child : results) {
            ManifestProto.ManifestEntry.Builder entry = ManifestProto.ManifestEntry.newBuilder()
                    .setPath(child.internalPath())
                    .setContentHash(ByteString.copyFrom(child.ref().hash().bytes()))
                    .setLeafSize(child.ref().leafSize())
                    .setIsContainer(child.ref().isContainer())
                    .setEntryType(child.entryType());

            if (child.mtime() != null) {
                entry.setMtime(child.mtime().toEpochMilli());
            }

            if (child.formatMetadata() != null && child.formatMetadata().size() > 0) {
                try {
                    child.formatMetadata().position(0);
                    ByteBuffer buf = ByteBuffer.allocate((int) child.formatMetadata().size());
                    child.formatMetadata().read(buf);
                    buf.flip();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    entry.setFormatMetadata(ByteString.copyFrom(bytes));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read format metadata", e);
                }
            }

            builder.addEntries(entry);
        }

        byte[] protoBytes = builder.build().toByteArray();
        Buffer buffer = Buffer.allocate(protoBytes.length);
        try {
            buffer.write(ByteBuffer.wrap(protoBytes));
            buffer.position(0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write manifest proto to buffer", e);
        }
        return buffer;
    }

    /**
     * Stores a manifest in ObjectStorage at the container's BlobRef key.
     */
    public void store(String tenantId, BlobRef containerRef, BinaryData manifestData) {
        blobService.create(tenantId, containerRef, manifestData, "application/x-protobuf")
                .await().indefinitely();
    }

    /**
     * Loads and parses a Manifest proto from ObjectStorage.
     */
    public ManifestProto.Manifest load(String tenantId, BlobRef containerRef) {
        BinaryData data = blobService.retrieve(tenantId, containerRef).await().indefinitely();
        try {
            data.position(0);
            ByteBuffer buf = ByteBuffer.allocate((int) data.size());
            while (buf.hasRemaining()) {
                if (data.read(buf) == -1) break;
            }
            buf.flip();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return ManifestProto.Manifest.parseFrom(bytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse manifest for " + containerRef, e);
        }
    }
}
