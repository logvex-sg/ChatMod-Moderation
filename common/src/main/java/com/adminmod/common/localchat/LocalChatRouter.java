package com.adminmod.common.localchat;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides who receives a chat message and how it is rendered.
 *
 * <p>This is the whole proximity rule, and it contains no Minecraft type, so the exact behaviour
 * that matters — who hears a local message, who hears a global one, what happens in another
 * dimension, what a staff member sees — is unit-testable without a running server.
 *
 * <p>Delivery is computed from positions the caller supplies, never from anything a client sends.
 * The sender is always a recipient of their own message so their client shows the line once, with
 * the same decoration everyone else sees.
 */
public final class LocalChatRouter {

    private LocalChatRouter() {
    }

    /**
     * The recipients of a message.
     *
     * <ul>
     *   <li>A global message reaches every candidate, or only those in the sender's dimension when
     *       {@code globalReachesAllDimensions} is off.</li>
     *   <li>A local message reaches candidates in the sender's dimension within {@code radius}
     *       blocks, plus any staff member when the staff bypass is on.</li>
     *   <li>The sender is always included, so their own message is never dropped.</li>
     * </ul>
     */
    public static List<Recipient> recipients(Channel channel, Recipient sender, List<Recipient> candidates,
            double radius, boolean globalReachesAllDimensions, boolean staffBypass) {
        List<Recipient> out = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            if (sender != null) {
                out.add(sender);
            }
            return out;
        }
        for (Recipient candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (isSender(candidate, sender) || receives(channel, sender, candidate, radius,
                    globalReachesAllDimensions, staffBypass)) {
                out.add(candidate);
            }
        }
        // A sender who is not in the candidate list (for example a player who just disconnected)
        // still hears their own message rather than losing it silently.
        if (sender != null && out.stream().noneMatch(candidate -> isSender(candidate, sender))) {
            out.add(sender);
        }
        return out;
    }

    /** Whether one candidate receives a message from the sender. */
    public static boolean receives(Channel channel, Recipient sender, Recipient candidate, double radius,
            boolean globalReachesAllDimensions, boolean staffBypass) {
        if (candidate == null || sender == null) {
            return false;
        }
        if (isSender(candidate, sender)) {
            return true;
        }
        if (channel == Channel.GLOBAL) {
            return globalReachesAllDimensions || sameDimension(sender, candidate);
        }
        // Local: same dimension, and close enough, or staff with the bypass on.
        if (staffBypass && candidate.staff()) {
            return true;
        }
        if (!sameDimension(sender, candidate)) {
            return false;
        }
        return sender.position().isWithin(candidate.position(), radius);
    }

    private static boolean isSender(Recipient candidate, Recipient sender) {
        return sender != null && candidate.id() != null && candidate.id().equals(sender.id());
    }

    private static boolean sameDimension(Recipient a, Recipient b) {
        return a.dimension() != null && a.dimension().equals(b.dimension());
    }
}
