package com.libragraph.vault.core.reconstruct;

import com.libragraph.vault.core.storage.TenantStorageResolver;
import com.libragraph.vault.core.task.TaskContext;
import com.libragraph.vault.core.task.TaskIO;
import com.libragraph.vault.core.task.TaskOutcome;
import com.libragraph.vault.core.task.VaultTask;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Task that reconstructs a container from its manifest and stored blobs.
 */
@ApplicationScoped
@TaskIO(input = Map.class, output = Map.class)
public class ReconstructContainerTask implements VaultTask {

    private static final Logger log = Logger.getLogger(ReconstructContainerTask.class);

    @Inject
    ReconstructionService reconstructionService;

    @Inject
    TenantStorageResolver tenantResolver;

    @Override
    public String taskType() {
        return "reconstruct.container";
    }

    @Override
    @SuppressWarnings("unchecked")
    public TaskOutcome onStart(Object input, TaskContext ctx) {
        Map<String, Object> inputMap = (Map<String, Object>) input;
        String containerKey = (String) inputMap.get("containerRef");
        String tenantId = tenantResolver.resolve(ctx.tenantId());

        BlobRef containerRef = BlobRef.parse(containerKey);

        log.infof("Reconstructing container: %s for tenant %s", containerRef, tenantId);

        BinaryData reconstructed = reconstructionService.reconstruct(tenantId, containerRef);

        return TaskOutcome.complete(Map.of(
                "containerRef", containerRef.toString(),
                "reconstructedSize", reconstructed.size()
        ));
    }
}
