package com.adminmod.common.net;

/** Channel and protocol constants shared by the client and server distributions. */
public final class AdminProtocol {
    /**
     * Bumped whenever the wire format changes; both sides must agree.
     *
     * <p>Version 2 added the pairing and client-secret authentication modes: the handshake now
     * carries a build id, an authentication mode and an optional client identity, and two new
     * message types carry the pairing exchange.
     */
    public static final int PROTOCOL_VERSION = 2;

    /**
     * Identifies this build of the mod.
     *
     * <p>It supplements authentication, it never replaces it: a matching build id proves the
     * caller runs a client built against this protocol, not that the caller is authorised. The
     * server compares it against {@code remoteAdmin.allowedClientBuilds} when that list is not
     * empty.
     */
    public static final String BUILD_ID = "adminmod/1.1.0+p2";

    /** Namespaced payload channel used for every remote administration message. */
    public static final String CHANNEL = "adminmod:admin";

    /** Reject envelopes whose timestamp drifts further than this from local time. */
    public static final long MAX_CLOCK_SKEW_MILLIS = 60_000L;

    /** Nonces are remembered for this long so a replayed envelope is rejected. */
    public static final long REPLAY_WINDOW_MILLIS = 120_000L;

    /** Failed authentications allowed from one address before it is briefly locked out. */
    public static final int AUTH_FAILURE_LIMIT = 5;

    /** How long an address is locked out after too many failed authentications. */
    public static final long AUTH_LOCKOUT_MILLIS = 300_000L;

    private AdminProtocol() {
    }
}
