package com.libragraph.vault.core.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libragraph.vault.core.dao.TaskDao;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class TaskService {

    private static final Logger log = Logger.getLogger(TaskService.class);

    @Inject
    Jdbi jdbi;

    @Inject
    TaskRegistry taskRegistry;

    @Inject
    ObjectMapper objectMapper;

    private final ConcurrentHashMap<Integer, TaskHandle<?>> handles = new ConcurrentHashMap<>();

    public int submit(Class<? extends VaultTask> taskClass, Object input, int tenantId) {
        return submit(taskClass, input, tenantId, 128);
    }

    public int submit(Class<? extends VaultTask> taskClass, Object input, int tenantId, int priority) {
        VaultTask task = resolveTask(taskClass);
        String inputJson = serialize(input);
        String[] resources = getResources(taskClass);

        int id = jdbi.inTransaction(handle -> {
            TaskDao dao = handle.attach(TaskDao.class);
            int taskId = dao.insert(tenantId, null, task.taskType(),
                    TaskStatus.OPEN, priority, inputJson, null);
            for (String resource : resources) {
                dao.insertResourceDep(taskId, resource);
            }
            return taskId;
        });

        log.debugf("Task submitted: id=%d, type=%s, tenant=%d", (Object) id, task.taskType(), tenantId);
        return id;
    }

    public <O> TaskHandle<O> submitTracked(Class<? extends VaultTask> taskClass,
                                            Object input, int tenantId, Class<O> outputType) {
        int id = submit(taskClass, input, tenantId);
        TaskHandle<O> handle = new TaskHandle<>(id, outputType);
        handles.put(id, handle);
        return handle;
    }

    public void complete(int taskId, Object output) {
        String outputJson = serialize(output);
        jdbi.useExtension(TaskDao.class, dao ->
                dao.complete(taskId, TaskStatus.COMPLETE, outputJson));
        checkUnblock(taskId);
    }

    public void fail(int taskId, TaskError error) {
        String errorJson = serialize(error);
        jdbi.useExtension(TaskDao.class, dao ->
                dao.failTerminal(taskId, TaskStatus.ERROR, errorJson, error.retryable()));
    }

    public Optional<TaskRecord> get(int taskId) {
        return jdbi.withExtension(TaskDao.class, dao -> dao.findById(taskId));
    }

    // -- Package-private methods for TaskWorkerPool and DefaultTaskContext --

    int submitSubtask(Class<? extends VaultTask> taskClass, Object input,
                      int priority, int parentId, int tenantId) {
        VaultTask task = resolveTask(taskClass);
        String inputJson = serialize(input);
        String[] resources = getResources(taskClass);

        int id = jdbi.inTransaction(handle -> {
            TaskDao dao = handle.attach(TaskDao.class);
            int taskId = dao.insert(tenantId, parentId, task.taskType(),
                    TaskStatus.OPEN, priority, inputJson, null);
            dao.insertTaskDep(parentId, taskId);
            for (String resource : resources) {
                dao.insertResourceDep(taskId, resource);
            }
            return taskId;
        });

        log.debugf("Subtask submitted: id=%d, type=%s, parent=%d", (Object) id, task.taskType(), parentId);
        return id;
    }

    void release(int taskId, TaskOutcome outcome) {
        switch (outcome) {
            case TaskOutcome.Complete c -> {
                String outputJson = serialize(c.output());
                jdbi.useExtension(TaskDao.class, dao ->
                        dao.complete(taskId, TaskStatus.COMPLETE, outputJson));
                checkUnblock(taskId);
                completeHandle(taskId, c.output());
            }
            case TaskOutcome.Blocked b -> {
                jdbi.useExtension(TaskDao.class, dao ->
                        dao.updateStatus(taskId, TaskStatus.BLOCKED));
            }
            case TaskOutcome.Background bg -> {
                Instant expiresAt = Instant.now().plus(bg.timeout());
                jdbi.useExtension(TaskDao.class, dao ->
                        dao.setBackground(taskId, TaskStatus.BACKGROUND, expiresAt));
            }
            case TaskOutcome.Failed f -> {
                boolean retryable = f.error().retryable();
                if (retryable) {
                    jdbi.useExtension(TaskDao.class, dao ->
                            dao.returnToOpen(taskId, TaskStatus.OPEN));
                } else {
                    String errorJson = serialize(f.error());
                    jdbi.useExtension(TaskDao.class, dao ->
                            dao.failTerminal(taskId, TaskStatus.ERROR, errorJson, false));
                    completeHandleExceptionally(taskId, f.error());
                }
            }
        }
    }

    /**
     * Called by {@link TaskNotificationListener} when a task_completed NOTIFY arrives.
     * Handles cross-node TaskHandle completion only — checkUnblock is done synchronously
     * in {@link #release} by the executing node.
     */
    void onTaskCompleted(int taskId) {
        TaskHandle<?> handle = handles.get(taskId);
        if (handle == null) return;

        get(taskId).ifPresent(record -> {
            if (record.status() == TaskStatus.COMPLETE && record.output() != null) {
                try {
                    Object deserialized = objectMapper.readValue(record.output(), handle.outputType());
                    completeHandle(taskId, deserialized);
                } catch (Exception e) {
                    completeHandleExceptionally(taskId,
                            new TaskError("Failed to deserialize output", e.getClass().getName(), null, false));
                }
            }
        });
    }

    List<TaskRecord> getSubtasksByParent(int parentId) {
        return jdbi.withExtension(TaskDao.class, dao -> dao.findByParent(parentId));
    }

    Object deserializeInput(TaskRecord record) {
        if (record.input() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(record.input(), Object.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize input for task " + record.id(), e);
        }
    }

    // -- Private helpers --

    private void checkUnblock(int completedTaskId) {
        jdbi.useExtension(TaskDao.class, dao -> {
            List<Integer> blocked = dao.findBlockedByCompletedTask(completedTaskId);
            for (int blockedId : blocked) {
                int incomplete = dao.countIncompleteTaskDeps(blockedId, TaskStatus.COMPLETE);
                if (incomplete == 0) {
                    dao.updateStatus(blockedId, TaskStatus.OPEN);
                    log.debugf("Task %d unblocked (all deps complete)", blockedId);
                }
            }
        });
    }

    private void completeHandle(int taskId, Object output) {
        TaskHandle<?> handle = handles.remove(taskId);
        if (handle != null) {
            handle.complete(output);
        }
    }

    private void completeHandleExceptionally(int taskId, TaskError error) {
        TaskHandle<?> handle = handles.remove(taskId);
        if (handle != null) {
            handle.fail(new RuntimeException(error.message()));
        }
    }

    private VaultTask resolveTask(Class<? extends VaultTask> taskClass) {
        return taskRegistry.lookupByClass(taskClass)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No VaultTask registered for class: " + taskClass.getName()));
    }

    private String[] getResources(Class<? extends VaultTask> taskClass) {
        TaskIO io = taskClass.getAnnotation(TaskIO.class);
        return (io != null) ? io.resources() : new String[0];
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize: " + value, e);
        }
    }
}
