package com.libragraph.vault.test.task;

import com.libragraph.vault.core.dao.TaskDao;
import com.libragraph.vault.core.task.TaskError;
import com.libragraph.vault.core.task.TaskRecord;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class TaskServiceTest {

    @Inject
    TaskService taskService;

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
    void submitTask_createsOpenRow() {
        int taskId = taskService.submit(EchoTask.class, "hello", tenantId);

        Optional<TaskRecord> record = taskService.get(taskId);
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(TaskStatus.OPEN);
        assertThat(record.get().type()).isEqualTo("test.echo");
        assertThat(record.get().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void submitTracked_returnsValidHandle() {
        var handle = taskService.submitTracked(EchoTask.class, "tracked", tenantId, String.class);

        assertThat(handle.taskId()).isPositive();
        assertThat(handle.isDone()).isFalse();
    }

    @Test
    void completeTask_setsStatusAndOutput() {
        int taskId = taskService.submit(EchoTask.class, "complete-me", tenantId);

        taskService.complete(taskId, "done!");

        Optional<TaskRecord> record = taskService.get(taskId);
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(TaskStatus.COMPLETE);
        assertThat(record.get().output()).contains("done!");
        assertThat(record.get().completedAt()).isNotNull();
    }

    @Test
    void failTask_setsStatusAndError() {
        int taskId = taskService.submit(EchoTask.class, "fail-me", tenantId);

        TaskError error = new TaskError("test error", "RuntimeException", null, false);
        taskService.fail(taskId, error);

        Optional<TaskRecord> record = taskService.get(taskId);
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(TaskStatus.ERROR);
        assertThat(record.get().output()).contains("test error");
    }

    @Test
    void getTask_returnsCorrectRecord() {
        int taskId = taskService.submit(EchoTask.class, "get-me", tenantId);

        Optional<TaskRecord> record = taskService.get(taskId);
        assertThat(record).isPresent();
        assertThat(record.get().id()).isEqualTo(taskId);
    }

    @Test
    void getTask_nonExistent_returnsEmpty() {
        Optional<TaskRecord> record = taskService.get(999999);
        assertThat(record).isEmpty();
    }
}
