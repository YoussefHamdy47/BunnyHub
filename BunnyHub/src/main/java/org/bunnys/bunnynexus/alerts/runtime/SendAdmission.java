package org.bunnys.bunnynexus.alerts.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.bunnys.bunnynexus.alerts.domain.AlertIdentity;

/**
 * Bounded local egress capacity, independent of BunnyHub's synchronous command executor.
 * This is not a job queue, a rate limiter, or distributed ownership. A single egress owner is still required.
 * No threads are created. Closing stops admission promptly and never forgets unresolved external work.
 */
public final class SendAdmission implements AutoCloseable {
    private final int capacity;
    private final Map<String, Permit> channels = new HashMap<>();
    private boolean closed;
    private int persistenceFailures;

    public SendAdmission(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Send capacity must be positive.");
        this.capacity = capacity;
    }
    /** Reserve before claiming a durable job; there is deliberately no waiting queue. */
    public synchronized Optional<Permit> tryReserve(String channelId) {
        AlertIdentity.snowflake(channelId);
        if (closed || persistenceFailures > 0 || channels.size() >= capacity || channels.containsKey(channelId))
            return Optional.empty();
        Permit permit = new Permit(channelId);
        channels.put(channelId, permit);
        return Optional.of(permit);
    }
    public record Snapshot(int capacity, int occupied, int persistenceFailures, boolean closed) {}
    public synchronized Snapshot snapshot() { return new Snapshot(capacity, channels.size(), persistenceFailures, closed); }
    @Override public synchronized void close() { closed = true; }

    /** Ownership follows this permit, not its channel key, so late callbacks cannot free a newer send. */
    public final class Permit {
        private final String channelId;
        private boolean protectedAttempt, transportStopped, receiptRecorded, persistenceFailed, released;
        private Permit(String channelId) { this.channelId = channelId; }

        /** Recheck immediately before submission; a completed receipt cannot start new external work. */
        public boolean transportAllowed() {
            synchronized (SendAdmission.this) {
                return protectedAttempt && !released && !transportStopped && !receiptRecorded
                        && !closed && persistenceFailures == 0;
            }
        }

        /** Call BEFORE the authorization transaction. If its commit becomes unknown, retain this permit. */
        public void protectAttempt() {
            synchronized (SendAdmission.this) {
                if (released || protectedAttempt || closed || persistenceFailures > 0)
                    throw new IllegalStateException("Permit cannot start authorization.");
                protectedAttempt = true;
            }
        }
        /** No job claimed, or abandoned before entering the authorization transaction. */
        public void cancelBeforeAttempt() {
            synchronized (SendAdmission.this) {
                if (released) return;
                if (protectedAttempt) throw new IllegalStateException("Attempt may exist; completion proof is required.");
                release();
            }
        }
        /** Only after storage proves no attempt committed, and transport was never invoked. */
        public void authorizationRejected() {
            synchronized (SendAdmission.this) {
                if (released) return;
                requireProtected();
                transportStopped = true;
                receiptRecorded = true;
                clearPersistenceFailure();
                release();
            }
        }
        /** Terminal transport completion or PROVEN cancellation; a timed-out/cancelled Future is insufficient. */
        public void confirmTransportStopped() {
            synchronized (SendAdmission.this) {
                if (released) return;
                requireProtected(); transportStopped = true; releaseIfComplete();
            }
        }
        /** Known committed receipt, including an explicit UNCERTAIN outcome; never an unknown DB commit. */
        public void confirmReceiptRecorded() {
            synchronized (SendAdmission.this) {
                if (released) return;
                requireProtected(); receiptRecorded = true; clearPersistenceFailure(); releaseIfComplete();
            }
        }
        /** Stop new admission while even one bounded completion cannot be persisted. */
        public void receiptPersistenceFailed() {
            synchronized (SendAdmission.this) {
                if (released || receiptRecorded) return;
                requireProtected();
                if (!persistenceFailed) { persistenceFailed = true; persistenceFailures++; }
            }
        }
        private void requireProtected() {
            if (!protectedAttempt) throw new IllegalStateException("No protected attempt.");
        }
        private void releaseIfComplete() { if (transportStopped && receiptRecorded) release(); }
        private void clearPersistenceFailure() {
            if (persistenceFailed) { persistenceFailed = false; persistenceFailures--; }
        }
        private void release() {
            channels.remove(channelId, this);
            released = true;
        }
    }
}
