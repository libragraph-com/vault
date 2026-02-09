package com.libragraph.vault.core.task;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

class DefaultTaskContext implements TaskContext {

    private final int taskId;
    private final int tenantId;
    private final String taskType;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    DefaultTaskContext(int taskId, int tenantId, String taskType,
                       TaskService taskService, ObjectMapper objectMapper) {
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.taskType = taskType;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public int taskId() {
        return taskId;
    }

    @Override
    public int tenantId() {
        return tenantId;
    }

    @Override
    public String taskType() {
        return taskType;
    }

    @Override
    public int createSubtask(Class<? extends VaultTask> taskClass, Object input) {
        return createSubtask(taskClass, input, 128);
    }

    @Override
    public int createSubtask(Class<? extends VaultTask> taskClass, Object input, int priority) {
        return taskService.submitSubtask(taskClass, input, priority, taskId, tenantId);
    }

    @Override
    public <O> O getSubtaskResult(int subtaskId, Class<O> outputType) {
        TaskRecord record = taskService.get(subtaskId)
                .orElseThrow(() -> new IllegalArgumentException("Subtask not found: " + subtaskId));
        if (record.status() != TaskStatus.COMPLETE) {
            throw new IllegalStateException("Subtask " + subtaskId + " is not complete: " + record.status());
        }
        return deserialize(record.output(), outputType);
    }

    @Override
    public Optional<TaskError> getSubtaskError(int subtaskId) {
        TaskRecord record = taskService.get(subtaskId)
                .orElseThrow(() -> new IllegalArgumentException("Subtask not found: " + subtaskId));
        if (record.status() != TaskStatus.ERROR && record.status() != TaskStatus.DEAD) {
            return Optional.empty();
        }
        return Optional.of(deserialize(record.output(), TaskError.class));
    }

    @Override
    public <O> List<O> getCompletedSubtasks(Class<O> outputType) {
        return taskService.getSubtasksByParent(taskId).stream()
                .filter(r -> r.status() == TaskStatus.COMPLETE)
                .map(r -> deserialize(r.output(), outputType))
                .toList();
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize " + type.getSimpleName() + ": " + json, e);
        }
    }
}
