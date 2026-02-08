package com.libragraph.vault.core.task;

import java.time.Duration;
import java.util.List;

public sealed interface TaskOutcome {

    record Complete(Object output) implements TaskOutcome {}

    record Blocked(List<Integer> subtaskIds) implements TaskOutcome {}

    record Background(String reason, Duration timeout) implements TaskOutcome {}

    record Failed(TaskError error) implements TaskOutcome {}

    static TaskOutcome complete(Object output) {
        return new Complete(output);
    }

    static TaskOutcome blocked(List<Integer> subtaskIds) {
        return new Blocked(List.copyOf(subtaskIds));
    }

    static TaskOutcome background(String reason, Duration timeout) {
        return new Background(reason, timeout);
    }

    static TaskOutcome fail(TaskError error) {
        return new Failed(error);
    }

    static TaskOutcome fail(Throwable t) {
        return new Failed(TaskError.from(t));
    }
}
