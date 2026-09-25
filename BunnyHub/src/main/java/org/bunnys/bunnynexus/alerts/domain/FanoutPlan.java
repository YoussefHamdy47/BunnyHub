package org.bunnys.bunnynexus.alerts.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

/** One resumable automatic audience plan, distinct from the canonical event it distributes. */
public record FanoutPlan(String id, String eventId, FreeGameKey notificationKey, Instant observedAt,
                         State state, Optional<String> afterSubscriptionId, long revision, long generation,
                         Optional<DeliveryJob.Lease> lease, long inserted, long duplicates, long skipped, boolean outboxCounted) {
    public FanoutPlan(String id, String eventId, FreeGameKey notificationKey, Instant observedAt, State state,
                      Optional<String> afterSubscriptionId, long revision, long generation, Optional<DeliveryJob.Lease> lease,
                      long inserted, long duplicates, long skipped) {
        this(id, eventId, notificationKey, observedAt, state, afterSubscriptionId, revision, generation, lease, inserted, duplicates, skipped, false);
    }
    public FanoutPlan counted() {
        return new FanoutPlan(id, eventId, notificationKey, observedAt, state, afterSubscriptionId, revision, generation,
                lease, inserted, duplicates, skipped, true);
    }
    public enum State { READY, LEASED, COMPLETE }
    public static final int MAX_PAGE_SIZE = 500;
    public FanoutPlan {
        id = token(id); eventId = token(eventId); Objects.requireNonNull(notificationKey);
        Objects.requireNonNull(observedAt); Objects.requireNonNull(state); Objects.requireNonNull(afterSubscriptionId);
        Objects.requireNonNull(lease); afterSubscriptionId.ifPresent(AlertIdentity::token);
        if (revision < 1 || generation < 0 || inserted < 0 || duplicates < 0 || skipped < 0)
            throw new IllegalArgumentException("Invalid plan counters.");
        if ((state == State.LEASED) != lease.isPresent()
                || (lease.isPresent() && lease.get().generation() != generation))
            throw new IllegalArgumentException("Plan lease/state mismatch.");
    }
    public static FanoutPlan automatic(String id, String eventId, FreeGameKey key, Instant observedAt) {
        return new FanoutPlan(id, eventId, key, observedAt, State.READY, Optional.empty(), 1, 0,
                Optional.empty(), 0, 0, 0);
    }
    public FanoutPlan claim(String token, Instant now, Instant until) {
        if (state != State.READY && !(state == State.LEASED && !now.isBefore(lease.orElseThrow().until())))
            throw new IllegalStateException("Plan is not claimable.");
        if (now.isBefore(observedAt) || !now.isBefore(until)) throw new IllegalArgumentException("Invalid claim time.");
        var next = new DeliveryJob.Lease(token, Math.incrementExact(generation), until);
        return new FanoutPlan(id, eventId, notificationKey, observedAt, State.LEASED, afterSubscriptionId,
                Math.incrementExact(revision), next.generation(), Optional.of(next), inserted, duplicates, skipped, outboxCounted);
    }
    public void requireOwner(DeliveryJob.Lease owner, Instant now) {
        if (state != State.LEASED || !lease.orElseThrow().token().equals(owner.token())
                || generation != owner.generation() || !now.isBefore(lease.orElseThrow().until()))
            throw new IllegalStateException("Stale fan-out owner.");
    }
    /** Empty page alone establishes completion. Page jobs and this value must commit atomically. */
    public FanoutPlan finishPage(DeliveryJob.Lease owner, Instant now, Optional<String> lastId,
                                 int insertedCount, int duplicateCount, int skippedCount) {
        requireOwner(owner, now); Objects.requireNonNull(lastId);
        long count = (long) insertedCount + duplicateCount + skippedCount;
        if (insertedCount < 0 || duplicateCount < 0 || skippedCount < 0 || count > MAX_PAGE_SIZE
                || (count > 0) != lastId.isPresent()) throw new IllegalArgumentException("Invalid page totals.");
        if (lastId.isPresent() && afterSubscriptionId.isPresent()
                && compareIds(lastId.get(), afterSubscriptionId.get()) <= 0)
            throw new IllegalArgumentException("Cursor must advance.");
        return new FanoutPlan(id, eventId, notificationKey, observedAt, count == 0 ? State.COMPLETE : State.READY,
                lastId.isPresent() ? lastId : afterSubscriptionId, Math.incrementExact(revision), generation,
                Optional.empty(), Math.addExact(inserted, insertedCount), Math.addExact(duplicates, duplicateCount),
                Math.addExact(skipped, skippedCount), outboxCounted);
    }
    /** Matches Mongo's simple collation, including IDs outside the BMP. */
    public static int compareIds(String left, String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
    public static int pageSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("Page size must be 1..500.");
        return size;
    }
}
