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

/** Card edits and reminders never take a slot a due alert needs, and respect the global pause. */
class FreebieLifecyclePriorityTest {
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieLifecycleWorker lifecycle = mock(FreebieLifecycleWorker.class);
    final JDA client = mock(JDA.class);
    FreebieSender sender;

    @BeforeEach void setup() {
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(repository.deliveries()).thenReturn(mock(FreebieDeliveryStore.class));
        sender = new FreebieSender(repository, () -> client, mock(ScheduledExecutorService.class), Clock.fixed(now, ZoneOffset.UTC),
                mock(FreebieNotifier.class)).withLifecycle(lifecycle);
    }

    @Test void runsOnlyAfterEveryDueAlertWasClaimed() {
        when(repository.claim(any(), any())).thenReturn(Optional.empty());
        sender.tick();
        verify(lifecycle).claimOne(eq(client), any(AtomicInteger.class), any(AtomicInteger.class));
    }

    @Test void waitsWhileAlertsFillTheBudget() {
        // Every alert is handed to Discord and stays in flight, so the eight slots fill with alerts.
        var guild = mock(Guild.class); var self = mock(SelfMember.class); var channel = mock(StandardGuildMessageChannel.class);
        when(repository.offer("gamerpower:1")).thenReturn(Optional.of(FreebieUnitTest.offer("X", FreebieStore.EPIC)));
        when(client.getGuildById("100000000000000000")).thenReturn(guild);
        when(guild.getChannelById(StandardGuildMessageChannel.class, "200000000000000000")).thenReturn(channel);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(true);
        when(repository.claim(any(), any())).thenReturn(Optional.of(FreebieSenderTest.job()));
        sender.tick();
        assertEquals(FreebieSender.MAX_IN_FLIGHT, sender.inFlight());
        verify(lifecycle, never()).claimOne(any(), any(), any());
    }

    @Test void theGlobalPauseHoldsEditsAndRemindersToo() {
        when(repository.sendingPaused()).thenReturn(true);
        sender.tick();
        verify(lifecycle, never()).claimOne(any(), any(), any());
    }
}
