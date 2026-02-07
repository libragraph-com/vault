package com.libragraph.vault.test;

import com.libragraph.vault.core.storage.BlobNotFoundException;
import com.libragraph.vault.core.storage.ObjectStorage;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.ContentHash;
import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.Buffer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class ObjectStorageTest {

    @Inject
    ObjectStorage storage;

    private BinaryData createData(byte[] content) throws IOException {
        Buffer buf = Buffer.allocate(content.length);
        buf.write(ByteBuffer.wrap(content));
        buf.position(0);
        return buf;
    }

    private byte[] readAllBytes(BinaryData data) throws IOException {
        data.position(0);
        ByteBuffer buf = ByteBuffer.allocate((int) data.size());
        while (buf.hasRemaining()) {
            if (data.read(buf) == -1) break;
        }
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    @Test
    void writeAndReadRoundTrip() throws IOException {
        UUID tenantId = UUID.randomUUID();
        byte[] content = "hello vault".getBytes();
        BinaryData data = createData(content);
        BlobRef ref = BlobRef.leaf(data.hash(), data.size());

        storage.write(tenantId, ref, data, "text/plain").await().indefinitely();

        BinaryData result = storage.read(tenantId, ref).await().indefinitely();
        assertThat(result.size()).isEqualTo(content.length);
        assertThat(readAllBytes(result)).isEqualTo(content);
    }

    @Test
    void existsReturnsFalseForMissing() {
        UUID tenantId = UUID.randomUUID();
        ContentHash hash = ContentHash.fromHex("aaaabbbbccccddddaaaabbbbccccdddd");
        BlobRef ref = BlobRef.leaf(hash, 100);

        Boolean exists = storage.exists(tenantId, ref).await().indefinitely();
        assertThat(exists).isFalse();
    }

    @Test
    void existsReturnsTrueAfterWrite() throws IOException {
        UUID tenantId = UUID.randomUUID();
        byte[] content = "exists test".getBytes();
        BinaryData data = createData(content);
        BlobRef ref = BlobRef.leaf(data.hash(), data.size());

        storage.write(tenantId, ref, data, null).await().indefinitely();

        Boolean exists = storage.exists(tenantId, ref).await().indefinitely();
        assertThat(exists).isTrue();
    }

    @Test
    void deleteRemovesBlob() throws IOException {
        UUID tenantId = UUID.randomUUID();
        byte[] content = "delete me".getBytes();
        BinaryData data = createData(content);
        BlobRef ref = BlobRef.leaf(data.hash(), data.size());

        storage.write(tenantId, ref, data, null).await().indefinitely();
        storage.delete(tenantId, ref).await().indefinitely();

        Boolean exists = storage.exists(tenantId, ref).await().indefinitely();
        assertThat(exists).isFalse();
    }

    @Test
    void readMissingBlobThrows() {
        UUID tenantId = UUID.randomUUID();
        ContentHash hash = ContentHash.fromHex("deadbeefdeadbeefdeadbeefdeadbeef");
        BlobRef ref = BlobRef.leaf(hash, 42);

        assertThatThrownBy(() -> storage.read(tenantId, ref).await().indefinitely())
                .isInstanceOf(BlobNotFoundException.class);
    }

    @Test
    void deleteMissingBlobThrows() {
        UUID tenantId = UUID.randomUUID();
        ContentHash hash = ContentHash.fromHex("deadbeefdeadbeefdeadbeefdeadbeef");
        BlobRef ref = BlobRef.leaf(hash, 42);

        assertThatThrownBy(() -> storage.delete(tenantId, ref).await().indefinitely())
                .isInstanceOf(BlobNotFoundException.class);
    }

    @Test
    void listTenantsIncludesWrittenTenant() throws IOException {
        UUID tenantId = UUID.randomUUID();
        byte[] content = "tenant listing".getBytes();
        BinaryData data = createData(content);
        BlobRef ref = BlobRef.leaf(data.hash(), data.size());

        storage.write(tenantId, ref, data, null).await().indefinitely();

        List<UUID> tenants = storage.listTenants().collect().asList().await().indefinitely();
        assertThat(tenants).contains(tenantId);
    }

    @Test
    void listContainersReturnsOnlyContainers() throws IOException {
        UUID tenantId = UUID.randomUUID();

        // Write a leaf
        byte[] leafContent = "leaf data".getBytes();
        BinaryData leafData = createData(leafContent);
        BlobRef leafRef = BlobRef.leaf(leafData.hash(), leafData.size());
        storage.write(tenantId, leafRef, leafData, null).await().indefinitely();

        // Write a container
        byte[] containerContent = "container data".getBytes();
        BinaryData containerData = createData(containerContent);
        BlobRef containerRef = BlobRef.container(containerData.hash(), containerData.size());
        storage.write(tenantId, containerRef, containerData, null).await().indefinitely();

        List<BlobRef> containers = storage.listContainers(tenantId)
                .collect().asList().await().indefinitely();
        assertThat(containers).contains(containerRef);
        assertThat(containers).doesNotContain(leafRef);
    }

    @Test
    void deleteTenantRemovesBucketAndBlobs() throws IOException {
        UUID tenantId = UUID.randomUUID();

        byte[] content = "tenant data".getBytes();
        BinaryData data = createData(content);
        BlobRef ref = BlobRef.leaf(data.hash(), data.size());
        storage.write(tenantId, ref, data, null).await().indefinitely();

        storage.deleteTenant(tenantId).await().indefinitely();

        assertThat(storage.exists(tenantId, ref).await().indefinitely()).isFalse();
        List<UUID> tenants = storage.listTenants().collect().asList().await().indefinitely();
        assertThat(tenants).doesNotContain(tenantId);
    }

    @Test
    void deleteTenantIsIdempotent() {
        UUID tenantId = UUID.randomUUID();
        // Should not throw on non-existent tenant
        storage.deleteTenant(tenantId).await().indefinitely();
    }

    @Test
    void tenantsAreIsolated() throws IOException {
        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();

        byte[] content = "isolated".getBytes();
        BinaryData data = createData(content);
        BlobRef ref = BlobRef.leaf(data.hash(), data.size());

        storage.write(tenant1, ref, data, null).await().indefinitely();

        assertThat(storage.exists(tenant1, ref).await().indefinitely()).isTrue();
        assertThat(storage.exists(tenant2, ref).await().indefinitely()).isFalse();
    }
}
