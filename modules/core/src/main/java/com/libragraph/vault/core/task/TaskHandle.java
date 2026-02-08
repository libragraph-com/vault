package com.libragraph.vault.core.task;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Node-local handle for tracking a submitted task's completion.
 * <p>
 * In a multi-node cluster, the task may execute on a different node. Completion
 * is delivered via PostgreSQL LISTEN/NOTIFY, which is best-effort — if the
 * notification connection drops, the handle will not be completed until it
 * reconnects. Callers should use {@link #await(Duration)} with a timeout and
 * fall back to polling {@link TaskService#get(int)} on expiry.
 */
public class TaskHandle<O> {

    private final int taskId;
    private final CompletableFuture<Object> future = new CompletableFuture<>();
    private final Class<O> outputType;

    TaskHandle(int taskId, Class<O> outputType) {
        this.taskId = taskId;
        this.outputType = outputType;
    }

    public int taskId() {
        return taskId;
    }

    public boolean isDone() {
        return future.isDone();
    }

    @SuppressWarnings("unchecked")
    public O await() throws InterruptedException, ExecutionException {
        return (O) future.get();
    }

    @SuppressWarnings("unchecked")
    public O await(Duration timeout) throws InterruptedException, ExecutionException, TimeoutException {
        return (O) future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public Class<O> outputType() {
        return outputType;
    }

    void complete(Object result) {
        future.complete(result);
    }

    void fail(Throwable cause) {
        future.completeExceptionally(cause);
    }
}
