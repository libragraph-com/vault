package com.libragraph.vault.core.task;

/**
 * A unit of work that can be scheduled, claimed, and executed by the task system.
 * <p>
 * Implementations must be {@code @ApplicationScoped} CDI beans and should declare
 * their input/output types via {@link TaskIO} for documentation and future validation.
 */
public interface VaultTask {

    String taskType();

    TaskOutcome onStart(Object input, TaskContext ctx);

    default TaskOutcome onResume(Object input, TaskContext ctx) {
        throw new UnsupportedOperationException(
                "Task type '" + taskType() + "' does not support resume");
    }

    default TaskOutcome onError(Object input, TaskContext ctx, TaskError error) {
        return TaskOutcome.fail(error);
    }
}
