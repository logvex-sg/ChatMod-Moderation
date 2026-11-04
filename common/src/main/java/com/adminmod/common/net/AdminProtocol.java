package com.adminmod.common.net;

/** Channel and protocol constants shared by the client and server distributions. */
public final class AdminProtocol {
    /** Bumped whenever the wire format changes; both sides must agree. */
    public static final int PROTOCOL_VERSION = 1;

    /** Namespaced payload channel used for every remote administration message. */
    public static final String CHANNEL = "adminmod:admin";

    /** Reject envelopes whose timestamp drifts further than this from local time. */
    public static final long MAX_CLOCK_SKEW_MILLIS = 60_000L;

    /** Nonces are remembered for this long so a replayed envelope is rejected. */
    public static final long REPLAY_WINDOW_MILLIS = 120_000L;

    private AdminProtocol() {
    }
}
