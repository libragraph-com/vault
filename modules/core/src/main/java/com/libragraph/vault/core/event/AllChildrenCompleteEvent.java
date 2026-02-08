package com.libragraph.vault.core.event;

import java.util.List;

public record AllChildrenCompleteEvent(FanInContext fanIn, List<ChildResult> results) {}
