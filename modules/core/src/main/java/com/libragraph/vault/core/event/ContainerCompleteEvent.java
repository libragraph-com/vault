package com.libragraph.vault.core.event;

import com.libragraph.vault.util.BlobRef;

public record ContainerCompleteEvent(BlobRef containerRef, int childCount) {}
