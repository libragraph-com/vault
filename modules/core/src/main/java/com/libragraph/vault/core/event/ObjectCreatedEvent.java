package com.libragraph.vault.core.event;

import com.libragraph.vault.formats.api.Handler;
import com.libragraph.vault.util.BlobRef;
import com.libragraph.vault.util.buffer.BinaryData;

public record ObjectCreatedEvent(
        BlobRef ref,
        long blobId,
        String mimeType,
        Handler handler,
        BinaryData buffer,
        String tenantId,
        int dbTenantId,
        int taskId
) {}
