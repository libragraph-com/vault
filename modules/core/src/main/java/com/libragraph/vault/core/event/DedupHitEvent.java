package com.libragraph.vault.core.event;

import com.libragraph.vault.util.BlobRef;

public record DedupHitEvent(BlobRef ref) {}
