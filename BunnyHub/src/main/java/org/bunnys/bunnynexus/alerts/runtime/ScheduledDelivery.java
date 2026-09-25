package org.bunnys.bunnynexus.alerts.runtime;

import org.bunnys.bunnynexus.alerts.application.OwnershipRepository;
import org.bunnys.bunnynexus.alerts.domain.DeliveryJob;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Capacity belongs to the asynchronous operation, not to the scheduler tick or a returned Future. */
public final class ScheduledDelivery {
    private final DeliveryJob job;
    private final SendAdmission.Permit permit;
    private final RuntimeOwnership ownership;
    private final LongSupplier nanoTime;
    private boolean protectedAttempt, submitted, stopped, rejected;
    private boolean authorized;
    private long authorizationStarted, authorizationBudget;
    ScheduledDelivery(DeliveryJob job, SendAdmission.Permit permit, RuntimeOwnership ownership, LongSupplier nanoTime) {
        this.job = job; this.permit = permit; this.ownership = ownership; this.nanoTime = nanoTime;
    }
    public DeliveryJob job() { return job; }
    /** Call before the authorization transaction; use this runtime lease in AuthorizationRequest. */
    public synchronized OwnershipRepository.Lease beginAuthorization() {
        if (protectedAttempt || rejected) throw new IllegalStateException("Authorization already started or work cancelled.");
        var lease = ownership.current().orElseThrow(() -> new IllegalStateException("Runtime ownership lost."));
        permit.protectAttempt(); protectedAttempt = true; authorizationStarted = nanoTime.getAsLong(); return lease;
    }
    /** Supply only a known committed repository result, never an inferred/unknown commit. */
    public synchronized void confirmAuthorized(DeliveryJob sending) {
        if (!protectedAttempt || authorized || rejected || sending.state() != DeliveryJob.State.SENDING
                || !sending.id().equals(job.id()) || !sending.key().equals(job.key()) || !sending.destination().equals(job.destination())
                || !sending.subscriptionId().equals(job.subscriptionId()) || sending.revision() <= job.revision()
                || sending.generation() != job.generation() || !sending.lease().orElseThrow().token().equals(job.lease().orElseThrow().token())
                || sending.attemptCount() != job.attemptCount() + 1) throw new IllegalArgumentException("Wrong authorized attempt.");
        var attempt = sending.attempt().orElseThrow(); var deadline = attempt.validUntil();
        if (sending.lease().orElseThrow().until().isBefore(deadline)) deadline = sending.lease().orElseThrow().until();
        authorizationBudget = Duration.between(attempt.startedAt(), deadline).toNanos(); authorized = true;
    }
    /** Check conservative elapsed authorization time and ownership immediately before submitting, once. */
    public synchronized boolean tryBeginTransport() {
        long elapsed = nanoTime.getAsLong() - authorizationStarted;
        if (!authorized || submitted || stopped || rejected || elapsed < 0 || elapsed >= authorizationBudget
                || !ownership.active() || !permit.transportAllowed()) return false;
        submitted = true; return true;
    }
    public synchronized void cancelBeforeAuthorization() {
        permit.cancelBeforeAttempt(); rejected = true;
    }
    /** Storage must prove no attempt committed, and no transport may have been submitted. */
    public synchronized void authorizationRejected() {
        if (submitted || authorized) throw new IllegalStateException("An authorized attempt cannot be treated as uncommitted.");
        permit.authorizationRejected(); rejected = true;
    }
    public synchronized void confirmTransportStopped() { permit.confirmTransportStopped(); stopped = true; }
    public synchronized void confirmReceiptRecorded() { permit.confirmReceiptRecorded(); }
    public void receiptPersistenceFailed() { permit.receiptPersistenceFailed(); }
    synchronized void handlerFailed() {
        if (protectedAttempt) permit.receiptPersistenceFailed();
        else { permit.cancelBeforeAttempt(); rejected = true; }
    }
}
