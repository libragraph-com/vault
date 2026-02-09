package com.libragraph.vault.core.storage;

import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.RamBuffer;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Filesystem-backed ObjectStorage for development and testing.
 *
 * <p>Layout: {@code {root}/{tenantId}/{tier1}/{tier2}/{key}}
 * where tier1 = hash[0:2], tier2 = hash[2:4].
 *
 * <p>No compression — stores data as-is for easy debugging.
 */
@ApplicationScoped
@IfBuildProperty(name = "vault.object-store.type", stringValue = "filesystem")
public class FilesystemObjectStorage implements ObjectStorage {

    @ConfigProperty(name = "vault.object-store.filesystem.root")
    String root;

    private Path resolvePath(String tenantId, BlobRef ref) {
        String hex = ref.hash().toHex();
        String tier1 = hex.substring(0, 2);
        String tier2 = hex.substring(2, 4);
        return Path.of(root, tenantId, tier1, tier2, ref.toString());
    }

    @Override
    public Uni<BinaryData> read(String tenantId, BlobRef ref) {
        return Uni.createFrom().item(() -> {
            Path path = resolvePath(tenantId, ref);
            if (!Files.exists(path)) {
                throw new BlobNotFoundException(tenantId, ref);
            }
            try {
                byte[] bytes = Files.readAllBytes(path);
                return (BinaryData) new RamBuffer(bytes, ref.hash());
            } catch (IOException e) {
                throw new StorageException("Failed to read blob: " + ref, e);
            }
        });
    }

    @Override
    public Uni<Void> create(String tenantId, BlobRef ref, BinaryData data, String mimeType) {
        return Uni.createFrom().voidItem().invoke(() -> {
            Path path = resolvePath(tenantId, ref);
            try {
                Files.createDirectories(path.getParent());
                try (FileChannel out = FileChannel.open(path,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
                    data.position(0);
                    ByteBuffer buf = ByteBuffer.allocate(8192);
                    while (data.read(buf) != -1) {
                        buf.flip();
                        while (buf.hasRemaining()) {
                            out.write(buf);
                        }
                        buf.clear();
                    }
                }
            } catch (IOException e) {
                throw new StorageException("Failed to write blob: " + ref, e);
            }
        });
    }

    @Override
    public Uni<Boolean> exists(String tenantId, BlobRef ref) {
        return Uni.createFrom().item(() -> Files.exists(resolvePath(tenantId, ref)));
    }

    @Override
    public Uni<Void> delete(String tenantId, BlobRef ref) {
        return Uni.createFrom().voidItem().invoke(() -> {
            Path path = resolvePath(tenantId, ref);
            try {
                if (!Files.deleteIfExists(path)) {
                    throw new BlobNotFoundException(tenantId, ref);
                }
                pruneEmptyParents(path.getParent(), Path.of(root));
            } catch (IOException e) {
                throw new StorageException("Failed to delete blob: " + ref, e);
            }
        });
    }

    private void pruneEmptyParents(Path dir, Path stop) throws IOException {
        Path current = dir;
        while (current != null && !current.equals(stop)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(current)) {
                if (entries.iterator().hasNext()) {
                    break;
                }
            }
            Files.delete(current);
            current = current.getParent();
        }
    }

    @Override
    public Uni<Void> deleteTenant(String tenantId) {
        return Uni.createFrom().voidItem().invoke(() -> {
            Path tenantPath = Path.of(root, tenantId);
            if (!Files.isDirectory(tenantPath)) {
                return;
            }
            try {
                Files.walkFileTree(tenantPath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                            throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new StorageException("Failed to delete tenant: " + tenantId, e);
            }
        });
    }

    @Override
    public Multi<String> listTenants() {
        return Multi.createFrom().items(() -> {
            Path rootPath = Path.of(root);
            if (!Files.isDirectory(rootPath)) {
                return java.util.stream.Stream.<String>empty();
            }
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(rootPath)) {
                List<String> tenants = new ArrayList<>();
                for (Path dir : dirs) {
                    if (Files.isDirectory(dir)) {
                        tenants.add(dir.getFileName().toString());
                    }
                }
                return tenants.stream();
            } catch (IOException e) {
                throw new StorageException("Failed to list tenants", e);
            }
        });
    }

    @Override
    public Multi<BlobRef> listContainers(String tenantId) {
        return Multi.createFrom().items(() -> {
            Path tenantPath = Path.of(root, tenantId);
            if (!Files.isDirectory(tenantPath)) {
                return java.util.stream.Stream.<BlobRef>empty();
            }
            try {
                return Files.walk(tenantPath)
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith("_"))
                        .map(p -> BlobRef.parse(p.getFileName().toString()));
            } catch (IOException e) {
                throw new StorageException("Failed to list containers for tenant: " + tenantId, e);
            }
        });
    }
}
