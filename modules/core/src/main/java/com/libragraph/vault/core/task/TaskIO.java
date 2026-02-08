package com.libragraph.vault.core.task;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the input/output types and resource dependencies for a {@link VaultTask}.
 * <p>
 * Resource dependencies are declared at compile time and automatically registered
 * when tasks are submitted. A task will not be dispatched until all its required
 * resources are available and under their concurrency limits.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TaskIO {
    Class<?> input();
    Class<?> output();

    /** Resource names this task requires (must match {@code task_resource.name} rows). */
    String[] resources() default {};
}
