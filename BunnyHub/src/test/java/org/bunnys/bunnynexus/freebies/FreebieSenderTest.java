package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FreebieSenderTest {
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final FreebieRepository repository = mock(FreebieRepository.class);
    final JDA client = mock(JDA.class);
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @AfterEach void stop() { executor.shutdownNow(); }

    FreebieSender sender() {
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        return new FreebieSender(repository, () -> client, executor, clock, new FreebieNotifier(repository, () -> client, clock, executor));
    }

    static FreebieRepository.Claimed job() {
        return new FreebieRepository.Claimed("gamerpower:1|200000000000000000", "gamerpower:1", "100000000000000000",
                "200000000000000000", Optional.empty(), 1, false, "token");
    }

    @Test void aStorageFailureAfterASynchronousOutcomeReleasesTheSlotExactlyOnce() {
        var sender = sender();
        when(repository.claim(any(), any(Duration.class))).thenReturn(Optional.of(job())).thenReturn(Optional.empty());
        when(repository.offer("gamerpower:1")).thenReturn(Optional.of(FreebieUnitTest.offer("X", FreebieStore.EPIC)));
        when(client.getGuildById("100000000000000000")).thenReturn(null); // bot removed: a synchronous, final outcome
        when(repository.markFailed(any(), any(), any(), any())).thenThrow(new IllegalStateException("database down"));

        assertThrows(IllegalStateException.class, sender::tick);
        assertEquals(0, sender.inFlight(), "a slot released twice would let more than 8 sends run at once");
    }

    @Test void synchronousCancellationsFreeEverySlot() {
        var sender = sender();
        when(repository.claim(any(), any(Duration.class))).thenReturn(Optional.of(job())).thenReturn(Optional.of(job())).thenReturn(Optional.empty());
        when(repository.offer("gamerpower:1")).thenReturn(Optional.empty());

        sender.tick();
        assertEquals(0, sender.inFlight());
        verify(repository, times(2)).markCancelled(any(), eq("offer no longer approved"), eq(now));
    }

    @Test void pausedSendingClaimsNothing() {
        var sender = sender();
        when(repository.sendingPaused()).thenReturn(true);
        sender.tick();
        verify(repository, never()).claim(any(), any());
    }
}
