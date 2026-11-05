package com.adminmod.common.compress;

/** Raised when a compression operation fails for an environmental reason (not for corrupt input). */
public class CompressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CompressionException(String message) {
        super(message);
    }

    public CompressionException(String message, Throwable cause) {
        super(message, cause);
    }
}
