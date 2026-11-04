package com.adminmod.common.security;

/** Raised when a session cannot be established or a frame fails authentication. */
public class SessionException extends Exception {
    public SessionException(String message) {
        super(message);
    }
}
