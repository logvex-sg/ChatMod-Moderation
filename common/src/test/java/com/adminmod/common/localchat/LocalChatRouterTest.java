package com.adminmod.common.localchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The proximity delivery rule.
 *
 * <p>These are the tests that matter most for the feature: who hears a message is a security
 * property, not a preference. The router is deliberately free of Minecraft types so every branch
 * can be exercised directly.
 */
class LocalChatRouterTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private static Recipient player(String id, double x, double z, String dimension) {
        return new Recipient(id, id, dimension, new Position(x, 64, z), false);
    }

    private static Recipient staff(String id, double x, double z, String dimension) {
        return new Recipient(id, id, dimension, new Position(x, 64, z), true);
    }

    @Test
    void aLocalMessageReachesOnlyPlayersWithinTheRadius() {
        Recipient sender = player("sender", 0, 0, OVERWORLD);
        Recipient near = player("near", 10, 0, OVERWORLD);
        Recipient far = player("far", 100, 0, OVERWORLD);
        Recipient otherWorld = player("nether", 0, 0, NETHER);

        List<Recipient> recipients = LocalChatRouter.recipients(Channel.LOCAL, sender,
                List.of(sender, near, far, otherWorld), 50.0, true, false);

        assertTrue(recipients.stream().anyMatch(r -> r.id().equals("sender")), "the sender hears itself");
        assertTrue(recipients.stream().anyMatch(r -> r.id().equals("near")), "a nearby player hears it");
        assertFalse(recipients.stream().anyMatch(r -> r.id().equals("far")), "a distant player does not");
        assertFalse(recipients.stream().anyMatch(r -> r.id().equals("nether")),
                "a player in another dimension does not");
    }

    @Test
    void theBoundaryIsInclusive() {
        Recipient sender = player("sender", 0, 0, OVERWORLD);
        Recipient exactlyOnEdge = player("edge", 50, 0, OVERWORLD);

        List<Recipient> recipients = LocalChatRouter.recipients(Channel.LOCAL, sender,
                List.of(sender, exactlyOnEdge), 50.0, true, false);

        assertTrue(recipients.stream().anyMatch(r -> r.id().equals("edge")),
                "a player exactly on the radius boundary hears the message");
    }

    @Test
    void aGlobalMessageReachesEveryoneAcrossDimensions() {
        Recipient sender = player("sender", 0, 0, OVERWORLD);
        Recipient otherWorld = player("nether", 0, 0, NETHER);
        Recipient far = player("far", 10_000, 0, OVERWORLD);

        List<Recipient> recipients = LocalChatRouter.recipients(Channel.GLOBAL, sender,
                List.of(sender, otherWorld, far), 50.0, true, false);

        assertEquals(3, recipients.size(), "a global message reaches every candidate");
    }

    @Test
    void aGlobalMessageCanBeRestrictedToTheSendersDimension() {
        Recipient sender = player("sender", 0, 0, OVERWORLD);
        Recipient otherWorld = player("nether", 0, 0, NETHER);
        Recipient sameWorld = player("far", 10_000, 0, OVERWORLD);

        List<Recipient> recipients = LocalChatRouter.recipients(Channel.GLOBAL, sender,
                List.of(sender, otherWorld, sameWorld), 50.0, false, false);

        assertTrue(recipients.stream().anyMatch(r -> r.id().equals("far")),
                "a player in the sender's dimension hears a global message");
        assertFalse(recipients.stream().anyMatch(r -> r.id().equals("nether")),
                "a player in another dimension does not when global is dimension-bound");
    }

    @Test
    void staffWithTheBypassOnHearEveryLocalMessage() {
        Recipient sender = player("sender", 0, 0, OVERWORLD);
        Recipient moderator = staff("mod", 10_000, 0, NETHER);

        List<Recipient> recipients = LocalChatRouter.recipients(Channel.LOCAL, sender,
                List.of(sender, moderator), 50.0, true, true);

        assertTrue(recipients.stream().anyMatch(r -> r.id().equals("mod")),
                "a staff member hears a local message from another dimension when the bypass is on");
    }

    @Test
    void staffWithoutTheBypassAreSubjectToTheRadius() {
        Recipient sender = player("sender", 0, 0, OVERWORLD);
        Recipient moderator = staff("mod", 10_000, 0, NETHER);

        List<Recipient> recipients = LocalChatRouter.recipients(Channel.LOCAL, sender,
                List.of(sender, moderator), 50.0, true, false);

        assertFalse(recipients.stream().anyMatch(r -> r.id().equals("mod")),
                "with the bypass off a staff member obeys the radius like anyone else");
    }

    @Test
    void theSenderIsAlwaysIncludedEvenWhenAbsentFromTheCandidateList() {
        Recipient sender = player("sender", 0, 0, OVERWORLD);

        List<Recipient> recipients = LocalChatRouter.recipients(Channel.LOCAL, sender, List.of(), 50.0, true, false);

        assertEquals(List.of("sender"), recipients.stream().map(Recipient::id).toList(),
                "a disconnected sender still sees their own line");
    }

    @Test
    void receivesRejectsANullCandidate() {
        Recipient sender = player("sender", 0, 0, OVERWORLD);
        assertFalse(LocalChatRouter.receives(Channel.LOCAL, sender, null, 50.0, true, false));
    }
}
