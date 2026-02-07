package com.libragraph.vault.core.service;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Declares that a {@link ManagedService} depends on another service being
 * {@link ManagedService.State#RUNNING} before it can start. When the dependency
 * transitions to {@link ManagedService.State#FAILED}, the dependent cascades to FAILED.
 */
@Target(TYPE)
@Retention(RUNTIME)
@Repeatable(DependsOn.List.class)
public @interface DependsOn {

    Class<? extends ManagedService> value();

    @Target(TYPE)
    @Retention(RUNTIME)
    @interface List {
        DependsOn[] value();
    }
}
