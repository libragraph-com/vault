package com.libragraph.vault.core.event;

import com.libragraph.vault.util.BlobRef;

public record ManifestBuiltEvent(BlobRef containerRef) {}
