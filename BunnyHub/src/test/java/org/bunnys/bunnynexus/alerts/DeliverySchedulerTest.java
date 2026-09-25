package org.bunnys.bunnynexus.alerts;

import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.bunnys.bunnynexus.alerts.runtime.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliverySchedulerTest {
    @Test void committedReceiptPreventsLateTransportSubmission() {
        var works = new ArrayList<ScheduledDelivery>();
        var scheduler = scheduler(1, works::add); ready(job("a", "100", "200")); scheduler.tick();
        var work = works.getFirst(); authorize(work);
        work.confirmReceiptRecorded();
        assertFalse(work.tryBeginTransport());
        assertEquals(1, admission.snapshot().occupied());
        work.confirmTransportStopped();
        assertEquals(0, admission.snapshot().occupied());
    }

    @Test void anotherFailedReceiptPausesAuthorizedButUnsubmittedWork() {
        var works = new ArrayList<ScheduledDelivery>();
        var scheduler = scheduler(1, works::add); ready(job("a", "100", "200")); scheduler.tick();
        var work = works.getFirst(); authorize(work);
        var other = admission.tryReserve("201").orElseThrow(); other.protectAttempt(); other.receiptPersistenceFailed();
        assertFalse(work.tryBeginTransport());
        other.confirmReceiptRecorded(); other.confirmTransportStopped();
        assertTrue(work.tryBeginTransport());
        assertFalse(work.tryBeginTransport());
        work.confirmTransportStopped(); work.confirmReceiptRecorded();
        assertEquals(0, admission.snapshot().occupied());
    }

    private void authorize(ScheduledDelivery work) {
        work.beginAuthorization();
        work.confirmAuthorized(work.job().authorize(work.job().lease().orElseThrow(), NOW,
                new DeliveryJob.Attempt("attempt", 1, "nonce", 1, 1, "v1", "a".repeat(64), Set.of(), NOW, NOW.plusSeconds(10))));
    }
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    private final AtomicLong nanos = new AtomicLong();
    private final SendAdmission admission = new SendAdmission(2);
    private final SchedulingRepository candidates = mock(SchedulingRepository.class);
    private final DeliveryQueue queue = mock(DeliveryQueue.class);
    private final OwnershipRepository owners = mock(OwnershipRepository.class);
    private final RuntimeOwnership ownership = new RuntimeOwnership(owners, admission, "process", Duration.ofSeconds(30), Duration.ofSeconds(1), nanos::get);
    private static DeliveryJob job(String id, String guild, String channel) {
        return DeliveryJob.ready(id, new DeliveryKey("event", channel), new DestinationRef(guild, channel, "dest-" + channel, "inc"), "sub-" + channel, NOW);
    }
    private DeliveryScheduler scheduler(int window, Consumer<ScheduledDelivery> handler) {
        when(owners.acquire(any(), anyString(), any())).thenReturn(Optional.of(new OwnershipRepository.Lease(new OwnershipRepository.Runtime(), "process", 1, NOW, NOW.plusSeconds(30))));
        ownership.acquire();
        return new DeliveryScheduler(candidates, queue, ownership, admission, window,
                new DeliveryScheduler.Budget(5, 2, 2, 2, Duration.ofSeconds(20), Duration.ofMillis(10)), handler, () -> "claim", nanos::get);
    }
    private void ready(DeliveryJob... jobs) {
        when(candidates.guilds(any(), anyInt())).thenReturn(List.of());
        when(candidates.due(any(), any(), any(), anyInt())).thenAnswer(call -> call.getArgument(0) == DeliveryJob.State.READY ? List.of(jobs) : List.of());
        when(queue.claim(anyString(), anyString(), any())).thenAnswer(call -> {
            assertTrue(admission.snapshot().occupied() > 0, "Reserve capacity before claim");
            return Arrays.stream(jobs).filter(j -> j.id().equals(call.getArgument(0))).findFirst().map(j -> j.claim("claim", NOW, NOW.plusSeconds(20)));
        });
    }
    @Test void fairWindowCannotBeFilledByOneChannelAndRotatesGuilds() {
        var window = new FairCandidateWindow(3);
        assertTrue(window.offer(job("a", "100", "200"))); assertFalse(window.offer(job("a2", "100", "200")));
        assertTrue(window.offer(job("b", "100", "201"))); assertTrue(window.offer(job("c", "101", "202")));
        assertFalse(window.offer(job("d", "102", "203")));
        assertEquals("a", window.poll().orElseThrow().id()); assertEquals("c", window.poll().orElseThrow().id());
        assertEquals("b", window.poll().orElseThrow().id()); assertTrue(window.poll().isEmpty());
    }
    @Test void holdsCapacityAfterHandlerReturnsAndEnforcesPacing() {
        var works = new ArrayList<ScheduledDelivery>(); var scheduler = scheduler(3, works::add);
        ready(job("a", "100", "200"), job("b", "101", "201"));
        assertEquals(1, scheduler.tick()); assertEquals(1, admission.snapshot().occupied());
        assertEquals(0, scheduler.tick()); nanos.addAndGet(Duration.ofMillis(10).toNanos());
        assertEquals(1, scheduler.tick()); assertEquals(2, admission.snapshot().occupied());
        assertEquals(0, scheduler.tick()); works.forEach(ScheduledDelivery::cancelBeforeAuthorization);
        assertEquals(0, admission.snapshot().occupied());
    }
    @Test void protectedFailureStopsRuntimeWithoutForgettingPossibleAttempt() {
        var scheduler = scheduler(1, work -> { work.beginAuthorization(); throw new IllegalStateException("unknown commit"); });
        ready(job("a", "100", "200"));
        assertThrows(IllegalStateException.class, scheduler::tick); assertEquals(RuntimeOwnership.State.LOST, ownership.state());
        assertEquals(1, admission.snapshot().occupied()); assertEquals(0, scheduler.tick());
    }
    @Test void ownershipLossDuringClaimNeverReachesTheHandler() {
        var works = new ArrayList<ScheduledDelivery>(); var scheduler = scheduler(1, works::add); var job = job("a", "100", "200"); ready(job);
        doAnswer(call -> { ownership.lose(); return Optional.of(job.claim("claim", NOW, NOW.plusSeconds(20))); })
                .when(queue).claim(anyString(), anyString(), any());
        assertEquals(0, scheduler.tick()); assertTrue(works.isEmpty()); assertEquals(0, admission.snapshot().occupied());
    }
    @Test void closeRetainsWorkAndTransportSubmissionIsOneShotAndOwnershipChecked() {
        var works = new ArrayList<ScheduledDelivery>(); var scheduler = scheduler(1, works::add); ready(job("a", "100", "200")); scheduler.tick();
        var work = works.getFirst(); assertFalse(work.tryBeginTransport()); work.beginAuthorization();
        assertFalse(work.tryBeginTransport());
        work.confirmAuthorized(work.job().authorize(work.job().lease().orElseThrow(), NOW,
                new DeliveryJob.Attempt("attempt", 1, "nonce", 1, 1, "v1", "a".repeat(64), Set.of(), NOW, NOW.plusSeconds(10))));
        assertTrue(work.tryBeginTransport()); assertFalse(work.tryBeginTransport());
        assertThrows(IllegalStateException.class, work::authorizationRejected);
        scheduler.close(); assertEquals(1, admission.snapshot().occupied()); assertFalse(work.tryBeginTransport());
        work.confirmReceiptRecorded(); assertEquals(1, admission.snapshot().occupied());
        work.confirmTransportStopped(); assertEquals(0, admission.snapshot().occupied());
    }
    @Test void fullWindowAdvancesOnlyVisitedGuildsSoNextGuildGetsItsTurn() {
        var works = new ArrayList<ScheduledDelivery>(); var scheduler = scheduler(1, works::add);
        var a = job("a", "100", "200"); var b = job("b", "101", "201"); ready(a, b);
        when(candidates.guilds(eq(Optional.empty()), anyInt())).thenReturn(List.of("100", "101"));
        when(candidates.guilds(eq(Optional.of("100")), anyInt())).thenReturn(List.of("101"));
        when(candidates.due(any(), eq(Optional.of("100")), any(), anyInt())).thenReturn(List.of(a));
        when(candidates.due(any(), eq(Optional.of("101")), any(), anyInt())).thenReturn(List.of(b));
        when(candidates.due(any(), eq(Optional.empty()), any(), anyInt())).thenReturn(List.of());
        scheduler.tick(); assertEquals("a", works.getFirst().job().id()); works.getFirst().cancelBeforeAuthorization();
        nanos.addAndGet(Duration.ofMillis(10).toNanos()); scheduler.tick(); assertEquals("b", works.getLast().job().id());
    }
    @Test void recoveryHasItsOwnBudgetAndUsesExistingUncertainTransition() {
        var scheduler = scheduler(1, ignored -> fail("Recovery cannot dispatch"));
        var sending = AlertFixtures.sending();
        when(candidates.expired(eq(DeliveryJob.State.SENDING), anyInt())).thenReturn(List.of(sending));
        when(candidates.expired(eq(DeliveryJob.State.LEASED), anyInt())).thenReturn(List.of());
        when(queue.recoverExpired(sending.id(), sending.revision())).thenReturn(Optional.of(sending.recoverExpired(AlertFixtures.NOW.plusSeconds(30))));
        assertEquals(1, scheduler.recoverTick()); verify(queue, never()).claim(any(), any(), any());
    }
    @Test void delayedAuthorizationReplyCannotExtendTheSendDeadline() {
        var works = new ArrayList<ScheduledDelivery>(); var scheduler = scheduler(1, works::add); ready(job("a", "100", "200")); scheduler.tick();
        var work = works.getFirst(); work.beginAuthorization(); nanos.set(Duration.ofSeconds(10).toNanos());
        work.confirmAuthorized(work.job().authorize(work.job().lease().orElseThrow(), NOW,
                new DeliveryJob.Attempt("attempt", 1, "nonce", 1, 1, "v1", "a".repeat(64), Set.of(), NOW, NOW.plusSeconds(10))));
        assertFalse(work.tryBeginTransport()); assertEquals(1, admission.snapshot().occupied());
        assertThrows(IllegalStateException.class, work::authorizationRejected);
    }
    @Test void overlappingTicksDoNotMultiplyDatabaseWork() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1); var finish = new java.util.concurrent.CountDownLatch(1);
        var scheduler = scheduler(1, ScheduledDelivery::cancelBeforeAuthorization); ready(job("a", "100", "200"));
        when(candidates.guilds(any(), anyInt())).thenAnswer(call -> { entered.countDown(); assertTrue(finish.await(5, java.util.concurrent.TimeUnit.SECONDS)); return List.of(); });
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var tick = pool.submit(scheduler::tick);
            try { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, scheduler.tick()); assertEquals(0, scheduler.recoverTick()); }
            finally { finish.countDown(); }
            assertEquals(1, tick.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    @Test void failedClaimsConsumeTheTickBudgetRatherThanScanningTheWholeWindow() {
        var scheduler = scheduler(5, ignored -> fail("No successful claims"));
        ready(job("a", "100", "200"), job("b", "101", "201"), job("c", "102", "202"));
        doAnswer(call -> { nanos.addAndGet(Duration.ofMillis(10).toNanos()); return Optional.empty(); })
                .when(queue).claim(anyString(), anyString(), any());
        assertEquals(0, scheduler.tick()); verify(queue, times(2)).claim(anyString(), anyString(), any());
        assertEquals(0, admission.snapshot().occupied());
    }
}
