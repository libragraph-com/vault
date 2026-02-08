package com.libragraph.vault.test.task;

import com.libragraph.vault.core.dao.TaskDao;
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

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class TaskDependencyTest {

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
    void blockParent_setsBlocked() {
        // Submit parent, then create a subtask dependency
        int parentId = taskService.submit(EchoTask.class, "parent", tenantId);
        int subtaskId = taskService.submit(EchoTask.class, "subtask", tenantId);

        jdbi.useExtension(TaskDao.class, dao -> {
            dao.insertTaskDep(parentId, subtaskId);
            dao.updateStatus(parentId, TaskStatus.BLOCKED);
        });

        var parent = taskService.get(parentId);
        assertThat(parent).isPresent();
        assertThat(parent.get().status()).isEqualTo(TaskStatus.BLOCKED);
    }

    @Test
    void completeSubtask_unblocksParent() {
        int parentId = taskService.submit(EchoTask.class, "parent", tenantId);
        int subtaskId = taskService.submit(EchoTask.class, "subtask", tenantId);

        jdbi.useExtension(TaskDao.class, dao -> {
            dao.insertTaskDep(parentId, subtaskId);
            dao.updateStatus(parentId, TaskStatus.BLOCKED);
        });

        // Complete the subtask — should trigger unblock check
        taskService.complete(subtaskId, "done");

        var parent = taskService.get(parentId);
        assertThat(parent).isPresent();
        assertThat(parent.get().status()).isEqualTo(TaskStatus.OPEN);
    }

    @Test
    void multipleSubtasks_allMustComplete() {
        int parentId = taskService.submit(EchoTask.class, "parent", tenantId);
        int sub1 = taskService.submit(EchoTask.class, "sub1", tenantId);
        int sub2 = taskService.submit(EchoTask.class, "sub2", tenantId);

        jdbi.useExtension(TaskDao.class, dao -> {
            dao.insertTaskDep(parentId, sub1);
            dao.insertTaskDep(parentId, sub2);
            dao.updateStatus(parentId, TaskStatus.BLOCKED);
        });

        // Complete only sub1 — parent should stay BLOCKED
        taskService.complete(sub1, "done1");
        var parentAfterOne = taskService.get(parentId);
        assertThat(parentAfterOne.get().status()).isEqualTo(TaskStatus.BLOCKED);

        // Complete sub2 — parent should be OPEN
        taskService.complete(sub2, "done2");
        var parentAfterAll = taskService.get(parentId);
        assertThat(parentAfterAll.get().status()).isEqualTo(TaskStatus.OPEN);
    }
}
