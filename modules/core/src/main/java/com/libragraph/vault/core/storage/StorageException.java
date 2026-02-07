package com.libragraph.vault.core.storage;

/**
 * Wraps checked I/O exceptions from storage operations.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
