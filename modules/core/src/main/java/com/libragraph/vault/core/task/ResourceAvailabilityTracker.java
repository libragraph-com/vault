package com.libragraph.vault.core.task;

import com.libragraph.vault.core.service.ServiceStateChangedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Observes {@link ServiceStateChangedEvent} and updates the worker pool's
 * available resources set. Separate bean to avoid circular init
 * (same pattern as {@link com.libragraph.vault.core.service.ServiceDependencyCascade}).
 * <p>
 * The worker pool registers itself via {@link #setWorkerPool(TaskWorkerPool)}
 * after it has started, avoiding CDI circular creation.
 */
@ApplicationScoped
public class ResourceAvailabilityTracker {

    private static final Logger log = Logger.getLogger(ResourceAvailabilityTracker.class);

    private final AtomicReference<TaskWorkerPool> poolRef = new AtomicReference<>();

    void setWorkerPool(TaskWorkerPool pool) {
        poolRef.set(pool);
    }

    void clearWorkerPool() {
        poolRef.set(null);
    }

    void onServiceStateChanged(@Observes ServiceStateChangedEvent event) {
        TaskWorkerPool pool = poolRef.get();
        if (pool == null) return;

        switch (event.newState()) {
            case RUNNING -> {
                pool.addResource(event.serviceId());
                log.debugf("Resource available: %s", event.serviceId());
            }
            case FAILED, STOPPED -> {
                pool.removeResource(event.serviceId());
                log.debugf("Resource unavailable: %s", event.serviceId());
            }
            default -> {} // STARTING, STOPPING — no action
        }
    }
}
