package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The end trigger wiring: owner stops read "withdrawn", automatic ends read "ended", housekeeping plans both. */
class FreebieLifecycleSystemTest {
    final Instant now = FreebieLifecycleTest.END.minus(Duration.ofHours(1));
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieLifecycleStore store = mock(FreebieLifecycleStore.class);
    final JDA client = mock(JDA.class);
    final FreebieOffer live = FreebieLifecycleTest.live();
    FreebieSystem system;

    @BeforeEach void setup() {
        when(repository.lifecycle()).thenReturn(store);
        when(repository.dmJobs()).thenReturn(mock(FreebieDmJobs.class));
        when(repository.dmSubscribers()).thenReturn(mock(FreebieDmSubscribers.class));
        when(repository.deliveries()).thenReturn(mock(FreebieDeliveryStore.class));
        when(repository.notices()).thenReturn(mock(FreebieNoticeStore.class));
        when(repository.retention()).thenReturn(mock(FreebieRetention.class));
        when(repository.reviews()).thenReturn(mock(FreebieReviewStore.class));
        when(repository.summaries()).thenReturn(mock(FreebieSummaryStore.class));
        when(store.planEdits(any(), anyBoolean(), any())).thenReturn(new FreebieLifecycleStore.Planned(0, 0));
        when(client.getStatus()).thenReturn(JDA.Status.DISCONNECTED);
        system = new FreebieSystem(new FreebieConfig("100000000000000099", Set.of("333644367539470337"), 10),
                repository, mock(FreebieSubscriptions.class), () -> client, Clock.fixed(now, ZoneOffset.UTC), new GamerPowerFeed());
    }
    @AfterEach void close() { system.close(); }

    static FreebieOffer stopped(boolean gone) {
        return FreebieLifecycleTest.offer(FreebieOffer.State.STOPPED, gone, Optional.of(FreebieLifecycleTest.END), false, true);
    }

    @Test void anOwnerStopIsRecordedAtomicallyAndQueuesWithdrawnEdits() {
        when(repository.stopByOwner(live.id(), FreebieOffer.State.APPROVED, "333644367539470337", now)).thenReturn(Optional.of(stopped(false)));
        when(repository.offer(live.id())).thenReturn(Optional.of(stopped(false)));
        when(repository.stoppedByOwner(live.id())).thenReturn(true);
        system.stop("333644367539470337", live.id());
        verify(repository, never()).transition(any(), any(), eq(FreebieOffer.State.STOPPED), any(), any());
        verify(store, timeout(5000)).cancelReminders(live.id(), now);
        verify(store, timeout(5000)).planEdits(live.id(), true, now);
    }

    @Test void anAutomaticEndQueuesEndedEditsAndCancelsReminders() {
        var gone = FreebieLifecycleTest.offer(FreebieOffer.State.APPROVED, true, Optional.of(FreebieLifecycleTest.END), false, true);
        when(repository.offersIn(FreebieOffer.State.APPROVED, 200)).thenReturn(List.of(gone));
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(repository.transition(gone.id(), FreebieOffer.State.APPROVED, FreebieOffer.State.STOPPED, null, now))
                .thenReturn(Optional.of(stopped(true)));
        when(repository.offer(gone.id())).thenReturn(Optional.of(stopped(true)));
        system.housekeeping();
        verify(store).cancelReminders(gone.id(), now);
        verify(store).planEdits(gone.id(), false, now);
    }

    @Test void housekeepingPlansFromTheDatabaseEvenWhileDisconnected() {
        when(store.endCandidates(now, FreebieLifecyclePlanner.OFFERS_PER_RUN))
                .thenReturn(List.of(new FreebieLifecycleStore.EndCandidate(stopped(false), true)));
        when(store.openDeliveries(live.id())).thenReturn(0L);
        system.housekeeping();
        verify(store).planEdits(live.id(), true, now);
        verify(store).endMarked(live.id(), 0, now);
        verify(store).recover(now);
        verify(store).endingWithin(eq(now), eq(Duration.ofHours(24)), anyInt());
    }

    @Test void anOfferWithAlertsStillOpenIsPlannedAgainLater() {
        when(store.endCandidates(now, FreebieLifecyclePlanner.OFFERS_PER_RUN))
                .thenReturn(List.of(new FreebieLifecycleStore.EndCandidate(stopped(true), false)));
        when(store.openDeliveries(live.id())).thenReturn(2L);
        system.housekeeping();
        verify(store).planEdits(live.id(), false, now);
        verify(store, never()).endMarked(any(), anyInt(), any());
    }

    @Test void statusShowsEditAndReminderQueues() {
        var text = FreebieSystem.lifecycleStatus(new FreebieLifecycleStore.Stats(3, 1, now.minus(Duration.ofMinutes(42))),
                new FreebieLifecycleStore.Stats(0, 2, null), now);
        assertTrue(text.contains("Ended-card edits: **3** pending • oldest 42m • **1** failed"), text);
        assertTrue(text.contains("Ending-soon reminders: **0** pending • **2** failed"), text);
    }
}
