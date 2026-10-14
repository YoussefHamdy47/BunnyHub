package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.StreamSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Sender, fan-out and housekeeping behaviour around deleted destinations and the retention schedule. */
class FreebieStaleDestinationTest {
    static final String GUILD = "100000000000000000", CHANNEL = "200000000000000000";
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieDeliveryStore store = mock(FreebieDeliveryStore.class);
    final FreebieRetention retention = mock(FreebieRetention.class);
    final FreebieSubscriptions subscriptions = mock(FreebieSubscriptions.class);
    final JDA client = mock(JDA.class);
    final Guild guild = mock(Guild.class);
    final FreebieOffer offer = FreebieUnitTest.offer("X", FreebieStore.EPIC);
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    FreebieSystem system;

    @BeforeEach void setup() {
        when(repository.deliveries()).thenReturn(store);
        when(repository.notices()).thenReturn(mock(FreebieNoticeStore.class));
        when(repository.retention()).thenReturn(retention);
        when(repository.lifecycle()).thenReturn(mock(FreebieLifecycleStore.class));
        when(repository.dmJobs()).thenReturn(mock(FreebieDmJobs.class));
        when(repository.dmSubscribers()).thenReturn(mock(FreebieDmSubscribers.class));
        when(repository.reviews()).thenReturn(mock(FreebieReviewStore.class));
        when(repository.summaries()).thenReturn(mock(FreebieSummaryStore.class));
        when(repository.offer(offer.id())).thenReturn(Optional.of(offer));
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(client.getGuildById(GUILD)).thenReturn(guild);
        when(client.getGuildById(Long.parseLong(GUILD))).thenReturn(guild);
        system = new FreebieSystem(new FreebieConfig("100000000000000099", Set.of("333644367539470337"), 10),
                repository, subscriptions, () -> client, clock, new GamerPowerFeed());
    }
    @AfterEach void close() { system.close(); }

    FreebieSender sender() {
        when(repository.claim(any(), any())).thenReturn(Optional.of(FreebieSenderTest.job())).thenReturn(Optional.empty());
        return new FreebieSender(repository, () -> client, mock(ScheduledExecutorService.class), clock, mock(FreebieNotifier.class));
    }

    @Test void senderCancelsAlertsForADeletedChannelInsteadOfFailingOrBlocking() {
        sender().tick();
        verify(store).cancelDeleted(FreebieSenderTest.job(), now);
        verify(repository, never()).markFailed(any(), any(), any(), any());
        verify(store, never()).block(any(), any(), any());
    }

    @Test void senderKeepsTheFailurePathForAChannelThatStillExists() {
        // Exists, but is no longer a text-like channel: a configuration problem, not a deletion.
        when(guild.getGuildChannelById(CHANNEL)).thenReturn(mock(GuildChannel.class));
        sender().tick();
        verify(store, never()).cancelDeleted(any(), any());
        verify(repository).markFailed(any(), eq(FreebieFailure.CHANNEL_MISSING), any(), any());
    }

    @Test void senderNeverCancelsForAnUnavailableGuild() {
        when(client.isUnavailable(Long.parseLong(GUILD))).thenReturn(true);
        sender().tick();
        verify(store, never()).cancelDeleted(any(), any());
        verify(repository).release(any(), any(), any());
    }

    @Test void fanOutDoesNotPlanDeletedChannels() {
        when(guild.getGuildChannelById("200000000000000001")).thenReturn(mock(StandardGuildMessageChannel.class));
        var kept = new FreebieSubscriptions.Subscription(GUILD, "200000000000000001", FreebieStore.EPIC, Optional.empty());
        var deleted = new FreebieSubscriptions.Subscription(GUILD, CHANNEL, FreebieStore.EPIC, Optional.empty());
        when(subscriptions.forStore(FreebieStore.EPIC)).thenReturn(List.of(kept, deleted));
        List<FreebieSubscriptions.Subscription> planned = new ArrayList<>();
        when(repository.planDeliveries(eq(offer), any(), eq(now))).thenAnswer(call -> {
            Iterable<FreebieSubscriptions.Subscription> audience = call.getArgument(1);
            StreamSupport.stream(audience.spliterator(), false).forEach(planned::add);
            return planned.size();
        });
        system.fanOut(offer);
        assertEquals(List.of(kept), planned);
        verify(repository).setFanoutDone(offer.id(), 1);
    }

    @Test void housekeepingSweepsOnlyWhenConnectedAndAtMostEveryTenMinutes() {
        when(client.getStatus()).thenReturn(JDA.Status.LOADING_SUBSYSTEMS);
        system.housekeeping();
        verify(subscriptions, never()).all(anyInt());
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        system.housekeeping();
        system.housekeeping();
        verify(subscriptions, times(1)).all(anyInt());
    }

    @Test void housekeepingPurgesWhenDueEvenWhileDisconnected() {
        when(client.getStatus()).thenReturn(JDA.Status.DISCONNECTED);
        when(retention.due(now)).thenReturn(true);
        when(retention.purge(eq(now), eq(90), any())).thenReturn(new FreebieRetention.Result(0, 0, 0, false));
        system.housekeeping();
        verify(retention).purge(now, 90, FreebieRetention.Limits.DEFAULT);
        when(retention.due(now)).thenReturn(false);
        system.housekeeping();
        verify(retention, times(1)).purge(any(), anyInt(), any());
    }

    @Test void deletionEventsRunOnTheFreebieWorker() {
        system.channelDeleted(GUILD, CHANNEL, "epic-games");
        system.roleDeleted(GUILD, "300000000000000001", "Steam");
        verify(store, timeout(5000)).cancelForChannel(GUILD, CHANNEL, now);
        verify(subscriptions, timeout(5000)).remove(GUILD, CHANNEL, Optional.empty());
        verify(subscriptions, timeout(5000)).clearRole(GUILD, "300000000000000001");
    }
}
