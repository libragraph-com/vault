package com.libragraph.vault.core.service;

/**
 * Contract for services with managed lifecycle and dependency ordering.
 * State transitions fire {@link ServiceStateChangedEvent} via CDI.
 */
public interface ManagedService {

    enum State { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

    String serviceId();

    State state();

    void start() throws Exception;

    void stop() throws Exception;

    void fail(Throwable cause);

    default boolean isRunning() {
        return state() == State.RUNNING;
    }
}
