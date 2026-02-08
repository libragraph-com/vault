package com.libragraph.vault.core.task;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class TaskRegistry {

    private static final Logger log = Logger.getLogger(TaskRegistry.class);

    @Inject
    Instance<VaultTask> tasks;

    private final Map<String, VaultTask> registry = new HashMap<>();
    private final Map<Class<?>, VaultTask> byClass = new HashMap<>();

    @PostConstruct
    void init() {
        for (VaultTask task : tasks) {
            String type = task.taskType();
            VaultTask existing = registry.put(type, task);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate task type '" + type + "': " +
                                existing.getClass().getName() + " and " + task.getClass().getName());
            }
            byClass.put(task.getClass(), task);
            log.infof("Registered task type: %s → %s", type, task.getClass().getSimpleName());
        }
        log.infof("TaskRegistry initialized with %d task types", registry.size());
    }

    public Optional<VaultTask> lookup(String taskType) {
        return Optional.ofNullable(registry.get(taskType));
    }

    public Optional<VaultTask> lookupByClass(Class<? extends VaultTask> taskClass) {
        VaultTask direct = byClass.get(taskClass);
        if (direct != null) return Optional.of(direct);
        for (var entry : byClass.entrySet()) {
            if (taskClass.isAssignableFrom(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    public int size() {
        return registry.size();
    }
}
