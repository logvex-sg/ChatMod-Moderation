package com.adminmod.integrations;

/**
 * Delivery priority for the outbound bridge.
 *
 * <p>Critical messages — a moderation action, a server shutdown notice, an alert — must not sit
 * behind a backlog of ordinary chat, so the dispatcher keeps two queues per provider and drains
 * the critical one first. Ordinary chat is the default.
 */
public enum Priority {
    /** Ordinary player chat and system notices. */
    NORMAL,
    /** Alerts, moderation actions and other messages that should jump the queue. */
    CRITICAL
}
