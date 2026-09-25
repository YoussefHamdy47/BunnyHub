package org.bunnys.bunnynexus.alerts.runtime;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerFetcher;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class GamerPowerPollerTest {
    final OwnershipRepository owners = mock(OwnershipRepository.class);
    final PollRepository polls = mock(PollRepository.class);
    final AtomicLong nanos = new AtomicLong();
    final AtomicBoolean capacity = new AtomicBoolean(true);
    final AtomicInteger fetched = new AtomicInteger();
    final OwnershipRepository.Lease lease = new OwnershipRepository.Lease(GamerPowerPoller.SOURCE, "owner", 3,
            Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
    GamerPowerFetcher.Result response = new GamerPowerFetcher.Result(GamerPowerFetcher.State.FETCHED, GamerPowerFetcher.Failure.NONE, 200, Optional.empty());
    GamerPowerPoller poller() {
        return poller(() -> { fetched.incrementAndGet(); return response; });
    }
    GamerPowerPoller poller(Supplier<GamerPowerFetcher.Result> fetch) {
        when(owners.acquire(any(), anyString(), any())).thenReturn(Optional.of(lease));
        when(polls.begin(any(), anyString(), any())).thenReturn(true);
        when(polls.finish(any(), anyString(), any(), any())).thenReturn(true);
        return new GamerPowerPoller(owners, polls, fetch, () -> {}, Duration.ofSeconds(10), capacity::get,
                Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofSeconds(10), nanos::get);
    }
    @Test void reserveFetchFinishAndReleaseAreOrderedAndShareAttempt() {
        try (var p = poller()) {
            assertEquals(GamerPowerPoller.Result.RECORDED, p.tick()); assertEquals(1, fetched.get());
            var attempt = org.mockito.ArgumentCaptor.forClass(String.class);
            var order = inOrder(owners, polls);
            order.verify(owners).acquire(eq(GamerPowerPoller.SOURCE), anyString(), any());
            order.verify(polls).begin(eq(lease), attempt.capture(), any());
            order.verify(polls).finish(eq(lease), eq(attempt.getValue()), any(), eq(new PollRepository.Completion(PollRepository.Outcome.SUCCESS, 0, 0)));
            order.verify(owners).release(lease);
        }
    }
    @Test void pressureAndNotDueNeverFetch() {
        try (var p = poller()) {
            capacity.set(false); assertEquals(GamerPowerPoller.Result.BACKPRESSURE, p.tick()); verifyNoInteractions(owners, polls);
            capacity.set(true); when(polls.begin(any(), anyString(), any())).thenReturn(false);
            assertEquals(GamerPowerPoller.Result.NOT_DUE, p.tick()); assertEquals(0, fetched.get());
        }
    }
    @Test void unknownReservationOrCompletionCannotCauseASecondFetchOrRelease() {
        try (var p = poller()) {
            when(polls.begin(any(), anyString(), any())).thenThrow(new IllegalStateException("uncertain"));
            assertEquals(GamerPowerPoller.Result.UNCERTAIN, p.tick()); assertEquals(0, fetched.get()); verify(owners, never()).release(any());
        }
        reset(owners, polls);
        try (var p = poller()) {
            when(polls.finish(any(), anyString(), any(), any())).thenThrow(new IllegalStateException("uncertain"));
            assertEquals(GamerPowerPoller.Result.UNCERTAIN, p.tick()); assertEquals(1, fetched.get()); verify(owners, never()).release(any());
        }
    }
    @Test void expiredBudgetAndChangedPressureAfterReservationSkipFetch() {
        try (var p = poller()) {
            when(polls.begin(any(), anyString(), any())).thenAnswer(i -> { nanos.set(Duration.ofSeconds(45).toNanos()); return true; });
            assertEquals(GamerPowerPoller.Result.RECORDED, p.tick()); assertEquals(0, fetched.get());
            verify(polls).finish(any(), anyString(), any(), eq(new PollRepository.Completion(PollRepository.Outcome.FAILURE, 0, 0)));
        }
        reset(owners, polls); nanos.set(0);
        try (var p = poller()) {
            when(polls.begin(any(), anyString(), any())).thenAnswer(i -> { capacity.set(false); return true; });
            assertEquals(GamerPowerPoller.Result.RECORDED, p.tick()); assertEquals(0, fetched.get());
            verify(polls).finish(any(), anyString(), any(), eq(new PollRepository.Completion(PollRepository.Outcome.BACKPRESSURE, 0, 0)));
        }
    }
    @Test void rateLimitPauseIsPersistedAndLateFinishCannotReleaseNewOwner() {
        try (var p = poller()) {
            response = new GamerPowerFetcher.Result(GamerPowerFetcher.State.FAILED, GamerPowerFetcher.Failure.HTTP_STATUS, 429, Optional.empty());
            when(polls.finish(any(), anyString(), any(), any())).thenReturn(false);
            assertEquals(GamerPowerPoller.Result.LOST, p.tick());
            verify(polls).finish(any(), anyString(), any(), eq(new PollRepository.Completion(PollRepository.Outcome.PAUSED, 0, 0)));
            verify(owners, never()).release(any());
        }
    }
    @Test void malformedBatchIsRecordedAsFailureRatherThanPartialSuccess() {
        try (var p = poller()) {
            var batch = new org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerIntake.Batch(
                    org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerIntake.State.FAILED,
                    org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerIntake.Problem.MALFORMED_RESPONSE, List.of(), 0);
            response = new GamerPowerFetcher.Result(GamerPowerFetcher.State.FAILED, GamerPowerFetcher.Failure.INVALID_FEED, 200, Optional.of(batch));
            assertEquals(GamerPowerPoller.Result.RECORDED, p.tick());
            verify(polls).finish(any(), anyString(), any(), eq(new PollRepository.Completion(PollRepository.Outcome.FAILURE, 0, 0)));
        }
    }
    @Test void pressureCallbackCannotConsumeLeaseBudgetThenStartFetch() {
        var checks = new AtomicInteger();
        when(owners.acquire(any(), anyString(), any())).thenReturn(Optional.of(lease));
        when(polls.begin(any(), anyString(), any())).thenReturn(true);
        when(polls.finish(any(), anyString(), any(), any())).thenReturn(true);
        try (var p = new GamerPowerPoller(owners, polls, () -> { fetched.incrementAndGet(); return response; }, () -> {},
                Duration.ofSeconds(10), () -> { if (checks.incrementAndGet() == 2) nanos.set(Duration.ofSeconds(55).toNanos()); return true; },
                Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofSeconds(10), nanos::get)) {
            assertEquals(GamerPowerPoller.Result.RECORDED, p.tick()); assertEquals(0, fetched.get());
            verify(polls).finish(any(), anyString(), any(), eq(new PollRepository.Completion(PollRepository.Outcome.FAILURE, 0, 0)));
        }
    }
    @Test void closeDuringPressureCallbackCannotStartFetch() {
        var checks = new AtomicInteger(); var reference = new AtomicReference<GamerPowerPoller>();
        when(owners.acquire(any(), anyString(), any())).thenReturn(Optional.of(lease));
        when(polls.begin(any(), anyString(), any())).thenReturn(true);
        when(polls.finish(any(), anyString(), any(), any())).thenReturn(true);
        try (var p = new GamerPowerPoller(owners, polls, () -> { fetched.incrementAndGet(); return response; }, () -> {},
                Duration.ofSeconds(10), () -> { if (checks.incrementAndGet() == 2) reference.get().close(); return true; },
                Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofSeconds(10), nanos::get)) {
            reference.set(p); assertEquals(GamerPowerPoller.Result.RECORDED, p.tick()); assertEquals(0, fetched.get());
        }
    }
    @Test void concurrentTicksAndCloseDoNotReleaseDuringFetch() throws Exception {
        var entered = new CountDownLatch(1); var exit = new CountDownLatch(1);
        try (var p = poller(() -> {
            entered.countDown(); try { assertTrue(exit.await(3, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return response;
        }); var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(p::tick);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS)); assertEquals(GamerPowerPoller.Result.BUSY, p.tick());
                p.close(); assertEquals(GamerPowerPoller.Result.CLOSED, p.tick()); verify(owners, never()).release(any());
            } finally { exit.countDown(); }
            assertEquals(GamerPowerPoller.Result.RECORDED, future.get(3, TimeUnit.SECONDS));
            verify(owners).release(lease);
        }
    }
}
