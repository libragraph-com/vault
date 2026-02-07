package com.libragraph.vault.formats.handlers;

import com.libragraph.vault.formats.api.ContainerChild;
import com.libragraph.vault.formats.api.FileContext;
import com.libragraph.vault.util.buffer.BinaryData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

class ZipHandlerFactoryTest {

    private final ZipHandlerFactory zipFactory = new ZipHandlerFactory();

    @TempDir
    Path tempDir;

    @Test
    void shouldDetectZipViaMagicBytes() {
        var criteria = zipFactory.getDetectionCriteria();
        byte[] zipHeader = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00};

        assertThat(criteria.matches("application/zip", "test.zip", zipHeader)).isTrue();
        assertThat(criteria.priority()).isEqualTo(200);
    }

    @Test
    void shouldExtractChildrenFromSimpleZip() throws Exception {
        Path zipPath = fixtureAsPath("zip/simple.zip");
        List<ContainerChild> children = extractChildren(zipPath);

        assertThat(children).isNotEmpty();
        // All children should have path and buffer
        for (ContainerChild child : children) {
            assertThat(child.path()).isNotBlank();
            assertThat(child.buffer()).isNotNull();
            assertThat(child.metadata()).isNotNull();
        }
    }

    @Test
    void shouldReconstructSimpleZip() throws Exception {
        Path originalZip = fixtureAsPath("zip/simple.zip");

        List<ContainerChild> children = extractChildren(originalZip);
        Path reconstructed = reconstructZip(originalZip, children);

        // Verify file contents are identical
        verifyFilesIdentical(originalZip, reconstructed);

        // Verify deterministic
        Path reconstructed2 = reconstructZip(originalZip, extractChildren(originalZip));
        assertThat(hashFile(reconstructed2)).isEqualTo(hashFile(reconstructed));
    }

    @Test
    void shouldPreserveDirectoryEntries() throws Exception {
        Path zipPath = fixtureAsPath("zip/with-folders.zip");
        List<ContainerChild> children = extractChildren(zipPath);

        long dirCount = children.stream()
                .filter(c -> c.path().endsWith("/"))
                .count();

        assertThat(dirCount)
                .as("Directory entries should be preserved")
                .isGreaterThan(0);
    }

    @Test
    void shouldHandleDocxRoundTrip() throws Exception {
        Path originalDocx = fixtureAsPath("zip/sample.docx");

        List<ContainerChild> children = extractChildren(originalDocx);
        assertThat(children).isNotEmpty();

        Path reconstructed = reconstructZip(originalDocx, children);

        long originalSize = Files.size(originalDocx);
        long reconstructedSize = Files.size(reconstructed);

        assertThat(reconstructedSize)
                .as("Reconstructed DOCX size should be close to original")
                .isCloseTo(originalSize, org.assertj.core.data.Percentage.withPercentage(2.0));

        // Verify deterministic
        Path reconstructed2 = reconstructZip(originalDocx, extractChildren(originalDocx));
        assertThat(hashFile(reconstructed2)).isEqualTo(hashFile(reconstructed));
    }

    // --- helpers ---

    private List<ContainerChild> extractChildren(Path zipPath) throws Exception {
        BinaryData buffer = BinaryData.wrap(Files.newByteChannel(zipPath));
        FileContext context = FileContext.of(zipPath);
        var handler = zipFactory.createInstance(buffer, context);
        return handler.extractChildren().collect(Collectors.toList());
    }

    private Path reconstructZip(Path originalZip, List<ContainerChild> children) throws Exception {
        Path reconstructed = tempDir.resolve("reconstructed_" + originalZip.getFileName());
        BinaryData buffer = BinaryData.wrap(Files.newByteChannel(originalZip));
        FileContext context = FileContext.of(originalZip);
        var handler = zipFactory.createInstance(buffer, context);
        try (var os = Files.newOutputStream(reconstructed)) {
            handler.reconstruct(children, os);
        }
        return reconstructed;
    }

    private Path fixtureAsPath(String relativePath) throws Exception {
        // Copy fixture from classpath to temp dir so we get a real Path
        String resourcePath = "/fixtures/" + relativePath;
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertThat(is).as("Fixture not found: " + resourcePath).isNotNull();
            Path dest = tempDir.resolve(Path.of(relativePath).getFileName());
            Files.copy(is, dest);
            return dest;
        }
    }

    private void verifyFilesIdentical(Path zip1, Path zip2) throws Exception {
        try (var zf1 = new java.util.zip.ZipFile(zip1.toFile());
             var zf2 = new java.util.zip.ZipFile(zip2.toFile())) {

            var entries1 = new java.util.ArrayList<java.util.zip.ZipEntry>();
            var entries2 = new java.util.ArrayList<java.util.zip.ZipEntry>();
            zf1.stream().forEach(entries1::add);
            zf2.stream().forEach(entries2::add);

            assertThat(entries2).hasSameSizeAs(entries1);

            for (int i = 0; i < entries1.size(); i++) {
                var e1 = entries1.get(i);
                var e2 = entries2.get(i);

                assertThat(e2.getName()).isEqualTo(e1.getName());
                assertThat(e2.getSize()).isEqualTo(e1.getSize());
                assertThat(e2.getCrc()).isEqualTo(e1.getCrc());

                if (!e1.isDirectory()) {
                    byte[] content1 = zf1.getInputStream(e1).readAllBytes();
                    byte[] content2 = zf2.getInputStream(e2).readAllBytes();
                    assertThat(content2).isEqualTo(content1);
                }
            }
        }
    }

    private String hashFile(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                md.update(buffer, 0, read);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString().substring(0, 32);
    }
}
