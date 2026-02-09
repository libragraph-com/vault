package com.libragraph.vault.core.task;

import java.util.List;
import java.util.Optional;

public interface TaskContext {

    int taskId();

    int tenantId();

    String taskType();

    int createSubtask(Class<? extends VaultTask> taskClass, Object input);

    int createSubtask(Class<? extends VaultTask> taskClass, Object input, int priority);

    <O> O getSubtaskResult(int subtaskId, Class<O> outputType);

    Optional<TaskError> getSubtaskError(int subtaskId);

    <O> List<O> getCompletedSubtasks(Class<O> outputType);
}
