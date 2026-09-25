package org.bunnys.bunnynexus.alerts.runtime;

import org.bunnys.bunnynexus.alerts.application.OwnershipRepository;
import org.bunnys.bunnynexus.alerts.domain.AlertIdentity;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Explicitly driven lifecycle. No threads, sleeps or I/O under the state lock; close is prompt. */
public final class RuntimeOwnership implements AutoCloseable {
    public enum State { NEW, ACQUIRING, ACTIVE, LOST, CLOSED }
    private final OwnershipRepository repository;
    private final SendAdmission admission;
    private final LongSupplier nanoTime;
    private final String processToken;
    private final Duration duration;
    private final long marginNanos;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private State state = State.NEW;
    private OwnershipRepository.Lease lease;
    private long validFrom, validFor;

    public RuntimeOwnership(OwnershipRepository repository, SendAdmission admission, String processToken,
                            Duration duration, Duration safetyMargin, LongSupplier nanoTime) {
        this.repository = Objects.requireNonNull(repository); this.admission = Objects.requireNonNull(admission);
        this.processToken = AlertIdentity.token(processToken); this.duration = Objects.requireNonNull(duration);
        OwnershipRepository.durationMillis(duration); this.nanoTime = Objects.requireNonNull(nanoTime);
        marginNanos = Objects.requireNonNull(safetyMargin).toNanos();
        if (marginNanos <= 0 || marginNanos >= duration.toNanos()) throw new IllegalArgumentException("Positive ownership safety margin required.");
    }
    public boolean acquire() { return refresh(true); }
    public boolean renew() { return refresh(false); }
    boolean usesAdmission(SendAdmission candidate) { return admission == candidate; }
    private boolean refresh(boolean acquiring) {
        if (!refreshing.compareAndSet(false, true)) return false;
        try {
            OwnershipRepository.Lease previous;
            synchronized (this) {
                if (acquiring ? state != State.NEW : !active()) return false;
                if (acquiring) state = State.ACQUIRING;
                previous = lease;
            }
            long started = nanoTime.getAsLong();
            Optional<OwnershipRepository.Lease> result = acquiring ? repository.acquire(new OwnershipRepository.Runtime(), processToken, duration) : repository.renew(previous, duration);
            synchronized (this) {
                if (state == State.CLOSED || state == State.LOST) return false;
                if (result.isEmpty()) { lose(); return false; }
                var next = result.get();
                if (!(next.resource() instanceof OwnershipRepository.Runtime) || !next.token().equals(processToken)
                        || (!acquiring && next.generation() != previous.generation())) { lose(); return false; }
                // Start the local deadline BEFORE the request: response latency can only shorten validity.
                long budget = Math.min(duration.toNanos(), Duration.between(next.checkedAt(), next.until()).toNanos()) - marginNanos;
                long elapsed = nanoTime.getAsLong() - started;
                if (budget <= 0 || elapsed < 0 || elapsed >= budget || (!acquiring && !active())) { lose(); return false; }
                lease = next; validFrom = started; validFor = budget; state = State.ACTIVE;
                return true;
            }
        } catch (RuntimeException | Error failure) { lose(); throw failure; }
        finally { refreshing.set(false); }
    }
    public synchronized boolean active() {
        if (state != State.ACTIVE) return false;
        long elapsed = nanoTime.getAsLong() - validFrom;
        if (elapsed < 0 || elapsed >= validFor) { lose(); return false; }
        return true;
    }
    public synchronized Optional<OwnershipRepository.Lease> current() { return active() ? Optional.of(lease) : Optional.empty(); }
    public synchronized State state() { active(); return state; }
    public synchronized void lose() { if (state != State.CLOSED) state = State.LOST; admission.close(); }
    @Override public synchronized void close() { state = State.CLOSED; admission.close(); }
    /** Explicit I/O step after local drain, never called by close or after a mere timeout. */
    public boolean releaseAfterDrain() {
        OwnershipRepository.Lease releasing;
        synchronized (this) {
            if (state != State.CLOSED || admission.snapshot().occupied() != 0 || refreshing.get()) return false;
            releasing = lease;
        }
        return releasing != null && repository.release(releasing);
    }
}
