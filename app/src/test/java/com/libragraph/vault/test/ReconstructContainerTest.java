package com.libragraph.vault.test;

import com.libragraph.vault.core.reconstruct.ReconstructionService;
import com.libragraph.vault.core.storage.TenantStorageResolver;
import com.libragraph.vault.core.task.TaskStatus;
import com.libragraph.vault.core.test.VaultTestConfig;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip test: ingest a ZIP, then reconstruct it and verify
 * the reconstructed content matches the original.
 */
@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class ReconstructContainerTest {

    @Inject
    TestTaskRunner taskRunner;

    @Inject
    ReconstructionService reconstructionService;

    @Inject
    VaultTestConfig testConfig;

    @Inject
    TenantStorageResolver tenantResolver;

    int dbTenantId;
    String storageTenantId;

    @BeforeEach
    void setUp() {
        dbTenantId = testConfig.ensureTestTenant();
        storageTenantId = tenantResolver.resolve(dbTenantId);
    }

    @Test
    void reconstructZip_matchesOriginal() throws Exception {
        // Build a test ZIP
        BinaryData originalZip = new TestZipBuilder()
                .addFile("hello.txt", "Hello reconstruction!")
                .addFile("data.bin", new byte[]{1, 2, 3, 4, 5})
                .buildBinaryData();

        BlobRef containerRef = BlobRef.container(originalZip.hash(), originalZip.size());

        // Ingest — data passed directly, never stored as raw ZIP
        int taskId = taskRunner.ingest(originalZip, "archive.zip", storageTenantId, dbTenantId);
        assertThat(taskRunner.getTask(taskId).status()).isEqualTo(TaskStatus.COMPLETE);

        // Reconstruct
        BinaryData reconstructed = reconstructionService.reconstruct(storageTenantId, containerRef);

        // Verify reconstructed ZIP has same entries and content
        assertThat(reconstructed.size()).isGreaterThan(0);

        // Compare entry-by-entry (ZIP binary may differ in headers but content should match)
        verifyZipContentsMatch(originalZip, reconstructed);
    }

    @Test
    void reconstructZip_deterministic() throws Exception {
        BinaryData zipData = new TestZipBuilder()
                .addFile("a.txt", "alpha")
                .addFile("b.txt", "beta")
                .buildBinaryData();

        BlobRef containerRef = BlobRef.container(zipData.hash(), zipData.size());

        int taskId = taskRunner.ingest(zipData, "archive.zip", storageTenantId, dbTenantId);
        assertThat(taskRunner.getTask(taskId).status()).isEqualTo(TaskStatus.COMPLETE);

        // Reconstruct twice
        BinaryData first = reconstructionService.reconstruct(storageTenantId, containerRef);
        BinaryData second = reconstructionService.reconstruct(storageTenantId, containerRef);

        // Byte-identical output
        byte[] firstBytes = readAll(first);
        byte[] secondBytes = readAll(second);
        assertThat(secondBytes).isEqualTo(firstBytes);
    }

    private void verifyZipContentsMatch(BinaryData original, BinaryData reconstructed) throws IOException {
        var originalEntries = readZipEntries(original);
        var reconstructedEntries = readZipEntries(reconstructed);

        assertThat(reconstructedEntries.keySet())
                .as("Reconstructed ZIP should have same entry names")
                .containsExactlyInAnyOrderElementsOf(originalEntries.keySet());

        for (var entry : originalEntries.entrySet()) {
            byte[] originalContent = entry.getValue();
            byte[] reconstructedContent = reconstructedEntries.get(entry.getKey());
            assertThat(reconstructedContent)
                    .as("Content of %s should match", entry.getKey())
                    .isEqualTo(originalContent);
        }
    }

    private java.util.Map<String, byte[]> readZipEntries(BinaryData data) throws IOException {
        var result = new java.util.LinkedHashMap<String, byte[]>();
        data.position(0);
        byte[] rawBytes = readAll(data);

        try (ZipInputStream zis = new ZipInputStream(
                new java.io.ByteArrayInputStream(rawBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = zis.read(buf)) > 0) {
                    baos.write(buf, 0, len);
                }
                result.put(entry.getName(), baos.toByteArray());
                zis.closeEntry();
            }
        }
        return result;
    }

    private byte[] readAll(BinaryData data) throws IOException {
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
}
