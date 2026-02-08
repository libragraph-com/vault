package com.libragraph.vault.test.task;

import com.libragraph.vault.core.task.TaskContext;
import com.libragraph.vault.core.task.TaskIO;
import com.libragraph.vault.core.task.TaskOutcome;
import com.libragraph.vault.core.task.VaultTask;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
@TaskIO(input = String.class, output = String.class)
public class BlockingTask implements VaultTask {

    @Override
    public String taskType() {
        return "test.blocking";
    }

    @Override
    public TaskOutcome onStart(Object input, TaskContext ctx) {
        int subtaskId = ctx.createSubtask(EchoTask.class, input);
        return TaskOutcome.blocked(List.of(subtaskId));
    }

    @Override
    public TaskOutcome onResume(Object input, TaskContext ctx) {
        List<String> results = ctx.getCompletedSubtasks(String.class);
        return TaskOutcome.complete("resumed with: " + String.join(", ", results));
    }
}
