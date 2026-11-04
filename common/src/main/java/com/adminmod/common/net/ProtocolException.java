package com.adminmod.common.net;

/** Raised when a remote payload is malformed or exceeds a hard limit. */
public class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }
}
