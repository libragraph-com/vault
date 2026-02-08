package com.libragraph.vault.core.event;

import com.libragraph.vault.util.BlobRef;

import java.nio.file.Path;

public record IngestFileEvent(int taskId, BlobRef containerRef, Path path, FanInContext fanIn) {}
