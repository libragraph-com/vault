package com.libragraph.vault.test.task;

import com.libragraph.vault.core.dao.TaskDao;
import com.libragraph.vault.core.task.StaleTaskRecovery;
import com.libragraph.vault.core.task.TaskService;
import com.libragraph.vault.core.task.TaskStatus;
import com.libragraph.vault.core.test.VaultTestConfig;
import com.libragraph.vault.test.MinioTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class StaleTaskRecoveryTest {

    @Inject
    TaskService taskService;

    @Inject
    StaleTaskRecovery staleTaskRecovery;

    @Inject
    VaultTestConfig testConfig;

    @Inject
    Jdbi jdbi;

    int tenantId;

    @BeforeEach
    void setUp() {
        tenantId = testConfig.ensureTestTenant();
    }

    @Test
    void staleInProgress_returnedToOpen() {
        int taskId = taskService.submit(EchoTask.class, "stale", tenantId);

        // Manually set to IN_PROGRESS with old claimed_at
        jdbi.useExtension(TaskDao.class, dao -> {
            dao.updateStatus(taskId, TaskStatus.IN_PROGRESS);
        });
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE task SET claimed_at = :claimedAt, executor = 1 WHERE id = :id")
                .bind("claimedAt", Instant.now().minus(10, ChronoUnit.MINUTES))
                .bind("id", taskId)
                .execute());

        staleTaskRecovery.sweep();

        var record = taskService.get(taskId);
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(TaskStatus.OPEN);
    }

    @Test
    void staleBackground_markedDead() {
        int taskId = taskService.submit(EchoTask.class, "bg-stale", tenantId);

        // Manually set to BACKGROUND with expired expires_at
        jdbi.useExtension(TaskDao.class, dao ->
                dao.setBackground(taskId, TaskStatus.BACKGROUND,
                        Instant.now().minus(1, ChronoUnit.HOURS)));

        staleTaskRecovery.sweep();

        var record = taskService.get(taskId);
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(TaskStatus.DEAD);
    }
}
