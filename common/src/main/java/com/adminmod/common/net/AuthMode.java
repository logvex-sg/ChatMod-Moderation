package com.adminmod.common.net;

/**
 * How the client proves its identity in the handshake.
 *
 * <p>The server advertises which modes it accepts. A client picks one and reports it in the
 * handshake so the server can reject a mode it does not allow before doing any key work; the mode
 * is bound into the session key derivation, so a client cannot switch modes mid-session.
 */
public enum AuthMode {
    /**
     * Shared operator password. The password is mixed with both nonces to derive the session key,
     * and the first frame that decrypts proves knowledge of it.
     */
    PASSWORD(0),
    /**
     * Paired client. The client holds a random secret issued during pairing; that secret is mixed
     * with both nonces instead of the password. A pairing key is used once to enrol and is not a
     * long-lived credential.
     */
    PAIRING_KEY(1),
    /**
     * Paired client reconnecting. The stored client secret is used directly, without consuming a
     * one-time pairing key.
     */
    CLIENT_SECRET(2);

    private final int id;

    AuthMode(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static AuthMode byId(int id) {
        for (AuthMode mode : values()) {
            if (mode.id == id) {
                return mode;
            }
        }
        throw new ProtocolException("unknown auth mode " + id);
    }
}
