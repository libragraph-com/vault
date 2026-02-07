package com.libragraph.vault.core.service;

import java.time.Instant;

/**
 * Fired via CDI whenever a {@link ManagedService} transitions between states.
 */
public record ServiceStateChangedEvent(
        String serviceId,
        ManagedService.State oldState,
        ManagedService.State newState,
        Instant timestamp
) {}
