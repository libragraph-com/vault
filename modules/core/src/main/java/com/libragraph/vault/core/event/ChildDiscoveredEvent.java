package com.libragraph.vault.core.event;

public record ChildDiscoveredEvent(ChildResult child, FanInContext fanIn) {}
