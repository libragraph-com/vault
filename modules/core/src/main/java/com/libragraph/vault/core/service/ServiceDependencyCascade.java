package com.libragraph.vault.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Observes {@link ServiceStateChangedEvent} and cascades failures to dependent services.
 * <p>
 * This is a separate bean (not on {@link AbstractManagedService}) to avoid circular
 * bean creation when CDI delivers events during {@code @PostConstruct}.
 */
@ApplicationScoped
public class ServiceDependencyCascade {

    private static final Logger log = Logger.getLogger(ServiceDependencyCascade.class);

    @Inject
    Instance<ManagedService> allServices;

    void onServiceFailed(@Observes ServiceStateChangedEvent event) {
        if (event.newState() != ManagedService.State.FAILED) {
            return;
        }

        for (ManagedService svc : allServices) {
            if (svc instanceof AbstractManagedService managed) {
                for (Class<? extends ManagedService> dep : managed.getDependencies()) {
                    if (matchesServiceId(event.serviceId(), dep)) {
                        log.warnf("Dependency '%s' failed — cascading failure to '%s'",
                                event.serviceId(), svc.serviceId());
                        svc.fail(new RuntimeException(
                                "Dependency '" + event.serviceId() + "' failed"));
                    }
                }
            }
        }
    }

    private boolean matchesServiceId(String serviceId, Class<? extends ManagedService> depClass) {
        for (ManagedService svc : allServices) {
            if (depClass.isInstance(svc) && svc.serviceId().equals(serviceId)) {
                return true;
            }
        }
        return false;
    }
}
