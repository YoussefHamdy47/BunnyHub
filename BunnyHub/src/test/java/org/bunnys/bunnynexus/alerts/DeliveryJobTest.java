package org.bunnys.bunnynexus.alerts;

import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.bunnys.bunnynexus.alerts.AlertFixtures.*;
import static org.bunnys.bunnynexus.alerts.domain.DeliveryJob.*;

class DeliveryJobTest {
    @Test void rejectsImpossiblePersistedCountersAndBackwardClockCallbacks() {
        var s = ready().snapshot();
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(s.id(), s.key(), s.destination(), s.subscriptionId(),
                s.createdAt(), s.state(), s.dueAt(), 1, 1, 0, s.lease(), s.attempt(), s.reason(), s.messageId()));
        var job = sending();
        assertThrows(IllegalStateException.class, () -> job.complete(job.lease().orElseThrow(), "attempt-1",
                NOW.minusSeconds(1), new Accepted("500"), Optional.empty()));
    }
    @Test void persistedSendingSnapshotRetainsUncertaintyAcrossProcessRecovery() {
        var beforeCrash = sending();
        var restored = DeliveryJob.restore(beforeCrash.snapshot());
        assertEquals(beforeCrash.snapshot(), restored.snapshot());
        var uncertain = restored.recoverExpired(NOW.plusSeconds(30));
        assertEquals(State.UNCERTAIN, uncertain.state());
        assertEquals(beforeCrash.attempt(), uncertain.attempt());
        var ready = DeliveryJob.restore(ready().snapshot());
        assertEquals(State.LEASED, ready.claim("worker", NOW, NOW.plusSeconds(30)).state());
    }
    @Test void malformedPersistenceCannotHideSendingAttemptOrInventReceipt() {
        var s = sending().snapshot();
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(s.id(), s.key(), s.destination(), s.subscriptionId(),
                s.createdAt(), s.state(), s.dueAt(), s.revision(), s.generation(), 0, s.lease(), Optional.empty(), s.reason(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(s.id(), s.key(), s.destination(), s.subscriptionId(),
                s.createdAt(), State.SENT, s.dueAt(), s.revision(), s.generation(), s.attemptCount(), Optional.empty(), s.attempt(), s.reason(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(s.id(), s.key(), s.destination(), s.subscriptionId(),
                s.createdAt(), s.state(), s.dueAt(), s.revision(), s.generation() + 1, s.attemptCount(), s.lease(), s.attempt(), s.reason(), Optional.empty()));
    }
    @Test void claimAndAuthorizationHaveSeparateAttemptAccounting() {
        var ready = ready(); var leased = leased(); var sending = sending();
        assertEquals(State.READY, ready.state());
        assertEquals(0, leased.attemptCount());
        assertEquals(1, sending.attemptCount());
        assertEquals(3, sending.revision());
        assertEquals(ready.key(), sending.key());
    }
    @Test void crashBeforeAuthorizationRecoversReadyButCrashAfterAuthorizationIsUncertain() {
        assertEquals(State.READY, leased().recoverExpired(NOW.plusSeconds(30)).state());
        var uncertain = sending().recoverExpired(NOW.plusSeconds(30));
        assertEquals(State.UNCERTAIN, uncertain.state());
        assertEquals("attempt-1", uncertain.attempt().orElseThrow().id());
        assertThrows(IllegalStateException.class, () -> uncertain.claim("w2", NOW.plusSeconds(31), NOW.plusSeconds(60)));
        assertThrows(IllegalStateException.class, () -> leased().recoverExpired(NOW.plusSeconds(29)));
    }
    @Test void staleGenerationCannotAuthorizeEvenWhenTokenIsReused() {
        var old = leased();
        var next = old.recoverExpired(NOW.plusSeconds(30)).claim("worker-1", NOW.plusSeconds(30), NOW.plusSeconds(60));
        assertEquals(2, next.generation());
        assertThrows(IllegalStateException.class, () -> next.authorize(old.lease().orElseThrow(), NOW.plusSeconds(30), attempt("a2", 1, NOW.plusSeconds(30))));
    }
    @Test void leaseExpiryIsExclusiveAndRenewalDoesNotChangeOwnershipGeneration() {
        var job = leased(); var lease = job.lease().orElseThrow();
        assertThrows(IllegalStateException.class, () -> job.authorize(lease, NOW.plusSeconds(30), attempt("a", 1, NOW.plusSeconds(30))));
        var renewed = job.renew(lease, NOW.plusSeconds(29), NOW.plusSeconds(60));
        assertEquals(job.generation(), renewed.generation());
        assertEquals(State.SENDING, renewed.authorize(lease, NOW.plusSeconds(30), attempt("a", 1, NOW.plusSeconds(30))).state());
        assertThrows(IllegalArgumentException.class, () -> job.renew(lease, NOW, NOW.plusSeconds(20)));
    }
    @Test void lateOrForeignCallbacksCannotRecordSuccess() {
        var job = sending(); var lease = job.lease().orElseThrow();
        assertThrows(IllegalStateException.class, () -> job.complete(lease, "another-attempt", NOW, new Accepted("500"), Optional.empty()));
        assertThrows(IllegalStateException.class, () -> job.complete(new Lease("other", 1, lease.until()), "attempt-1", NOW, new Accepted("500"), Optional.empty()));
        assertThrows(IllegalStateException.class, () -> job.complete(lease, "attempt-1", NOW.plusSeconds(30), new Accepted("500"), Optional.empty()));
        var uncertain = job.recoverExpired(NOW.plusSeconds(30));
        assertThrows(IllegalStateException.class, () -> uncertain.complete(lease, "attempt-1", NOW.plusSeconds(31), new Accepted("500"), Optional.empty()));
        assertThrows(IllegalStateException.class, () -> uncertain.reconcileAccepted("other", "500"));
        assertEquals(State.SENT, uncertain.reconcileAccepted("attempt-1", "500").state());
    }
    @Test void unknownOutcomeNeverRetriesEvenIfRetryTimeIsSupplied() {
        var job = sending();
        var next = job.complete(job.lease().orElseThrow(), "attempt-1", NOW, new Unknown(), Optional.of(NOW.plusSeconds(5)));
        assertEquals(State.UNCERTAIN, next.state());
        assertTrue(next.lease().isEmpty());
        assertThrows(IllegalStateException.class, () -> next.claim("w", NOW.plusSeconds(5), NOW.plusSeconds(20)));
    }
    @Test void definiteRejectionRetriesSameJobWithNewAttemptAndStableNonce() {
        var job = sending(); var due = NOW.plusSeconds(5);
        var retry = job.complete(job.lease().orElseThrow(), "attempt-1", NOW, new DefinitelyRejected(true), Optional.of(due));
        assertEquals(State.RETRY_WAIT, retry.state());
        assertThrows(IllegalStateException.class, () -> retry.claim("w2", NOW, NOW.plusSeconds(30)));
        var claimed = retry.claim("w2", due, NOW.plusSeconds(30));
        assertThrows(IllegalArgumentException.class, () -> claimed.authorize(claimed.lease().orElseThrow(), due, attempt("attempt-1", 2, due)));
        var second = claimed.authorize(claimed.lease().orElseThrow(), due, attempt("attempt-2", 2, due));
        assertEquals(2, second.attemptCount());
        assertEquals(job.key(), second.key());
        assertEquals(job.attempt().orElseThrow().nonce(), second.attempt().orElseThrow().nonce());
    }
    @Test void terminalJobsCannotBeClaimedOrReauthorized() {
        var sending = sending(); var lease = sending.lease().orElseThrow();
        var sent = sending.complete(lease, "attempt-1", NOW, new Accepted("500"), Optional.empty());
        var failed = sending.complete(lease, "attempt-1", NOW, new DefinitelyRejected(false), Optional.of(NOW.plusSeconds(5)));
        var skipped = leased().skip(leased().lease().orElseThrow(), NOW, Reason.CONFIGURATION_CHANGED);
        assertEquals("500", sent.messageId().orElseThrow());
        assertEquals(State.FAILED, failed.state());
        for (var terminal : List.of(sent, failed, skipped)) {
            assertThrows(IllegalStateException.class, () -> terminal.claim("w", NOW, NOW.plusSeconds(60)));
            assertThrows(IllegalStateException.class, () -> terminal.authorize(lease, NOW, attempt("a2", 2, NOW)));
        }
    }
    @Test void preflightDeferralDoesNotConsumeAttempts() {
        var job = leased();
        var deferred = job.defer(job.lease().orElseThrow(), NOW, NOW.plusSeconds(5));
        assertEquals(0, deferred.attemptCount());
        assertEquals(State.RETRY_WAIT, deferred.state());
    }
    @Test void retryBudgetHonorsAttemptsAgeExpiryAndRemoteBackoff() {
        var p = new RetryPolicy(2, Duration.ofHours(1), Duration.ofSeconds(5), Duration.ofMinutes(15));
        var job = sending();
        assertEquals(NOW.plusSeconds(5), p.next(job, NOW, NOW.plusSeconds(1000), Optional.empty(), 0.5).orElseThrow());
        assertEquals(NOW.plusSeconds(90), p.next(job, NOW, NOW.plusSeconds(1000), Optional.of(NOW.plusSeconds(90)), 0).orElseThrow());
        assertTrue(p.next(job, NOW, NOW.plusSeconds(90), Optional.of(NOW.plusSeconds(90)), 0).isEmpty());
        assertTrue(p.next(job, NOW.plusSeconds(3600), NOW.plusSeconds(5000), Optional.empty(), 0).isEmpty());
        var single = new RetryPolicy(1, Duration.ofHours(1), Duration.ofSeconds(5), Duration.ofMinutes(15));
        assertTrue(single.next(job, NOW, NOW.plusSeconds(1000), Optional.empty(), 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> p.next(job, NOW, NOW.plusSeconds(1000), Optional.empty(), Double.NaN));
    }
}
