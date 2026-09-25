package org.bunnys.bunnynexus.alerts.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

/**
 * Immutable transition engine. Repositories MUST compare revision, state, token and generation atomically.
 * Returning a new value is not persistence, a lease claim, or permission to contact Discord.
 */
public final class DeliveryJob {
    public enum State { READY, LEASED, SENDING, RETRY_WAIT, SENT, SKIPPED, FAILED, UNCERTAIN }
    public enum Reason { CREATED, CLAIMED, AUTHORIZED, ACCEPTED, NONACCEPTANCE, UNKNOWN_OUTCOME,
        LEASE_EXPIRED, CONFIGURATION_CHANGED, OFFER_INELIGIBLE, PERMANENT_REJECTION, RETRIES_EXHAUSTED,
        RECONCILED, OPERATOR_CLOSED }
    public record Lease(String token, long generation, Instant until) {
        public Lease {
            token = AlertIdentity.token(token); Objects.requireNonNull(until);
            if (generation < 1) throw new IllegalArgumentException("Invalid lease generation.");
        }
    }
    /** Freeze exactly the payload and configuration authorized by the storage transaction. */
    public record Attempt(String id, int ordinal, String nonce, long contentRevision, long subscriptionRevision,
                          String templateVersion, String payloadHash, Set<String> roleIds,
                          Instant startedAt, Instant validUntil) {
        public Attempt {
            id = token(id); nonce = token(nonce); templateVersion = token(templateVersion);
            if (ordinal < 1 || contentRevision < 1 || subscriptionRevision < 1)
                throw new IllegalArgumentException("Attempt revisions and ordinal must be positive.");
            if (payloadHash == null || !payloadHash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Expected SHA-256 payload hash.");
            Objects.requireNonNull(roleIds);
            if (roleIds.size() > Subscription.MAX_ROLE_IDS) throw new IllegalArgumentException("Too many roles.");
            roleIds = Set.copyOf(roleIds); roleIds.forEach(AlertIdentity::snowflake);
            Objects.requireNonNull(startedAt); Objects.requireNonNull(validUntil);
            if (!startedAt.isBefore(validUntil)) throw new IllegalArgumentException("Attempt window has ended.");
        }
    }
    public sealed interface Outcome permits Accepted, DefinitelyRejected, Unknown {}
    public record Accepted(String messageId) implements Outcome {
        public Accepted { messageId = snowflake(messageId); }
    }
    /** Only transport proof of nonacceptance permits retry; timeout alone never constructs this outcome. */
    public record DefinitelyRejected(boolean retryable) implements Outcome {}
    public record Unknown() implements Outcome {}

    private final String id;
    private final DeliveryKey key;
    private final DestinationRef destination;
    private final String subscriptionId;
    private final Instant createdAt;
    private final State state;
    private final Instant dueAt;
    private final long revision, generation;
    private final int attemptCount;
    private final Lease lease;
    private final Attempt attempt;
    private final Reason reason;
    private final String messageId;

    /** Storage-neutral snapshot. Adapters own BSON/schema versions and must reject unsupported versions. */
    public record Snapshot(String id, DeliveryKey key, DestinationRef destination, String subscriptionId,
                           Instant createdAt, State state, Instant dueAt, long revision, long generation,
                           int attemptCount, Optional<Lease> lease, Optional<Attempt> attempt,
                           Reason reason, Optional<String> messageId) {
        public Snapshot {
            id = token(id); subscriptionId = token(subscriptionId);
            Objects.requireNonNull(key); Objects.requireNonNull(destination); Objects.requireNonNull(createdAt);
            Objects.requireNonNull(state); Objects.requireNonNull(dueAt); Objects.requireNonNull(lease);
            Objects.requireNonNull(attempt); Objects.requireNonNull(reason); Objects.requireNonNull(messageId);
            if (!key.channelId().equals(destination.channelId()) || revision < 1 || generation < 0 || attemptCount < 0
                    || generation >= revision || attemptCount > generation
                    || dueAt.isBefore(createdAt)) throw new IllegalArgumentException("Invalid job snapshot.");
            if ((state == State.LEASED || state == State.SENDING) != lease.isPresent())
                throw new IllegalArgumentException("State/lease mismatch.");
            if (lease.isPresent() && (lease.get().generation() != generation || !lease.get().until().isAfter(createdAt)))
                throw new IllegalArgumentException("Invalid persisted lease.");
            if ((attemptCount > 0) != attempt.isPresent()) throw new IllegalArgumentException("Attempt count mismatch.");
            if (attempt.isPresent()) {
                Attempt a = attempt.get();
                if (a.ordinal() != attemptCount || a.startedAt().isBefore(createdAt))
                    throw new IllegalArgumentException("Invalid persisted attempt.");
                Subscription.checkedRoles(a.roleIds(), destination.guildId());
            }
            if ((state == State.SENDING || state == State.UNCERTAIN || state == State.SENT) && attempt.isEmpty())
                throw new IllegalArgumentException("State requires an attempt.");
            if ((state == State.SENT) != messageId.isPresent()) throw new IllegalArgumentException("Receipt/state mismatch.");
            messageId.ifPresent(AlertIdentity::snowflake);
        }
    }
    public Snapshot snapshot() {
        return new Snapshot(id, key, destination, subscriptionId, createdAt, state, dueAt, revision, generation,
                attemptCount, lease(), attempt(), reason, messageId());
    }
    public static DeliveryJob restore(Snapshot s) {
        Objects.requireNonNull(s);
        return new DeliveryJob(s.id(), s.key(), s.destination(), s.subscriptionId(), s.createdAt(), s.state(),
                s.dueAt(), s.revision(), s.generation(), s.attemptCount(), s.lease().orElse(null),
                s.attempt().orElse(null), s.reason(), s.messageId().orElse(null));
    }

    private DeliveryJob(String id, DeliveryKey key, DestinationRef destination, String subscriptionId,
                        Instant createdAt, State state, Instant dueAt, long revision, long generation,
                        int attemptCount, Lease lease, Attempt attempt, Reason reason, String messageId) {
        this.id = id; this.key = key; this.destination = destination; this.subscriptionId = subscriptionId;
        this.createdAt = createdAt; this.state = state; this.dueAt = dueAt; this.revision = revision;
        this.generation = generation; this.attemptCount = attemptCount; this.lease = lease;
        this.attempt = attempt; this.reason = reason; this.messageId = messageId;
    }
    public static DeliveryJob ready(String id, DeliveryKey key, DestinationRef destination,
                                    String subscriptionId, Instant now) {
        token(id); token(subscriptionId); Objects.requireNonNull(key); Objects.requireNonNull(destination);
        Objects.requireNonNull(now);
        if (!key.channelId().equals(destination.channelId())) throw new IllegalArgumentException("Channel mismatch.");
        return new DeliveryJob(id, key, destination, subscriptionId, now, State.READY, now,
                1, 0, 0, null, null, Reason.CREATED, null);
    }
    public DeliveryJob claim(String token, Instant now, Instant until) {
        if ((state != State.READY && state != State.RETRY_WAIT) || now.isBefore(dueAt))
            throw new IllegalStateException("Job is not due and claimable.");
        if (!now.isBefore(until)) throw new IllegalArgumentException("Lease must end in the future.");
        Lease next = new Lease(token, Math.incrementExact(generation), until);
        return change(State.LEASED, dueAt, next, attempt, attemptCount, Reason.CLAIMED, null);
    }
    public DeliveryJob renew(Lease owner, Instant now, Instant until) {
        owned(owner, now);
        if (!until.isAfter(lease.until())) throw new IllegalArgumentException("Renewal must extend the lease.");
        return change(state, dueAt, new Lease(lease.token(), generation, until), attempt, attemptCount, reason, messageId);
    }
    public DeliveryJob authorize(Lease owner, Instant now, Attempt next) {
        require(State.LEASED); owned(owner, now); Objects.requireNonNull(next);
        if (!next.startedAt().equals(now) || next.ordinal() != Math.incrementExact(attemptCount))
            throw new IllegalArgumentException("Attempt time or ordinal mismatch.");
        if (attempt != null && (attempt.id().equals(next.id()) || !attempt.nonce().equals(next.nonce())))
            throw new IllegalArgumentException("Each attempt needs a new ID and the same per-job nonce.");
        Subscription.checkedRoles(next.roleIds(), destination.guildId());
        return change(State.SENDING, dueAt, lease, next, next.ordinal(), Reason.AUTHORIZED, null);
    }
    public DeliveryJob skip(Lease owner, Instant now, Reason why) {
        require(State.LEASED); owned(owner, now);
        if (why != Reason.CONFIGURATION_CHANGED && why != Reason.OFFER_INELIGIBLE)
            throw new IllegalArgumentException("Invalid skip reason.");
        return change(State.SKIPPED, dueAt, null, attempt, attemptCount, why, null);
    }
    /** Preflight failure has not consumed a send attempt. Application deadline still bounds its retries. */
    public DeliveryJob defer(Lease owner, Instant now, Instant retryAt) {
        require(State.LEASED); owned(owner, now); future(now, retryAt);
        return change(State.RETRY_WAIT, retryAt, null, attempt, attemptCount, Reason.NONACCEPTANCE, null);
    }
    public DeliveryJob complete(Lease owner, String attemptId, Instant now, Outcome outcome,
                                Optional<Instant> retryAt) {
        require(State.SENDING); owned(owner, now); matchingAttempt(attemptId);
        Objects.requireNonNull(outcome); Objects.requireNonNull(retryAt);
        if (outcome instanceof Accepted accepted)
            return change(State.SENT, dueAt, null, attempt, attemptCount, Reason.ACCEPTED, accepted.messageId());
        if (outcome instanceof Unknown)
            return change(State.UNCERTAIN, dueAt, null, attempt, attemptCount, Reason.UNKNOWN_OUTCOME, null);
        DefinitelyRejected rejected = (DefinitelyRejected) outcome;
        if (rejected.retryable() && retryAt.isPresent()) {
            future(now, retryAt.get());
            return change(State.RETRY_WAIT, retryAt.get(), null, attempt, attemptCount, Reason.NONACCEPTANCE, null);
        }
        return change(State.FAILED, dueAt, null, attempt, attemptCount,
                rejected.retryable() ? Reason.RETRIES_EXHAUSTED : Reason.PERMANENT_REJECTION, null);
    }
    /** Recovery must be a conditional database write using this snapshot's revision and lease. */
    public DeliveryJob recoverExpired(Instant now) {
        if ((state != State.LEASED && state != State.SENDING) || now.isBefore(lease.until()))
            throw new IllegalStateException("No expired lease to recover.");
        return change(state == State.SENDING ? State.UNCERTAIN : State.READY, now, null, attempt,
                attemptCount, Reason.LEASE_EXPIRED, null);
    }
    /** Restricted reconciliation port must verify receipt evidence and persist audit atomically. */
    public DeliveryJob reconcileAccepted(String attemptId, String receiptMessageId) {
        require(State.UNCERTAIN); matchingAttempt(attemptId); snowflake(receiptMessageId);
        return change(State.SENT, dueAt, null, attempt, attemptCount, Reason.RECONCILED, receiptMessageId);
    }
    private void owned(Lease owner, Instant now) {
        Objects.requireNonNull(owner); Objects.requireNonNull(now);
        if (lease == null || !lease.token().equals(owner.token()) || lease.generation() != owner.generation()
                || now.isBefore(createdAt) || (attempt != null && now.isBefore(attempt.startedAt()))
                || !now.isBefore(lease.until())) throw new IllegalStateException("Stale or expired lease owner.");
    }
    private void matchingAttempt(String attemptId) {
        if (attempt == null || !attempt.id().equals(attemptId)) throw new IllegalStateException("Wrong attempt.");
    }
    private void require(State expected) {
        if (state != expected) throw new IllegalStateException("Expected " + expected + ", got " + state);
    }
    private static void future(Instant now, Instant retryAt) {
        if (!retryAt.isAfter(now)) throw new IllegalArgumentException("Retry must be in the future.");
    }
    private DeliveryJob change(State next, Instant due, Lease nextLease, Attempt nextAttempt,
                               int count, Reason why, String receipt) {
        return new DeliveryJob(id, key, destination, subscriptionId, createdAt, next, due,
                Math.incrementExact(revision), nextLease == null ? generation : nextLease.generation(),
                count, nextLease, nextAttempt, why, receipt);
    }
    public String id() { return id; }
    public DeliveryKey key() { return key; }
    public DestinationRef destination() { return destination; }
    public String subscriptionId() { return subscriptionId; }
    public Instant createdAt() { return createdAt; }
    public State state() { return state; }
    public Instant dueAt() { return dueAt; }
    public long revision() { return revision; }
    public long generation() { return generation; }
    public int attemptCount() { return attemptCount; }
    public Optional<Lease> lease() { return Optional.ofNullable(lease); }
    public Optional<Attempt> attempt() { return Optional.ofNullable(attempt); }
    public Reason reason() { return reason; }
    public Optional<String> messageId() { return Optional.ofNullable(messageId); }
}
