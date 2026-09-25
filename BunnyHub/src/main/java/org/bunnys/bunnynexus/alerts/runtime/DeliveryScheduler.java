package org.bunnys.bunnynexus.alerts.runtime;

import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Explicit bounded ticks on a dedicated background I/O lane; never a gateway/command callback. No threads created. */
public final class DeliveryScheduler implements AutoCloseable {
    public record Budget(int pageSize, int guildsPerTick, int claimsPerTick, int recoveriesPerTick,
                         Duration claimLease, Duration claimSpacing) {
        public Budget {
            FanoutPlan.pageSize(pageSize); FanoutPlan.pageSize(claimsPerTick); FanoutPlan.pageSize(recoveriesPerTick);
            if (guildsPerTick < 1 || guildsPerTick > 16) throw new IllegalArgumentException("Guild reads must be bounded.");
            OwnershipRepository.durationMillis(claimLease);
            if (claimSpacing.isNegative() || claimSpacing.isZero() || claimSpacing.compareTo(Duration.ofMinutes(1)) > 0)
                throw new IllegalArgumentException("Positive admission spacing up to one minute required.");
        }
    }
    private final SchedulingRepository candidates;
    private final DeliveryQueue queue;
    private final RuntimeOwnership ownership;
    private final SendAdmission admission;
    private final FairCandidateWindow window;
    private final Budget budget;
    private final Consumer<ScheduledDelivery> handler;
    private final Supplier<String> tokens;
    private final LongSupplier nanoTime;
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final EnumMap<DeliveryJob.State, SchedulingRepository.Cursor> cursors = new EnumMap<>(DeliveryJob.State.class);
    private Optional<String> afterGuild = Optional.empty();
    private long lastClaim;
    private boolean hasClaimed;
    private int refillRound;
    private boolean recoverLeasedFirst;

    public DeliveryScheduler(SchedulingRepository candidates, DeliveryQueue queue, RuntimeOwnership ownership,
                             SendAdmission admission, int windowSize, Budget budget, Consumer<ScheduledDelivery> handler,
                             Supplier<String> uniqueTokens, LongSupplier nanoTime) {
        this.candidates = Objects.requireNonNull(candidates); this.queue = Objects.requireNonNull(queue);
        this.ownership = Objects.requireNonNull(ownership); this.admission = Objects.requireNonNull(admission);
        if (!ownership.usesAdmission(admission)) throw new IllegalArgumentException("Runtime and scheduler must share admission accounting.");
        this.window = new FairCandidateWindow(windowSize); this.budget = Objects.requireNonNull(budget);
        this.handler = Objects.requireNonNull(handler); this.tokens = Objects.requireNonNull(uniqueTokens); this.nanoTime = Objects.requireNonNull(nanoTime);
    }
    public int tick() {
        if (!ticking.compareAndSet(false, true)) return 0;
        try {
            if (!canAdmit() || !paced()) return 0;
            refill(); int claimed = 0, attempted = 0, inspected = window.size();
            for (int i = 0; i < inspected && attempted < budget.claimsPerTick() && canAdmit() && paced(); i++) {
                var selected = window.poll();
                if (selected.isEmpty()) break;
                var candidate = selected.get();
                var permit = admission.tryReserve(candidate.destination().channelId());
                if (permit.isEmpty()) continue; // Discard the hint; durable paging revisits it without blocking other channels.
                DeliveryJob job;
                try {
                    if (!ownership.active()) { permit.get().cancelBeforeAttempt(); break; }
                    lastClaim = nanoTime.getAsLong(); hasClaimed = true;
                    attempted++;
                    String token = AlertIdentity.token(tokens.get());
                    var result = queue.claim(candidate.id(), token, budget.claimLease());
                    if (result.isEmpty()) { permit.get().cancelBeforeAttempt(); continue; }
                    job = result.get();
                    if (!job.id().equals(candidate.id()) || !job.key().equals(candidate.key()) || !job.subscriptionId().equals(candidate.subscriptionId())
                            || !job.destination().equals(candidate.destination()) || job.state() != DeliveryJob.State.LEASED
                            || !job.lease().orElseThrow().token().equals(token) || job.generation() <= candidate.generation())
                        throw new IllegalStateException("Claim does not match reserved candidate.");
                } catch (RuntimeException | Error failure) { permit.get().cancelBeforeAttempt(); throw failure; }
                if (!ownership.active()) { permit.get().cancelBeforeAttempt(); break; }
                var work = new ScheduledDelivery(job, permit.get(), ownership, nanoTime);
                try { handler.accept(work); } catch (RuntimeException | Error failure) { work.handlerFailed(); throw failure; }
                claimed++;
            }
            return claimed;
        } catch (RuntimeException | Error failure) { ownership.lose(); throw failure; }
        finally { ticking.set(false); }
    }
    private boolean canAdmit() {
        var snapshot = admission.snapshot();
        return ownership.active() && !snapshot.closed() && snapshot.persistenceFailures() == 0 && snapshot.occupied() < snapshot.capacity();
    }
    private boolean paced() {
        long elapsed = nanoTime.getAsLong() - lastClaim;
        return !hasClaimed || (elapsed >= 0 && elapsed >= budget.claimSpacing().toNanos());
    }
    private void refill() {
        if (window.full()) return;
        int round = refillRound++;
        var states = (round & 2) == 0 ? List.of(DeliveryJob.State.READY, DeliveryJob.State.RETRY_WAIT)
                : List.of(DeliveryJob.State.RETRY_WAIT, DeliveryJob.State.READY);
        if ((round & 1) == 0) { refillGuilds(states); refillGlobal(states); }
        else { refillGlobal(states); refillGuilds(states); }
    }
    private void refillGuilds(List<DeliveryJob.State> states) {
        if (window.full() || !ownership.active()) return;
        var guilds = bounded(candidates.guilds(afterGuild, budget.guildsPerTick()), budget.guildsPerTick());
        if (guilds.isEmpty()) afterGuild = Optional.empty();
        for (String guild : guilds) {
            if (window.full() || !ownership.active()) break;
            AlertIdentity.snowflake(guild);
            if (afterGuild.isPresent() && guild.compareTo(afterGuild.get()) <= 0) throw new IllegalStateException("Guild cursor did not advance.");
            for (var state : states) {
                if (window.full() || !ownership.active()) break;
                add(candidates.due(state, Optional.of(guild), Optional.empty(), budget.pageSize()));
            }
            // Advance only visited guilds, so a full window cannot repeatedly skip the rest of a guild page.
            afterGuild = Optional.of(guild);
        }
    }
    private void refillGlobal(List<DeliveryJob.State> states) {
        for (var state : states) {
            if (window.full() || !ownership.active()) break;
            var page = candidates.due(state, Optional.empty(), Optional.ofNullable(cursors.get(state)), budget.pageSize());
            add(page);
            if (page.isEmpty()) cursors.remove(state); else cursors.put(state, SchedulingRepository.Cursor.after(page.getLast()));
        }
    }
    private void add(List<DeliveryJob> page) { bounded(page, budget.pageSize()).forEach(window::offer); }
    private static <T> List<T> bounded(List<T> page, int limit) {
        Objects.requireNonNull(page); if (page.size() > limit) throw new IllegalStateException("Oversized scheduler page."); return page;
    }
    /** Recovery has its own bounded turn and shares the single I/O lane, never recovering SENDING as READY. */
    public int recoverTick() {
        if (!ticking.compareAndSet(false, true)) return 0;
        try {
            int recovered = 0, inspected = 0;
            var states = recoverLeasedFirst ? List.of(DeliveryJob.State.LEASED, DeliveryJob.State.SENDING)
                    : List.of(DeliveryJob.State.SENDING, DeliveryJob.State.LEASED);
            recoverLeasedFirst = !recoverLeasedFirst;
            for (var state : states) {
                if (!ownership.active() || inspected >= budget.recoveriesPerTick()) break;
                for (var job : bounded(candidates.expired(state, budget.recoveriesPerTick() - inspected), budget.recoveriesPerTick() - inspected)) {
                    if (!ownership.active()) break;
                    if (job.state() != state) throw new IllegalStateException("Wrong recovery state.");
                    inspected++;
                    if (queue.recoverExpired(job.id(), job.revision()).isPresent()) recovered++;
                }
            }
            return recovered;
        } catch (RuntimeException | Error failure) { ownership.lose(); throw failure; }
        finally { ticking.set(false); }
    }
    public int bufferedCandidates() { return window.size(); }
    @Override public void close() { ownership.close(); window.clear(); }
}
