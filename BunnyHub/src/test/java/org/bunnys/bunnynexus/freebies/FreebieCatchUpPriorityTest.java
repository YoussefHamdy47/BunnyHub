package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Catch-up deliveries never delay first posts, and the one catch-up path reuses the button's eligibility. */
class FreebieCatchUpPriorityTest {
    static final String GUILD = "100000000000000000", CHANNEL = "200000000000000000";
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final FreebieRepository repository = mock(FreebieRepository.class);
    final JDA client = mock(JDA.class);
    final FreebieDmWorker dms = mock(FreebieDmWorker.class);
    final FreebieLifecycleWorker lifecycle = mock(FreebieLifecycleWorker.class);
    FreebieSender sender;

    @BeforeEach void setup() {
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(repository.deliveries()).thenReturn(mock(FreebieDeliveryStore.class));
        sender = new FreebieSender(repository, () -> client, mock(ScheduledExecutorService.class), Clock.fixed(now, ZoneOffset.UTC),
                mock(FreebieNotifier.class)).withLifecycle(lifecycle).withDms(dms);
        // Every claimed alert is handed to Discord and stays in flight.
        var guild = mock(Guild.class); var self = mock(SelfMember.class); var channel = mock(StandardGuildMessageChannel.class);
        when(repository.offer("gamerpower:1")).thenReturn(Optional.of(FreebieUnitTest.offer("X", FreebieStore.EPIC)));
        when(client.getGuildById(GUILD)).thenReturn(guild);
        when(guild.getChannelById(StandardGuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(true);
    }

    static FreebieRepository.Claimed catchUp() {
        var base = FreebieSenderTest.job();
        return new FreebieRepository.Claimed(base.id(), base.offerId(), base.guildId(), base.channelId(), base.roleId(), 1, false, "t", true);
    }

    @Test void catchUpIsClaimedOnlyOnceNoFreshFirstPostIsDue() {
        when(repository.claim(any(), any())).thenReturn(Optional.of(FreebieSenderTest.job())).thenReturn(Optional.empty());
        when(repository.claimCatchUp(any(), any())).thenReturn(Optional.of(catchUp()));
        sender.tick();
        var order = inOrder(repository);
        order.verify(repository, times(2)).claim(any(), any());
        order.verify(repository, atLeastOnce()).claimCatchUp(any(), any());
    }

    @Test void catchUpNeverHoldsMoreThanTheBackgroundHalf() {
        when(repository.claim(any(), any())).thenReturn(Optional.empty());
        when(repository.claimCatchUp(any(), any())).thenReturn(Optional.of(catchUp()));
        sender.tick();
        assertEquals(FreebieSender.BACKGROUND_SLOTS, sender.inFlight(), "half the slots stay free for a new approval");
        assertEquals(FreebieSender.BACKGROUND_SLOTS, sender.background());
        verify(repository, times(FreebieSender.BACKGROUND_SLOTS)).claimCatchUp(any(), any());
        // DMs and card updates are offered the same shared counter, already full.
        verify(dms, atLeastOnce()).claimOne(eq(client), any(AtomicInteger.class), argThat(b -> b.get() == FreebieSender.BACKGROUND_SLOTS));
    }

    @Test void aBusyFirstPostQueueHoldsCatchUpDmsAndCardUpdates() {
        when(repository.claim(any(), any())).thenReturn(Optional.of(FreebieSenderTest.job()));
        sender.tick();
        verify(repository, never()).claimCatchUp(any(), any());
        verify(dms, never()).claimOne(any(), any(), any());
        verify(lifecycle, never()).claimOne(any(), any(), any());
    }

    @Test void thePauseHoldsDmsToo() {
        when(repository.sendingPaused()).thenReturn(true);
        sender.tick();
        verify(dms, never()).claimOne(any(), any(), any());
        verify(repository, never()).claimCatchUp(any(), any());
    }

    // ------------------------------------------------------------------ the shared catch-up path

    @Test void catchUpChannelReusesTheButtonEligibilityForThatChannelOnly() {
        var subscriptions = mock(FreebieSubscriptions.class);
        var steam = new FreebieSubscriptions.Subscription(GUILD, CHANNEL, FreebieStore.STEAM, Optional.empty());
        var epic = new FreebieSubscriptions.Subscription(GUILD, CHANNEL, FreebieStore.EPIC, Optional.empty());
        var elsewhere = new FreebieSubscriptions.Subscription(GUILD, "200000000000000001", FreebieStore.STEAM, Optional.empty());
        when(subscriptions.forGuild(GUILD)).thenReturn(List.of(steam, epic, elsewhere));
        var a = FreebieUnitTest.offer("A", FreebieStore.STEAM);
        when(repository.liveApproved(FreebieStore.STEAM, now)).thenReturn(List.of(a));
        when(repository.planDelivery(a, steam, now)).thenReturn(true);
        var system = new FreebieSystem(new FreebieConfig("100000000000000099", Set.of("333644367539470337"), 10),
                repository, subscriptions, () -> client, Clock.fixed(now, ZoneOffset.UTC), new GamerPowerFeed());
        try {
            var result = system.catchUpChannel(GUILD, CHANNEL, s -> s == FreebieStore.STEAM);
            assertEquals(new FreebieSystem.CatchUp(1, 1), result);
            verify(repository, atLeastOnce()).liveApproved(FreebieStore.STEAM, now); // the same query as the button and /free-games
            verify(repository).planDelivery(a, steam, now);
            verify(repository, never()).planDelivery(any(), eq(epic), any());
            verify(repository, never()).planDelivery(any(), eq(elsewhere), any());
        } finally { system.close(); }
    }
}
