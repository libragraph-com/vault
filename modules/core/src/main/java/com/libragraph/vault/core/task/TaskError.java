package com.libragraph.vault.core.task;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

public record TaskError(
        String message,
        String exceptionType,
        String stackTrace,
        boolean retryable
) {
    public static TaskError from(Throwable t) {
        var sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));

        boolean retryable = t instanceof IOException
                || t instanceof TimeoutException
                || t instanceof SocketTimeoutException;

        return new TaskError(
                t.getMessage(),
                t.getClass().getName(),
                sw.toString(),
                retryable
        );
    }
}
