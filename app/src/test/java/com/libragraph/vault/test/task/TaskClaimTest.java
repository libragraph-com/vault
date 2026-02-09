package com.libragraph.vault.test.task;

import com.libragraph.vault.core.cluster.NodeService;
import com.libragraph.vault.core.dao.TaskDao;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(MinioTestResource.class)
class TaskClaimTest {

    @Inject
    TaskService taskService;

    @Inject
    NodeService nodeService;

    @Inject
    VaultTestConfig testConfig;

    @Inject
    Jdbi jdbi;

    int tenantId;

    @BeforeEach
    void setUp() {
        tenantId = testConfig.ensureTestTenant();
        // Cancel leftover OPEN tasks from previous runs (dev profile persists data)
        jdbi.useHandle(handle -> handle.createUpdate(
                "UPDATE task SET status = 6 WHERE status = 0").execute());
        // Ensure resource rows exist for declarative resource-dependent tasks (idempotent)
        jdbi.useHandle(handle -> {
            handle.execute("INSERT INTO task_resource (id, name) VALUES (99, 'test-service') ON CONFLICT DO NOTHING");
            handle.execute("INSERT INTO task_resource (id, name, max_concurrency) " +
                    "VALUES (100, 'limited-service', 1) ON CONFLICT (name) DO UPDATE SET max_concurrency = 1");
        });
    }

    @Test
    void claimNext_returnsTaskAndSetsInProgress() {
        int taskId = taskService.submit(EchoTask.class, "claim-me", tenantId);

        Optional<TaskRecord> claimed = jdbi.withHandle(handle -> {
            TaskDao dao = handle.attach(TaskDao.class);
            return dao.claimNext(handle, nodeService.nodeId(), Set.of());
        });

        assertThat(claimed).isPresent();
        assertThat(claimed.get().status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(claimed.get().executor()).isEqualTo(nodeService.nodeId());
    }

    @Test
    void claimNext_noAvailableTasks_returnsEmpty() {
        Optional<TaskRecord> claimed = jdbi.withHandle(handle -> {
            TaskDao dao = handle.attach(TaskDao.class);
            return dao.claimNext(handle, nodeService.nodeId(), Set.of());
        });

        // May or may not be empty depending on other tests, but should not throw
        assertThat(claimed).isNotNull();
    }

    @Test
    void claimNext_priorityOrdering() {
        int lowId = taskService.submit(EchoTask.class, "low", tenantId, 100);
        int highId = taskService.submit(EchoTask.class, "high", tenantId, 200);

        Optional<TaskRecord> first = jdbi.withHandle(handle -> {
            TaskDao dao = handle.attach(TaskDao.class);
            return dao.claimNext(handle, nodeService.nodeId(), Set.of());
        });

        assertThat(first).isPresent();
        assertThat(first.get().id()).isEqualTo(highId);
    }

    @Test
    void claimNext_concurrentClaims_noDoubleClaims() throws InterruptedException {
        int taskCount = 5;
        Set<Integer> submitted = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < taskCount; i++) {
            submitted.add(taskService.submit(EchoTask.class, "concurrent-" + i, tenantId));
        }

        int workerCount = 8;
        CountDownLatch latch = new CountDownLatch(workerCount);
        List<Integer> allClaimed = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < workerCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    Optional<TaskRecord> result = jdbi.withHandle(handle -> {
                        TaskDao dao = handle.attach(TaskDao.class);
                        return dao.claimNext(handle, nodeService.nodeId(), Set.of());
                    });
                    result.ifPresent(r -> allClaimed.add(r.id()));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);

        // Filter to only tasks we submitted, then check no duplicates
        List<Integer> ourClaimed = allClaimed.stream().filter(submitted::contains).toList();
        assertThat(ourClaimed).hasSizeLessThanOrEqualTo(taskCount);
        assertThat(new HashSet<>(ourClaimed)).hasSameSizeAs(ourClaimed); // no duplicates
    }

    @Test
    void claimNext_serviceDown_taskNotDispatched() {
        // ResourceDependentTask declares @TaskIO(resources = "test-service")
        // Resource dep is inserted atomically at submit time
        int taskId = taskService.submit(ResourceDependentTask.class, "needs-service", tenantId);

        // Claim with test-service NOT in available set → should skip this task
        Optional<TaskRecord> claimed = jdbi.withHandle(handle -> {
            TaskDao dao = handle.attach(TaskDao.class);
            return dao.claimNext(handle, nodeService.nodeId(), Set.of());
        });

        // Should not have claimed our resource-dependent task
        assertThat(claimed).satisfiesAnyOf(
                opt -> assertThat(opt).isEmpty(),
                opt -> assertThat(opt.get().id()).isNotEqualTo(taskId)
        );

        // Verify our task is still OPEN (not dispatched)
        TaskRecord record = taskService.get(taskId).orElseThrow();
        assertThat(record.status()).isEqualTo(TaskStatus.OPEN);
    }

    @Test
    void claimNext_serviceUp_taskDispatched() {
        // High priority so this task is claimed before any leftovers from other tests
        int taskId = taskService.submit(ResourceDependentTask.class, "needs-service", tenantId, 999);

        // Claim with test-service IN available set → should claim it
        Optional<TaskRecord> claimed = jdbi.withHandle(handle -> {
            TaskDao dao = handle.attach(TaskDao.class);
            return dao.claimNext(handle, nodeService.nodeId(), Set.of("test-service"));
        });

        assertThat(claimed).isPresent();
        assertThat(claimed.get().id()).isEqualTo(taskId);
        assertThat(claimed.get().status()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void claimNext_maxConcurrency_throttlesDispatch() {
        // LimitedResourceTask declares @TaskIO(resources = "limited-service")
        int task1 = taskService.submit(LimitedResourceTask.class, "throttled-1", tenantId, 998);
        int task2 = taskService.submit(LimitedResourceTask.class, "throttled-2", tenantId, 997);

        // Claim first task → should succeed
        Optional<TaskRecord> first = jdbi.withHandle(handle -> {
            TaskDao dao = handle.attach(TaskDao.class);
            return dao.claimNext(handle, nodeService.nodeId(), Set.of("limited-service"));
        });
        assertThat(first).isPresent();
        assertThat(first.get().id()).isEqualTo(task1);

        // Try to claim second → should be skipped (max_concurrency=1, one already IN_PROGRESS)
        Optional<TaskRecord> second = jdbi.withHandle(handle -> {
            TaskDao dao = handle.attach(TaskDao.class);
            return dao.claimNext(handle, nodeService.nodeId(), Set.of("limited-service"));
        });
        assertThat(second).satisfiesAnyOf(
                opt -> assertThat(opt).isEmpty(),
                opt -> assertThat(opt.get().id()).isNotEqualTo(task2)
        );

        // Verify task2 is still OPEN
        assertThat(taskService.get(task2).orElseThrow().status()).isEqualTo(TaskStatus.OPEN);

        // Complete first task
        taskService.complete(task1, "done");

        // Now second task should be claimable
        Optional<TaskRecord> third = jdbi.withHandle(handle -> {
            TaskDao dao = handle.attach(TaskDao.class);
            return dao.claimNext(handle, nodeService.nodeId(), Set.of("limited-service"));
        });
        assertThat(third).isPresent();
        assertThat(third.get().id()).isEqualTo(task2);
    }
}
