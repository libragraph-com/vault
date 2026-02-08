package com.libragraph.vault.core.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libragraph.vault.core.cluster.NodeService;
import com.libragraph.vault.core.dao.TaskDao;
import com.libragraph.vault.core.db.DatabaseService;
import com.libragraph.vault.core.service.AbstractManagedService;
import com.libragraph.vault.core.service.DependsOn;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
@Startup
@DependsOn(DatabaseService.class)
@DependsOn(NodeService.class)
public class TaskWorkerPool extends AbstractManagedService {

    @Inject
    Jdbi jdbi;

    @Inject
    NodeService nodeService;

    @Inject
    TaskService taskService;

    @Inject
    TaskRegistry taskRegistry;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AgroalDataSource dataSource;

    @Inject
    ResourceAvailabilityTracker resourceTracker;

    @ConfigProperty(name = "vault.tasks.worker-count", defaultValue = "4")
    int workerCount;

    @ConfigProperty(name = "vault.tasks.poll-interval-ms", defaultValue = "1000")
    long pollIntervalMs;

    private TaskNotificationListener notificationListener;
    private final List<Thread> workers = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> availableResources = ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    @Override
    public String serviceId() {
        return "task-worker-pool";
    }

    @Override
    protected void doStart() {
        running = true;

        notificationListener = new TaskNotificationListener(dataSource);
        notificationListener.start(taskService::onTaskCompleted);

        for (int i = 0; i < workerCount; i++) {
            Thread worker = Thread.ofVirtual()
                    .name("task-worker-" + i)
                    .start(this::workerLoop);
            workers.add(worker);
        }

        resourceTracker.setWorkerPool(this);

        log.infof("TaskWorkerPool started with %d workers", workerCount);
    }

    @Override
    protected void doStop() {
        running = false;
        resourceTracker.clearWorkerPool();

        if (notificationListener != null) {
            notificationListener.stop();
        }

        for (Thread worker : workers) {
            worker.interrupt();
        }
        workers.clear();

        log.info("TaskWorkerPool stopped");
    }

    void addResource(String serviceId) {
        availableResources.add(serviceId);
    }

    void removeResource(String serviceId) {
        availableResources.remove(serviceId);
    }

    private void workerLoop() {
        while (running) {
            try {
                Optional<TaskRecord> claimed = claimTask();
                if (claimed.isPresent()) {
                    executeTask(claimed.get());
                } else {
                    notificationListener.awaitWork(pollIntervalMs, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running) {
                    log.error("Unexpected error in worker loop", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private Optional<TaskRecord> claimTask() {
        int nodeId = nodeService.nodeId();
        return jdbi.withHandle(handle -> {
            TaskDao dao = handle.attach(TaskDao.class);
            return dao.claimNext(handle, nodeId, availableResources);
        });
    }

    private void executeTask(TaskRecord record) {
        Optional<VaultTask> optTask = taskRegistry.lookup(record.type());
        if (optTask.isEmpty()) {
            log.errorf("No handler for task type '%s' (task %d)", record.type(), record.id());
            taskService.fail(record.id(), new TaskError(
                    "No handler for task type: " + record.type(), null, null, false));
            return;
        }

        VaultTask task = optTask.get();
        DefaultTaskContext ctx = new DefaultTaskContext(
                record.id(), record.tenantId(), record.type(), taskService, objectMapper);

        try {
            Object input = taskService.deserializeInput(record);

            TaskOutcome outcome;
            // Determine dispatch phase
            List<TaskRecord> subtasks = taskService.getSubtasksByParent(record.id());
            boolean hasFailedSubtask = subtasks.stream()
                    .anyMatch(s -> s.status() == TaskStatus.ERROR || s.status() == TaskStatus.DEAD);
            boolean hasCompletedSubtasks = subtasks.stream()
                    .anyMatch(s -> s.status() == TaskStatus.COMPLETE);

            if (hasFailedSubtask) {
                TaskRecord failed = subtasks.stream()
                        .filter(s -> s.status() == TaskStatus.ERROR || s.status() == TaskStatus.DEAD)
                        .findFirst().get();
                TaskError error = failed.output() != null
                        ? objectMapper.readValue(failed.output(), TaskError.class)
                        : new TaskError("Subtask " + failed.id() + " failed", null, null, false);
                outcome = task.onError(input, ctx, error);
            } else if (hasCompletedSubtasks) {
                outcome = task.onResume(input, ctx);
            } else {
                outcome = task.onStart(input, ctx);
            }

            taskService.release(record.id(), outcome);
        } catch (Exception e) {
            log.errorf(e, "Task %d (type=%s) threw exception", record.id(), record.type());
            taskService.release(record.id(), TaskOutcome.fail(e));
        }
    }

    @PostConstruct
    void init() {
        try {
            start();
        } catch (Exception e) {
            throw new RuntimeException("TaskWorkerPool failed to start", e);
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            stop();
        } catch (Exception e) {
            log.warn("Error stopping TaskWorkerPool", e);
        }
    }
}
