package com.libragraph.vault.core.event;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class EventExecutorProducer {

    private ExecutorService executor;

    @Produces
    @ApplicationScoped
    @Named("eventExecutor")
    public ExecutorService eventExecutor() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        return executor;
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) executor.shutdown();
    }
}
