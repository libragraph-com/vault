package com.libragraph.vault.core.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

/**
 * Base class for {@link ManagedService} implementations. Provides:
 * <ul>
 *   <li>Thread-safe state machine via {@link AtomicReference}</li>
 *   <li>CDI event firing on every state transition</li>
 *   <li>{@code @DependsOn} validation before start</li>
 * </ul>
 * Failure cascade is handled by {@link ServiceDependencyCascade} to avoid
 * circular bean creation during CDI event delivery.
 * <p>
 * Subclasses implement {@link #doStart()} and {@link #doStop()}.
 */
public abstract class AbstractManagedService implements ManagedService {

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);

    @Inject
    Event<ServiceStateChangedEvent> stateEvent;

    @Inject
    Instance<ManagedService> allServices;

    protected final Logger log = Logger.getLogger(getClass());

    // -- template methods for subclasses --

    protected abstract void doStart() throws Exception;

    protected abstract void doStop() throws Exception;

    // -- ManagedService contract --

    @Override
    public State state() {
        return state.get();
    }

    @Override
    public void start() throws Exception {
        if (state.get() == State.RUNNING) {
            return; // idempotent
        }

        verifyDependencies();

        transition(State.STARTING);
        try {
            doStart();
            transition(State.RUNNING);
        } catch (Exception e) {
            fail(e);
            throw e;
        }
    }

    @Override
    public void stop() throws Exception {
        if (state.get() == State.STOPPED) {
            return; // idempotent
        }

        transition(State.STOPPING);
        try {
            doStop();
            transition(State.STOPPED);
        } catch (Exception e) {
            fail(e);
            throw e;
        }
    }

    @Override
    public void fail(Throwable cause) {
        State old = state.get();
        if (old == State.FAILED) {
            return; // already failed
        }
        log.errorf("Service '%s' failed (was %s): %s", serviceId(), old, cause.getMessage());
        transition(State.FAILED);
    }

    /**
     * Directly sets state without firing events or lifecycle callbacks.
     * Intended for test isolation — allows restoring state after destructive tests.
     */
    public void forceState(State newState) {
        state.set(newState);
    }

    /** Returns the {@code @DependsOn} classes declared on this service. */
    public List<Class<? extends ManagedService>> getDependencies() {
        List<Class<? extends ManagedService>> deps = new ArrayList<>();
        DependsOn[] annotations = getClass().getAnnotationsByType(DependsOn.class);
        for (DependsOn d : annotations) {
            deps.add(d.value());
        }
        return deps;
    }

    // -- internals --

    private void transition(State newState) {
        State old = state.getAndSet(newState);
        log.infof("Service '%s': %s -> %s", serviceId(), old, newState);
        stateEvent.fire(new ServiceStateChangedEvent(
                serviceId(), old, newState, Instant.now()));
    }

    private void verifyDependencies() {
        for (Class<? extends ManagedService> dep : getDependencies()) {
            for (ManagedService svc : allServices) {
                if (dep.isInstance(svc) && !svc.isRunning()) {
                    throw new IllegalStateException(
                            "Cannot start '" + serviceId() + "': dependency '"
                                    + svc.serviceId() + "' is " + svc.state());
                }
            }
        }
    }
}
