package com.libragraph.vault.core.event;

import com.libragraph.vault.util.buffer.BinaryData;

public record IngestFileEvent(
        int taskId,
        String tenantId,
        int dbTenantId,
        BinaryData buffer,
        String filename,
        FanInContext fanIn,
        boolean bonus
) {
    public IngestFileEvent(int taskId, String tenantId, int dbTenantId,
                           BinaryData buffer, String filename, FanInContext fanIn) {
        this(taskId, tenantId, dbTenantId, buffer, filename, fanIn, false);
    }
}
