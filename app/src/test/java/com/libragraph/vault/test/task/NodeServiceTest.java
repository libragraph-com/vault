package com.libragraph.vault.test.task;

import com.libragraph.vault.core.cluster.NodeService;
import com.libragraph.vault.test.MinioTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class NodeServiceTest {

    @Inject
    NodeService nodeService;

    @Inject
    Jdbi jdbi;

    @Test
    void nodeService_registersOnStartup() {
        assertThat(nodeService.isRunning()).isTrue();
        assertThat(nodeService.nodeId()).isPositive();
        assertThat(nodeService.hostname()).isNotBlank();
    }

    @Test
    void nodeService_nodeExistsInDatabase() {
        int nodeId = nodeService.nodeId();

        var found = jdbi.withHandle(h ->
                h.createQuery("SELECT hostname FROM node WHERE id = :id")
                        .bind("id", nodeId)
                        .mapTo(String.class)
                        .findFirst());

        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(nodeService.hostname());
    }
}
