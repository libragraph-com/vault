package com.libragraph.vault.core.event;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class FanInContext {

    private final UUID contextId;
    private final AtomicInteger remaining;
    private final FanInContext parent;
    private final ConcurrentLinkedQueue<ChildResult> results;

    public FanInContext(int expectedChildren) {
        this(expectedChildren, null);
    }

    public FanInContext(int expectedChildren, FanInContext parent) {
        this.contextId = UUID.randomUUID();
        this.remaining = new AtomicInteger(expectedChildren);
        this.parent = parent;
        this.results = new ConcurrentLinkedQueue<>();
    }

    public UUID contextId() {
        return contextId;
    }

    public FanInContext parent() {
        return parent;
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
