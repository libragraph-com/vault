package com.libragraph.vault.core.event;

import com.libragraph.vault.util.BlobRef;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class FanInContext {

    private final UUID contextId;
    private final AtomicInteger remaining;
    private final FanInContext parent;
    private final ConcurrentLinkedQueue<ChildResult> results;

    private final BlobRef containerRef;
    private final String containerPath;
    private final String tenantId;
    private final int dbTenantId;
    private final int taskId;

    public FanInContext(int expectedChildren, FanInContext parent,
                        BlobRef containerRef, String containerPath,
                        String tenantId, int dbTenantId, int taskId) {
        this.contextId = UUID.randomUUID();
        this.remaining = new AtomicInteger(expectedChildren);
        this.parent = parent;
        this.results = new ConcurrentLinkedQueue<>();
        this.containerRef = containerRef;
        this.containerPath = containerPath;
        this.tenantId = tenantId;
        this.dbTenantId = dbTenantId;
        this.taskId = taskId;
    }

    public UUID contextId() {
        return contextId;
    }

    public FanInContext parent() {
        return parent;
    }

    public BlobRef containerRef() {
        return containerRef;
    }

    public String containerPath() {
        return containerPath;
    }

    public String tenantId() {
        return tenantId;
    }

    public int dbTenantId() {
        return dbTenantId;
    }

    public int taskId() {
        return taskId;
    }

    public void addResult(ChildResult result) {
        results.add(result);
    }

    public List<ChildResult> results() {
        return List.copyOf(results);
    }

    /**
     * Decrements remaining count. Returns true when the last child completes.
     */
    public boolean decrementAndCheck() {
        return remaining.decrementAndGet() == 0;
    }

    public int remaining() {
        return remaining.get();
    }
}
