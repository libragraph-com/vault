package com.libragraph.vault.core.event;

import com.libragraph.vault.formats.api.ContainerChild;

public record ChildDiscoveredEvent(
        ContainerChild child,
        FanInContext fanIn,
        String tenantId,
        int dbTenantId,
        int taskId
) {}
