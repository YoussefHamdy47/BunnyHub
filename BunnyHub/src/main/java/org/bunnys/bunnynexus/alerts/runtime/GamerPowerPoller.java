package org.bunnys.bunnynexus.alerts.runtime;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.AlertIdentity.SourceId;
import org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerFetcher;

/** One explicit shadow tick; no timer, automatic provisioning, ingestion cursor or publication. */
public final class GamerPowerPoller implements AutoCloseable {
    public static final OwnershipRepository.Source SOURCE = new OwnershipRepository.Source(new SourceId("gamerpower"), "games");
    public enum Result { RECORDED, NOT_DUE, NOT_OWNER, BACKPRESSURE, BUSY, CLOSED, LOST, UNCERTAIN }
    private final OwnershipRepository ownership;
    private final PollRepository polls;
    private final Supplier<GamerPowerFetcher.Result> fetch;
    private final Runnable stopFetch;
    private final BooleanSupplier storageAvailable;
    private final LongSupplier ticker;
    private final Duration leaseTime, interval, reserve, fetchTime;
    private final AtomicBoolean active = new AtomicBoolean(), closed = new AtomicBoolean();

    public GamerPowerPoller(OwnershipRepository ownership, PollRepository polls, GamerPowerFetcher fetcher,
                           BooleanSupplier storageAvailable, Duration leaseTime, Duration interval, Duration persistenceReserve) {
        this(ownership, polls, fetcher::fetch, fetcher::close, fetcher.maximumFetchTime(), storageAvailable,
                leaseTime, interval, persistenceReserve, System::nanoTime);
    }
    GamerPowerPoller(OwnershipRepository ownership, PollRepository polls, Supplier<GamerPowerFetcher.Result> fetch,
                    Runnable stopFetch, Duration fetchTime, BooleanSupplier storageAvailable, Duration leaseTime,
                    Duration interval, Duration reserve, LongSupplier ticker) {
        this.ownership = Objects.requireNonNull(ownership); this.polls = Objects.requireNonNull(polls);
        this.fetch = Objects.requireNonNull(fetch); this.stopFetch = Objects.requireNonNull(stopFetch);
        this.storageAvailable = Objects.requireNonNull(storageAvailable); this.ticker = Objects.requireNonNull(ticker);
        this.leaseTime = Objects.requireNonNull(leaseTime); this.interval = Objects.requireNonNull(interval);
        this.reserve = Objects.requireNonNull(reserve); this.fetchTime = Objects.requireNonNull(fetchTime);
        OwnershipRepository.durationMillis(leaseTime);
        if (fetchTime.isNegative() || fetchTime.isZero() || reserve.isNegative() || reserve.isZero()
                || leaseTime.compareTo(fetchTime.plus(reserve)) <= 0) throw new IllegalArgumentException("Lease must cover fetch and persistence reserve.");
        PollRepository.validate(new OwnershipRepository.Lease(SOURCE, "validation", 1, java.time.Instant.EPOCH,
                java.time.Instant.EPOCH.plus(leaseTime)), "validation", interval);
    }
    public Result tick() {
        if (closed.get()) return Result.CLOSED;
        if (!active.compareAndSet(false, true)) return Result.BUSY;
        OwnershipRepository.Lease lease = null;
        boolean releasable = false;
        long started = ticker.getAsLong();
        try {
            if (closed.get()) return Result.CLOSED;
            if (!storageAvailable.getAsBoolean()) return Result.BACKPRESSURE;
            var acquired = ownership.acquire(SOURCE, UUID.randomUUID().toString(), leaseTime);
            if (acquired.isEmpty()) return Result.NOT_OWNER;
            lease = acquired.get();
            if (!lease.resource().equals(SOURCE)) throw new IllegalStateException("Foreign source lease.");
            if (closed.get()) { releasable = true; return Result.CLOSED; }
            String attempt = UUID.randomUUID().toString();
            if (!polls.begin(lease, attempt, interval)) { releasable = true; return Result.NOT_DUE; }
            // Use database-issued lease lifetime minus all local elapsed acquisition/reservation time.
            PollRepository.Completion completion;
            if (!canFetch(lease, started))
                completion = new PollRepository.Completion(PollRepository.Outcome.FAILURE, 0, 0);
            else if (!storageAvailable.getAsBoolean())
                completion = new PollRepository.Completion(PollRepository.Outcome.BACKPRESSURE, 0, 0);
            else if (!canFetch(lease, started))
                completion = new PollRepository.Completion(PollRepository.Outcome.FAILURE, 0, 0);
            else completion = completion(fetch.get());
            boolean recorded = polls.finish(lease, attempt, interval, completion);
            releasable = recorded;
            return recorded ? Result.RECORDED : Result.LOST;
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(GamerPowerPoller.class).error("Shadow poll outcome uncertain | {}",
                    org.bunnys.utils.FailureDiagnostics.describe(failure));
            return Result.UNCERTAIN;
        } finally {
            // Unknown writes leave ownership to expire. Fetch is synchronous: never release during active I/O.
            if (lease != null && releasable) {
                try { ownership.release(lease); }
                catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(GamerPowerPoller.class).error("Shadow poll release uncertain | {}",
                            org.bunnys.utils.FailureDiagnostics.describe(failure));
                }
            }
            active.set(false);
        }
    }
    private boolean canFetch(OwnershipRepository.Lease lease, long started) {
        long elapsed = ticker.getAsLong() - started;
        long budget = Math.min(leaseTime.toNanos(), Duration.between(lease.checkedAt(), lease.until()).toNanos());
        return !closed.get() && elapsed >= 0 && budget - elapsed > fetchTime.plus(reserve).toNanos();
    }
    private static PollRepository.Completion completion(GamerPowerFetcher.Result result) {
        int count = result.batch().map(b -> b.candidates().size()).orElse(0);
        int rejected = result.batch().map(b -> b.rejectedItems()).orElse(0);
        var outcome = switch (result.state()) {
            case FETCHED -> PollRepository.Outcome.SUCCESS;
            case PAUSED -> PollRepository.Outcome.PAUSED;
            case FAILED -> result.httpStatus() == 429 || result.httpStatus() == 401 || result.httpStatus() == 403
                    ? PollRepository.Outcome.PAUSED : (result.batch().filter(b -> b.state() == org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerIntake.State.PARTIAL).isPresent()
                    ? PollRepository.Outcome.PARTIAL : PollRepository.Outcome.FAILURE);
            default -> PollRepository.Outcome.FAILURE;
        };
        return new PollRepository.Completion(outcome, count, rejected);
    }
    @Override public void close() { closed.set(true); stopFetch.run(); }
}
