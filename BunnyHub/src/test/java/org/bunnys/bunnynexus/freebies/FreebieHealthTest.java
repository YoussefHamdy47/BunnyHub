package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FreebieHealthTest {
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final String id = "100000000000000099";
    final FreebieConfig config = new FreebieConfig(id, Set.of("333644367539470337"), 10);

    @Test void missingChannelAndDisconnectedGatewayGiveDifferentAdvice() {
        var client = mock(JDA.class);
        assertTrue(FreebieHealth.channelHealth(client, id).contains("disconnected"));
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        assertTrue(FreebieHealth.channelHealth(client, id).contains("not visible"));
    }

    @Test void permissionChecksIdentifyEveryMissingPermissionWithoutSending() {
        var client = mock(JDA.class); var channel = mock(GuildMessageChannel.class);
        var guild = mock(Guild.class); var self = mock(SelfMember.class);
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(client.getChannelById(GuildMessageChannel.class, id)).thenReturn(channel);
        when(channel.getGuild()).thenReturn(guild); when(guild.getSelfMember()).thenReturn(self);
        String missing = FreebieHealth.channelHealth(client, id);
        for (String permission : List.of("View Channel", "Send Messages", "Embed Links", "Read Message History"))
            assertTrue(missing.contains(permission));
        when(channel.canTalk()).thenReturn(true);
        when(self.hasPermission(channel, Permission.VIEW_CHANNEL)).thenReturn(true);
        when(self.hasPermission(channel, Permission.MESSAGE_EMBED_LINKS)).thenReturn(true);
        when(self.hasPermission(channel, Permission.MESSAGE_HISTORY)).thenReturn(true);
        assertEquals("ready", FreebieHealth.channelHealth(client, id));
        verify(channel, never()).sendMessage(org.mockito.ArgumentMatchers.anyString());
    }

    @Test void unknownFailureTextIsNeverShownToOwners() {
        assertEquals("unknown failure; inspect logs", FreebieHealth.reason("secret database exception"));
        assertEquals("unknown failure; inspect logs", FreebieHealth.reason(null));
    }

    @Test void maximumDetailFitsStatusEmbedAndDoesNotInventNegativeAges() {
        var queue = new FreebieHealth.Queue(Long.MAX_VALUE, now.plusSeconds(60), Long.MAX_VALUE, Long.MAX_VALUE);
        var failures = new ArrayList<FreebieHealth.Failure>(); var summaries = new ArrayList<FreebieHealth.Destination>();
        for (int i = 0; i < 5; i++) {
            failures.add(new FreebieHealth.Failure(id, id, "MISSING_PERMISSIONS"));
            summaries.add(new FreebieHealth.Destination("gamerpower:" + "1".repeat(48), id, "awaiting recovery"));
        }
        var snapshot = new FreebieHealth.Snapshot(queue, queue, queue, queue, Long.MAX_VALUE, failures, summaries);
        String rendered = FreebieHealth.render(snapshot, null, config, now);
        assertTrue(rendered.length() < 3400, "leave room for the discovery status: " + rendered.length());
        assertFalse(rendered.contains("oldest -"));
        assertTrue(rendered.contains("expired leases"));
        assertTrue(rendered.contains("Summaries pending"));
    }

    @Test void unauthorizedStatusCannotReadDatabase() {
        var repository = mock(FreebieRepository.class);
        var system = new FreebieSystem(config, repository, mock(FreebieSubscriptions.class), () -> null,
                Clock.fixed(now, ZoneOffset.UTC), new GamerPowerFeed());
        try { assertThrows(SecurityException.class, () -> system.status("999999999999999999")); }
        finally { system.close(); }
        verifyNoInteractions(repository);
    }
    @Test void combinedOwnerDiagnosticsRemainBoundedWithBlockedReasonsVisible() {
        var repository = mock(FreebieRepository.class); var health = mock(FreebieHealth.class);
        var deliveries = mock(FreebieDeliveryStore.class);
        when(repository.health()).thenReturn(health); when(repository.deliveries()).thenReturn(deliveries);
        when(repository.overview()).thenReturn(new FreebieRepository.Overview(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        var queue = new FreebieHealth.Queue(Long.MAX_VALUE, now, Long.MAX_VALUE, Long.MAX_VALUE);
        var failure = new FreebieHealth.Failure(id, id, "MISSING_PERMISSIONS");
        var summary = new FreebieHealth.Destination("gamerpower:" + "1".repeat(48), id, "awaiting recovery");
        when(health.snapshot(now)).thenReturn(new FreebieHealth.Snapshot(queue, queue, queue, queue, Long.MAX_VALUE,
                Collections.nCopies(5, failure), Collections.nCopies(5, summary)));
        var blocked = new org.bson.Document("offerId", "gamerpower:12345678901234567890").append("channelId", id)
                .append("blockedReason", "HISTORY_PERMISSION").append("nextAttemptAt", Date.from(now.plusSeconds(120)));
        when(deliveries.blocked()).thenReturn(Collections.nCopies(3, blocked));
        var system = new FreebieSystem(config, repository, mock(FreebieSubscriptions.class), () -> null,
                Clock.fixed(now, ZoneOffset.UTC), new GamerPowerFeed());
        try {
            String text = system.status("333644367539470337");
            assertTrue(text.length() <= 4096, "size=" + text.length());
            assertTrue(text.contains("Restore Read Message History")); assertTrue(text.contains("Next check"));
        } finally { system.close(); }
    }}

