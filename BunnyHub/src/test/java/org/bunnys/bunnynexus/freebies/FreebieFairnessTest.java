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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Order inside the shared background budget: reminders/edits first, then catch-up and DMs taking turns. A large
 * catch-up and DM backlog never starves a due reminder: it gets the next free slot.
 */
class FreebieFairnessTest {
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final FreebieRepository repository = mock(FreebieRepository.class);
    final JDA client = mock(JDA.class);
    final FreebieDmWorker dms = mock(FreebieDmWorker.class);
    final FreebieLifecycleWorker lifecycle = mock(FreebieLifecycleWorker.class);
    final List<String> claims = new ArrayList<>();
    final AtomicBoolean reminderDue = new AtomicBoolean();
    final List<AtomicInteger> heldByDms = new ArrayList<>();
    FreebieSender sender;
    AtomicInteger background;

    @BeforeEach void setup() {
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(repository.deliveries()).thenReturn(mock(FreebieDeliveryStore.class));
        when(repository.claim(any(), any())).thenReturn(Optional.empty()); // no first post due
        // An endless catch-up backlog; every claimed row stays in flight.
        var guild = mock(Guild.class); var self = mock(SelfMember.class); var channel = mock(StandardGuildMessageChannel.class);
        when(repository.offer("gamerpower:1")).thenReturn(Optional.of(FreebieUnitTest.offer("X", FreebieStore.EPIC)));
        when(client.getGuildById("100000000000000000")).thenReturn(guild);
        when(guild.getChannelById(StandardGuildMessageChannel.class, "200000000000000000")).thenReturn(channel);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(true);
        when(repository.claimCatchUp(any(), any())).thenAnswer(i -> { claims.add("catch-up"); return Optional.of(FreebieCatchUpPriorityTest.catchUp()); });
        // An endless DM backlog that respects the shared budget like the real worker.
        when(dms.claimOne(any(), any(), any())).thenAnswer(i -> {
            AtomicInteger shared = i.getArgument(1), budget = i.getArgument(2);
            background = budget;
            if (budget.get() >= FreebieSender.BACKGROUND_SLOTS || shared.get() >= FreebieSender.MAX_IN_FLIGHT) return false;
            shared.incrementAndGet(); budget.incrementAndGet(); heldByDms.add(budget);
            claims.add("dm");
            return true;
        });
        // One reminder, due only when the test says so.
        when(lifecycle.claimOne(any(), any(), any())).thenAnswer(i -> {
            AtomicInteger shared = i.getArgument(1), budget = i.getArgument(2);
            if (!reminderDue.get() || budget.get() >= FreebieSender.BACKGROUND_SLOTS) return false;
            reminderDue.set(false); shared.incrementAndGet(); budget.incrementAndGet();
            claims.add("reminder");
            return true;
        });
        sender = new FreebieSender(repository, () -> client, mock(ScheduledExecutorService.class), Clock.fixed(now, ZoneOffset.UTC),
                mock(FreebieNotifier.class)).withLifecycle(lifecycle).withDms(dms);
    }

    @Test void aDueReminderIsClaimedBeforeAnyCatchUpOrDmBacklog() {
        reminderDue.set(true);
        sender.tick();
        assertEquals("reminder", claims.getFirst(), claims.toString());
        assertEquals(List.of("reminder", "catch-up", "dm", "catch-up"), claims, "then catch-up and DMs take turns");
        assertEquals(FreebieSender.BACKGROUND_SLOTS, background.get(), "and together never exceed the background half");
    }

    @Test void aReminderThatBecomesDueBehindAFullBacklogGetsTheNextFreeSlot() {
        sender.tick();
        assertEquals(List.of("catch-up", "dm", "catch-up", "dm"), claims, "the backlog fills the background half");
        reminderDue.set(true);
        claims.clear();
        sender.tick();
        assertTrue(claims.isEmpty(), "no slot free yet: " + claims);
        heldByDms.getFirst().decrementAndGet(); // one DM finished
        sender.tick();
        assertEquals(List.of("reminder"), claims, "the freed slot goes to the reminder, not to the backlog");
    }
}
