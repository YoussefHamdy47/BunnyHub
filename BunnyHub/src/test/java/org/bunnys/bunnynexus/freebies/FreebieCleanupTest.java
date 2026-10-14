package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.stream.StreamSupport;
import static org.bunnys.bunnynexus.freebies.FreebieCleanup.Presence.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Deleted channel/role cleanup: live events, offline sweep, and the "uncached is never deleted" rule. */
class FreebieCleanupTest {
    static final String GUILD = "100000000000000000", KEPT = "200000000000000001", GONE = "200000000000000002",
            ROLE = "300000000000000001", GONE_ROLE = "300000000000000002", OTHER_GUILD = "100000000000000009";
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final FreebieSubscriptions subscriptions = mock(FreebieSubscriptions.class);
    final FreebieDeliveryStore deliveries = mock(FreebieDeliveryStore.class);
    final FreebieNoticeStore notices = mock(FreebieNoticeStore.class);
    final JDA client = mock(JDA.class);
    final Guild guild = mock(Guild.class);
    final FreebieCleanup cleanup = new FreebieCleanup(subscriptions, deliveries, notices);

    @BeforeEach void loadedGuild() {
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(client.getGuildById(Long.parseLong(GUILD))).thenReturn(guild);
        when(guild.getGuildChannelById(KEPT)).thenReturn(mock(GuildChannel.class));
        when(guild.getRoleById(ROLE)).thenReturn(mock(Role.class));
        when(subscriptions.remove(any(), any(), any())).thenReturn(1L);
        when(subscriptions.clearRole(any(), any())).thenReturn(1L);
    }

    static FreebieSubscriptions.Subscription sub(String guild, String channel, String role) {
        return new FreebieSubscriptions.Subscription(guild, channel, FreebieStore.EPIC, Optional.ofNullable(role));
    }

    @Test void onlyALoadedConnectedGuildCanReportADeletion() {
        assertEquals(DELETED, FreebieCleanup.channel(client, GUILD, GONE));
        assertEquals(PRESENT, FreebieCleanup.channel(client, GUILD, KEPT));
        assertEquals(DELETED, FreebieCleanup.role(client, GUILD, GONE_ROLE));
        assertEquals(PRESENT, FreebieCleanup.role(client, GUILD, ROLE));
        assertEquals(UNKNOWN, FreebieCleanup.channel(client, OTHER_GUILD, GONE), "uncached guild");
        assertEquals(UNKNOWN, FreebieCleanup.channel(null, GUILD, GONE));
        when(client.isUnavailable(Long.parseLong(GUILD))).thenReturn(true);
        assertEquals(UNKNOWN, FreebieCleanup.channel(client, GUILD, GONE), "unavailable guild");
        assertEquals(UNKNOWN, FreebieCleanup.role(client, GUILD, GONE_ROLE));
        when(client.isUnavailable(Long.parseLong(GUILD))).thenReturn(false);
        for (var status : List.of(JDA.Status.LOADING_SUBSYSTEMS, JDA.Status.AWAITING_LOGIN_CONFIRMATION, JDA.Status.DISCONNECTED)) {
            when(client.getStatus()).thenReturn(status);
            assertEquals(UNKNOWN, FreebieCleanup.channel(client, GUILD, GONE), status.name());
        }
    }

    @Test void liveChannelDeleteRemovesSettingsAndCancelsOpenAlerts() {
        cleanup.channelDeleted(GUILD, GONE, Optional.of("epic-games"), now);
        verify(subscriptions).remove(GUILD, GONE, Optional.empty());
        verify(deliveries).cancelForChannel(GUILD, GONE, now);
    }

    @Test void liveRoleDeleteOnlyClearsThePing() {
        cleanup.roleDeleted(GUILD, GONE_ROLE, Optional.of("Steam"), now);
        verify(subscriptions).clearRole(GUILD, GONE_ROLE);
        verify(subscriptions, never()).remove(any(), any(), any());
        verifyNoInteractions(deliveries);
    }

    @Test void sweepRemovesOnlyDeletedChannelsAndRoles() {
        when(subscriptions.all(anyInt())).thenReturn(List.of(sub(GUILD, KEPT, ROLE), sub(GUILD, KEPT, GONE_ROLE),
                sub(GUILD, GONE, null), sub(OTHER_GUILD, GONE, GONE_ROLE)));
        when(deliveries.openDestinations(anyInt())).thenReturn(List.of(List.of(GUILD, "200000000000000003"),
                List.of(GUILD, KEPT), List.of(OTHER_GUILD, "200000000000000004")));
        when(deliveries.cancelForChannel(any(), any(), any())).thenReturn(2L);
        var result = cleanup.sweep(client, now);
        verify(subscriptions).remove(GUILD, GONE, Optional.empty());
        verify(deliveries).cancelForChannel(GUILD, GONE, now);
        verify(subscriptions).clearRole(GUILD, GONE_ROLE);
        // A queued alert whose setting is already gone is still cancelled when its channel was deleted.
        verify(deliveries).cancelForChannel(GUILD, "200000000000000003", now);
        verify(subscriptions, never()).remove(eq(GUILD), eq(KEPT), any());
        verify(subscriptions, never()).clearRole(GUILD, ROLE);
        verify(subscriptions, never()).remove(eq(OTHER_GUILD), any(), any());
        verify(subscriptions, never()).clearRole(eq(OTHER_GUILD), any());
        verify(deliveries, never()).cancelForChannel(eq(OTHER_GUILD), any(), any());
        assertEquals(new FreebieCleanup.Sweep(1, 1, 4), result);
    }

    @Test void sweepNeverRunsWhileDisconnectedOrForUnavailableGuilds() {
        when(subscriptions.all(anyInt())).thenReturn(List.of(sub(GUILD, GONE, GONE_ROLE)));
        when(deliveries.openDestinations(anyInt())).thenReturn(List.of(List.of(GUILD, GONE)));
        when(client.getStatus()).thenReturn(JDA.Status.RECONNECT_QUEUED);
        cleanup.sweep(client, now);
        cleanup.sweep(null, now);
        verifyNoInteractions(subscriptions, deliveries, notices);
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(client.isUnavailable(Long.parseLong(GUILD))).thenReturn(true);
        cleanup.sweep(client, now);
        verify(subscriptions, never()).remove(any(), any(), any());
        verify(subscriptions, never()).clearRole(any(), any());
        verify(deliveries, never()).cancelForChannel(any(), any(), any());
    }

    @Test void fanOutAudienceSkipsOnlyKnownDeletedChannels() {
        var audience = List.of(sub(GUILD, KEPT, null), sub(GUILD, GONE, null), sub(OTHER_GUILD, GONE, null));
        var kept = StreamSupport.stream(FreebieCleanup.deliverable(client, audience).spliterator(), false).toList();
        assertEquals(List.of(sub(GUILD, KEPT, null), sub(OTHER_GUILD, GONE, null)), kept);
        when(client.getStatus()).thenReturn(JDA.Status.DISCONNECTED);
        assertEquals(3, StreamSupport.stream(FreebieCleanup.deliverable(client, audience).spliterator(), false).count());
    }

    @Test void retentionDaysDefaultMinimumAndInvalidValues() {
        List<String> warnings = new ArrayList<>();
        assertEquals(90, FreebieConfig.retentionDays(null, warnings::add));
        assertEquals(90, FreebieConfig.retentionDays(" ", warnings::add));
        assertEquals(30, FreebieConfig.retentionDays("30", warnings::add));
        assertEquals(365, FreebieConfig.retentionDays(" 365 ", warnings::add));
        assertTrue(warnings.isEmpty());
        assertEquals(90, FreebieConfig.retentionDays("29", warnings::add));
        assertEquals(90, FreebieConfig.retentionDays("forever", warnings::add));
        assertEquals(90, FreebieConfig.retentionDays("-5", warnings::add));
        assertEquals(3, warnings.size());
        assertTrue(warnings.getFirst().contains("FREEBIE_RETENTION_DAYS"));
        var config = FreebieConfig.load(Map.of("FREEBIE_REVIEW_CHANNEL_ID", "100000000000000099", "FREEBIE_OWNER_IDS",
                "333644367539470337", "FREEBIE_RETENTION_DAYS", "7")::get, new ArrayList<>()).orElseThrow();
        assertEquals(90, config.retentionDays(), "an invalid window keeps the system on with the default");
        assertEquals(45, FreebieConfig.load(Map.of("FREEBIE_REVIEW_CHANNEL_ID", "100000000000000099", "FREEBIE_OWNER_IDS",
                "333644367539470337", "FREEBIE_RETENTION_DAYS", "45")::get, new ArrayList<>()).orElseThrow().retentionDays());
    }

    @Test void statusShowsLastPurge() {
        assertTrue(FreebieSystem.retentionStatus(90, Optional.empty()).contains("No purge has run yet"));
        var text = FreebieSystem.retentionStatus(60, Optional.of(new FreebieRetention.Last(now, 12, 3, 1)));
        assertTrue(text.contains("(60 days)"));
        assertTrue(text.contains("<t:" + now.getEpochSecond() + ":R>"));
        assertTrue(text.contains("12 alert(s), 3 offer(s), 1 notice record(s)"));
    }
}
