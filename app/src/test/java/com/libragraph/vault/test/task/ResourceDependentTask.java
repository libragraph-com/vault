package com.libragraph.vault.test.task;

import com.libragraph.vault.core.task.TaskContext;
import com.libragraph.vault.core.task.TaskIO;
import com.libragraph.vault.core.task.TaskOutcome;
import com.libragraph.vault.core.task.VaultTask;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@TaskIO(input = String.class, output = String.class, resources = "test-service")
public class ResourceDependentTask implements VaultTask {

    @Override
    public String taskType() {
        return "test.resource-dependent";
    }

    @Override
    public TaskOutcome onStart(Object input, TaskContext ctx) {
        return TaskOutcome.complete("done: " + input);
    }
}
