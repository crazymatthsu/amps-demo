package com.demo.amps.cache;

/**
 * A store operation failed against AMPS.
 *
 * <p>Unchecked because the store is driven through {@link java.util.Map}
 * methods, which have nowhere to declare a checked exception. The cause is
 * always the underlying {@code AMPSException} (or a timeout waiting for an
 * acknowledgement), so nothing is lost -- only relocated.
 */
public class CacheStoreException extends RuntimeException {

    public CacheStoreException(String message) {
        super(message);
    }

    public CacheStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
