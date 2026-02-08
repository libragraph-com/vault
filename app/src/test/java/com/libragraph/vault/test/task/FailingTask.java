package com.libragraph.vault.test.task;

import com.libragraph.vault.core.task.TaskContext;
import com.libragraph.vault.core.task.TaskError;
import com.libragraph.vault.core.task.TaskIO;
import com.libragraph.vault.core.task.TaskOutcome;
import com.libragraph.vault.core.task.VaultTask;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@TaskIO(input = String.class, output = String.class)
public class FailingTask implements VaultTask {

    @Override
    public String taskType() {
        return "test.failing";
    }

    @Override
    public TaskOutcome onStart(Object input, TaskContext ctx) {
        return TaskOutcome.fail(new TaskError(
                "Intentional test failure: " + input,
                "java.lang.RuntimeException",
                null,
                false
        ));
    }
}
