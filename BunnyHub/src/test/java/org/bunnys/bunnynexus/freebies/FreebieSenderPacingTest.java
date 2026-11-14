package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The sender's idle backoff and wake signal, on a scheduler the test controls: ticks are told apart by the time of
 * each first-post claim (the first query of every pass), not by sleeping.
 */
class FreebieSenderPacingTest {
    final Instant start = Instant.parse("2026-09-25T12:00:00Z");
    final ManualScheduler scheduler = new ManualScheduler(start);
    final FreebieRepository repository = mock(FreebieRepository.class);
    final FreebieLifecycleStore lifecycleJobs = mock(FreebieLifecycleStore.class);
    final FreebieDmJobs dmJobs = mock(FreebieDmJobs.class);
    final FreebieBroadcastStore broadcastJobs = mock(FreebieBroadcastStore.class);
    final FreebiePublicationStore publications = mock(FreebiePublicationStore.class);
    final FreebieAnnouncementStore pages = mock(FreebieAnnouncementStore.class);
    final FreebieNoticeStore notices = mock(FreebieNoticeStore.class);
    final JDA client = mock(JDA.class);
    /** Seconds since {@link #start} at which each tick ran. */
    final List<Long> ticks = new ArrayList<>();
    final java.util.concurrent.atomic.AtomicBoolean claimNext = new java.util.concurrent.atomic.AtomicBoolean();
    FreebieSender sender;
    FreebieNotifier notifier;

    @BeforeEach void setup() {
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(repository.lifecycle()).thenReturn(lifecycleJobs);
        when(repository.dmJobs()).thenReturn(dmJobs);
        when(repository.broadcasts()).thenReturn(broadcastJobs);
        when(repository.publications()).thenReturn(publications);
        when(repository.announcements()).thenReturn(pages);
        when(repository.notices()).thenReturn(notices);
        when(lifecycleJobs.claim(any(), any())).thenReturn(Optional.empty());
        when(dmJobs.claim(any(), any())).thenReturn(Optional.empty());
        when(broadcastJobs.claim(any(), any())).thenReturn(Optional.empty());
        when(publications.claim(any(), any())).thenReturn(Optional.empty());
        when(pages.claimRefresh(any(), any())).thenReturn(Optional.empty());
        when(pages.claimOrphan(any(), any())).thenReturn(Optional.empty());
        when(pages.publishPending(anyInt())).thenReturn(List.of());
        when(notices.claim(any())).thenReturn(Optional.empty());
        when(repository.claimCatchUp(any(), any())).thenReturn(Optional.empty());
        idle();
        var clock = scheduler.clock();
        // Publishing on, so the publication queue is polled like every other one.
        var features = new FreebieFeatures(true, true, Set.of("200000000000000000"), false, true, false);
        notifier = new FreebieNotifier(repository, () -> client, clock, scheduler);
        sender = new FreebieSender(repository, () -> client, scheduler, clock, notifier)
                .withLifecycle(new FreebieLifecycleWorker(repository, mock(FreebieSubscriptions.class), scheduler, clock))
                .withDms(new FreebieDmWorker(repository, scheduler, clock))
                .withBroadcasts(new FreebieBroadcastWorker(repository, scheduler, clock))
                .withAnnouncements(mock(FreebieAnnouncementSender.class), new FreebieAnnouncementWorker(repository, features, scheduler, clock),
                        new FreebiePublisher(repository, features, scheduler, clock));
    }

    /** Every first-post claim records its tick; it finds a row only once after {@link #oneClaim()}. */
    void idle() {
        when(repository.claim(any(), any())).thenAnswer(call -> {
            tick();
            return claimNext.getAndSet(false) ? Optional.of(FreebieSenderTest.job()) : Optional.empty();
        });
    }

    void tick() { ticks.add(Duration.between(start, scheduler.now()).toSeconds()); }

    /** One due first post whose offer is gone: claimed and cancelled at once, so nothing stays in flight. */
    void oneClaim() {
        claimNext.set(true);
        when(repository.offer("gamerpower:1")).thenReturn(Optional.empty());
    }

    @Test void anIdleSenderBacksOffAndReturnsToOneSecondAfterAClaim() {
        sender.start(Duration.ofSeconds(10));
        scheduler.advance(Duration.ofSeconds(92));
        assertEquals(List.of(10L, 12L, 17L, 32L, 62L, 92L), ticks, "after an idle tick: 2, 5, 15, then 30 s");

        oneClaim();
        scheduler.advance(Duration.ofSeconds(30)); // The tick at 122 s claims; the pass asks again until the queue is empty.
        assertEquals(List.of(122L, 122L), ticks.subList(6, 8));
        scheduler.advance(Duration.ofSeconds(8));
        assertEquals(List.of(123L, 125L, 130L), ticks.subList(8, ticks.size()), "1 s after the claim, then backing off again");
    }

    @Test void wakeRunsOneTickAtOnceAndConcurrentWakesShareIt() throws Exception {
        sender.start(Duration.ZERO);
        scheduler.advance(Duration.ofSeconds(60)); // Ticks at 0, 2, 7, 22 and 52 s; the next is due at 82 s.
        int before = ticks.size();
        assertEquals(52L, ticks.getLast());

        var pool = Executors.newFixedThreadPool(8);
        var gate = new CountDownLatch(1);
        try {
            var calls = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) calls.add(pool.submit(() -> { gate.await(); for (int n = 0; n < 50; n++) sender.wake(); return null; }));
            gate.countDown();
            for (var call : calls) call.get(30, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        scheduler.runDue();
        assertEquals(List.of(60L), ticks.subList(before, ticks.size()), "400 wakes from 8 threads: one tick, with no time passing");

        scheduler.advance(Duration.ofSeconds(22));
        assertEquals(List.of(60L, 62L, 67L, 82L), ticks.subList(before, ticks.size()),
                "the backoff starts again from 2 s, and the tick that was due at 82 s before the wake does not run as well");
    }

    @Test void aWakeBeforeStartDoesNothing() {
        sender.wake();
        scheduler.runDue();
        assertTrue(ticks.isEmpty());
        assertEquals(0, scheduler.pending());
    }

    @Test void thePauseHeldInMemoryStopsEveryClaimWithoutReadingTheDatabase() {
        when(repository.sendingPausedCached()).thenReturn(true);
        sender.start(Duration.ZERO);
        scheduler.advance(Duration.ofSeconds(60));
        assertTrue(ticks.isEmpty());
        verify(repository, never()).sendingPaused();
        verify(dmJobs, never()).claim(any(), any());
        // Nothing is read while paused, so the tick stays on the short interval and a resume is seen within a second.
        when(repository.sendingPausedCached()).thenReturn(false);
        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(List.of(61L), ticks);
    }

    @Test void theTickRecoversNoLeasesAndRunsNoPublishingCleanUp() {
        sender.start(Duration.ZERO);
        scheduler.advance(Duration.ofMinutes(5));
        assertTrue(ticks.size() > 10);
        verify(repository, never()).recoverExpiredLeases(any());
        verify(repository, never()).sendingPaused();
        verify(lifecycleJobs, never()).recover(any());
        verify(dmJobs, never()).recover(any());
        verify(broadcastJobs, never()).recover(any());
        verify(publications, never()).recover(any());
        verify(publications, never()).cancelOpen(any(), any());
        verify(pages, never()).dropPublishPending(any());
        // Every queue is still polled on every pass.
        verify(lifecycleJobs, atLeast(ticks.size())).claim(any(), any());
        verify(pages, times(ticks.size())).claimRefresh(any(), any());
        verify(publications, times(ticks.size())).claim(any(), any());
        verify(repository, times(ticks.size())).claimCatchUp(any(), any());
        verify(dmJobs, times(ticks.size())).claim(any(), any());
        verify(broadcastJobs, times(ticks.size())).claim(any(), any());
        // Pages are planned by the first tick (as after a wake) and after a tick with work, not on idle polls.
        verify(pages, times(1)).publishPending(anyInt());
    }

    @Test void publicationsArePlannedOnTheTickAfterAWakeAndAfterATickWithWork() {
        sender.start(Duration.ZERO);
        scheduler.advance(Duration.ofSeconds(60));
        verify(pages, times(1)).publishPending(anyInt());
        sender.wake();
        scheduler.runDue();
        verify(pages, times(2)).publishPending(anyInt());
        scheduler.advance(Duration.ofSeconds(30)); // Idle ticks at +2, +7 and +22 s plan nothing.
        verify(pages, times(2)).publishPending(anyInt());
        oneClaim();
        scheduler.advance(Duration.ofSeconds(22)); // The claiming tick itself does not plan; the one a second later does.
        scheduler.advance(Duration.ofSeconds(1));
        verify(pages, times(3)).publishPending(anyInt());
    }

    @Test void theNoticeTickBacksOffFromFiveToThirtySecondsAndWakes() {
        List<Long> claims = new ArrayList<>();
        when(notices.claim(any())).thenAnswer(call -> { claims.add(Duration.between(start, scheduler.now()).toSeconds()); return Optional.empty(); });
        notifier.start(scheduler, Duration.ofSeconds(10));
        scheduler.advance(Duration.ofSeconds(130));
        assertEquals(List.of(10L, 20L, 40L, 70L, 100L, 130L), claims, "after an idle tick: 10, 20, then 30 s");
        verify(notices, never()).recover(any());
        notifier.wake();
        notifier.wake();
        scheduler.runDue();
        assertEquals(List.of(130L, 130L), claims.subList(5, claims.size()), "two wakes, one tick, at once");
    }

    @Test void closingStopsTheTickForGood() {
        sender.start(Duration.ZERO);
        scheduler.advance(Duration.ofSeconds(10));
        int before = ticks.size();
        sender.close();
        sender.wake();
        scheduler.advance(Duration.ofMinutes(5));
        assertEquals(before, ticks.size());
        assertEquals(0, scheduler.pending(), "nothing reschedules itself after close");
    }
}
